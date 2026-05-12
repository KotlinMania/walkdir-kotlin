// port-lint: ignore — Kotlin-only tests; upstream lives in tests/walk_dir.rs but
// translates poorly without a real filesystem, so this exercise drives the port
// against an in-memory [Sys] instead.
package io.github.kotlinmania.walkdir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An in-memory [Sys] backed by a map of paths to file-type/children pairs.
 *
 * Paths use `/` as separator. Directories list their children's full paths.
 */
private class InMemorySys(
    /** Map of path → (file type, optional list of children paths for directories). */
    private val tree: Map<String, Pair<FileType, List<String>>>,
) : Sys {
    private inner class MemHandle(val path: String) : Handle {
        override fun equals(other: Any?): Boolean =
            other is InMemorySys.MemHandle && other.path == path
        override fun hashCode(): Int = path.hashCode()
    }

    private inner class MemMetadata(override val fileType: FileType) : Metadata {
        override val ino: ULong? = null
        override val dev: ULong? = 1UL
    }

    private inner class MemDirEntry(val full: String, val ty: FileType) : RawDirEntry {
        override fun path(): String = full
        override fun fileType(): Result<FileType> = Result.success(ty)
        override fun metadata(): Result<Metadata> = Result.success(MemMetadata(ty))
        override val ino: ULong? = null
    }

    override fun readDir(path: String): Result<Iterator<Result<RawDirEntry>>> {
        val entry = tree[path]
            ?: return Result.failure(IoError(IoErrorKind.NOT_FOUND, "no such path: $path"))
        if (!entry.first.isDir) {
            return Result.failure(IoError(IoErrorKind.NOT_A_DIRECTORY, "not a directory: $path"))
        }
        val children = entry.second.map { child ->
            val ty = tree[child]?.first
                ?: return@map Result.failure<RawDirEntry>(
                    IoError(IoErrorKind.NOT_FOUND, "missing child: $child"),
                )
            Result.success(MemDirEntry(child, ty) as RawDirEntry)
        }
        return Result.success(children.iterator())
    }

    override fun metadata(path: String): Result<Metadata> {
        val entry = tree[path]
            ?: return Result.failure(IoError(IoErrorKind.NOT_FOUND, "no such path: $path"))
        return Result.success(MemMetadata(entry.first))
    }

    override fun symlinkMetadata(path: String): Result<Metadata> = metadata(path)

    override fun deviceNum(path: String): Result<ULong> = Result.success(1UL)

    override fun handleFromPath(path: String): Result<Handle> =
        Result.success(MemHandle(path))
}

private val DIR = FileType(isDir = true, isFile = false, isSymlink = false)
private val FILE = FileType(isDir = false, isFile = true, isSymlink = false)

class WalkDirTest {
    @Test
    fun visitsRootDepthFirst() {
        val sys = InMemorySys(mapOf(
            "foo" to (DIR to listOf("foo/abc", "foo/def")),
            "foo/abc" to (DIR to listOf("foo/abc/qrs", "foo/abc/tuv")),
            "foo/abc/qrs" to (FILE to emptyList()),
            "foo/abc/tuv" to (FILE to emptyList()),
            "foo/def" to (DIR to emptyList()),
        ))
        val seen = WalkDir.new("foo").sortByFileName().intoIter(sys)
            .asSequence().map { it.getOrThrow().path() }.toList()
        assertEquals(
            listOf("foo", "foo/abc", "foo/abc/qrs", "foo/abc/tuv", "foo/def"),
            seen,
        )
    }

    @Test
    fun minDepthSkipsRoot() {
        val sys = InMemorySys(mapOf(
            "foo" to (DIR to listOf("foo/a", "foo/b")),
            "foo/a" to (FILE to emptyList()),
            "foo/b" to (FILE to emptyList()),
        ))
        val seen = WalkDir.new("foo").minDepth(1).sortByFileName().intoIter(sys)
            .asSequence().map { it.getOrThrow().path() }.toList()
        assertEquals(listOf("foo/a", "foo/b"), seen)
    }

    @Test
    fun maxDepthStopsDescent() {
        val sys = InMemorySys(mapOf(
            "r" to (DIR to listOf("r/a")),
            "r/a" to (DIR to listOf("r/a/b")),
            "r/a/b" to (FILE to emptyList()),
        ))
        val seen = WalkDir.new("r").maxDepth(1).sortByFileName().intoIter(sys)
            .asSequence().map { it.getOrThrow().path() }.toList()
        assertEquals(listOf("r", "r/a"), seen)
    }

    @Test
    fun contentsFirstYieldsLeavesBeforeParents() {
        val sys = InMemorySys(mapOf(
            "foo" to (DIR to listOf("foo/abc", "foo/def")),
            "foo/abc" to (DIR to listOf("foo/abc/qrs", "foo/abc/tuv")),
            "foo/abc/qrs" to (FILE to emptyList()),
            "foo/abc/tuv" to (FILE to emptyList()),
            "foo/def" to (DIR to emptyList()),
        ))
        val seen = WalkDir.new("foo").contentsFirst(true).sortByFileName().intoIter(sys)
            .asSequence().map { it.getOrThrow().path() }.toList()
        assertEquals(
            listOf("foo/abc/qrs", "foo/abc/tuv", "foo/abc", "foo/def", "foo"),
            seen,
        )
    }

    @Test
    fun filterEntrySkipsHidden() {
        val sys = InMemorySys(mapOf(
            "r" to (DIR to listOf("r/.hidden", "r/visible")),
            "r/.hidden" to (DIR to listOf("r/.hidden/x")),
            "r/.hidden/x" to (FILE to emptyList()),
            "r/visible" to (FILE to emptyList()),
        ))
        val iter = WalkDir.new("r").sortByFileName().intoIter(sys).filterEntry { dent ->
            !dent.fileName().startsWith(".")
        }
        val seen = iter.asSequence().map { it.getOrThrow().path() }.toList()
        assertEquals(listOf("r", "r/visible"), seen)
    }

    @Test
    fun rootMissingYieldsError() {
        val sys = InMemorySys(emptyMap())
        val iter = WalkDir.new("nope").intoIter(sys)
        assertTrue(iter.hasNext())
        val first = iter.next()
        val err = first.exceptionOrNull()
        assertNotNull(err)
        val werr = err as Error
        assertEquals(0, werr.depth())
        assertEquals("nope", werr.path())
        assertNotNull(werr.ioError())
        assertEquals(IoErrorKind.NOT_FOUND, werr.ioError()!!.kind)
        assertFalse(iter.hasNext())
    }

    @Test
    fun errorOnSubdirContinuesIteration() {
        val base = InMemorySys(mapOf(
            "r" to (DIR to listOf("r/a", "r/b")),
            "r/a" to (DIR to listOf("r/a/x")),
            "r/a/x" to (FILE to emptyList()),
            "r/b" to (DIR to emptyList()),
        ))
        val sys = object : Sys {
            override fun readDir(path: String): Result<Iterator<Result<RawDirEntry>>> =
                if (path == "r/a") Result.failure(IoError(IoErrorKind.PERMISSION_DENIED, "denied"))
                else base.readDir(path)

            override fun metadata(path: String): Result<Metadata> = base.metadata(path)
            override fun symlinkMetadata(path: String): Result<Metadata> = base.symlinkMetadata(path)
            override fun deviceNum(path: String): Result<ULong> = base.deviceNum(path)
            override fun handleFromPath(path: String): Result<Handle> = base.handleFromPath(path)
        }
        val outcomes = WalkDir.new("r").sortByFileName().intoIter(sys)
            .asSequence().toList()
        val paths = outcomes.map { it.fold(onSuccess = { e -> e.path() }, onFailure = { "ERR" }) }
        assertEquals(listOf("r", "r/a", "ERR", "r/b"), paths)
    }

    @Test
    fun fileNameOfRoot() {
        assertEquals("foo", fileNameOf("foo"))
        assertEquals("a", fileNameOf("/x/a"))
        assertEquals("a", fileNameOf("/x/a/"))
        assertNull(fileNameOf("/"))
        assertNull(fileNameOf(""))
    }

    @Test
    fun walkDirOptionsToStringMatchesUpstreamShape() {
        val opts = WalkDirOptions()
        val s = opts.toString()
        assertTrue("sorter=None" in s, "expected sorter=None, got: $s")
        assertTrue("followLinks=false" in s)
        assertTrue("contentsFirst=false" in s)
    }
}
