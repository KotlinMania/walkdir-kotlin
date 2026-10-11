// port-lint: tests tests/recursive.rs
package io.github.kotlinmania.walkdir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private sealed class TestNode {
    data class Dir(
        val children: MutableList<String> = mutableListOf(),
    ) : TestNode()

    data class File(
        val contents: String = "",
    ) : TestNode()

    data class Symlink(
        val target: String,
    ) : TestNode()
}

/**
 * An in-memory [Sys] backed by a map of paths to file nodes.
 *
 * Paths use `/` as separator. Directories list their children's full paths.
 */
private class InMemorySys : Sys {
    val nodes = mutableMapOf<String, TestNode>()
    val symlinkTargets = mutableMapOf<String, String>()
    val devices = mutableMapOf<String, ULong>()

    constructor()

    constructor(tree: Map<String, Pair<FileType, List<String>>>) {
        for ((path, pair) in tree) {
            val (ty, children) = pair
            when {
                ty.isDir -> nodes[path] = TestNode.Dir(children.toMutableList())
                ty.isSymlink -> {
                    val target = children.firstOrNull() ?: ""
                    nodes[path] = TestNode.Symlink(target)
                    symlinkTargets[path] = target
                }
                else -> nodes[path] = TestNode.File()
            }
        }
    }

    fun mkdirp(path: String) {
        val parts = path.split('/').filter { it.isNotEmpty() }
        var curr = if (path.startsWith("/")) "" else ""
        for (part in parts) {
            val next =
                if (curr.isEmpty()) {
                    if (path.startsWith("/")) "/$part" else part
                } else {
                    "$curr/$part"
                }
            val node = nodes[next]
            if (node == null) {
                nodes[next] = TestNode.Dir()
                if (curr.isNotEmpty()) {
                    val parent = nodes[curr] as? TestNode.Dir
                    if (parent != null && !parent.children.contains(next)) {
                        parent.children.add(next)
                    }
                }
            }
            curr = next
        }
    }

    fun touch(path: String) {
        val parentPath = path.substringBeforeLast('/', "")
        if (parentPath.isNotEmpty()) {
            mkdirp(parentPath)
        }
        nodes[path] = TestNode.File()
        if (parentPath.isNotEmpty()) {
            val parent = nodes[parentPath] as? TestNode.Dir
            if (parent != null && !parent.children.contains(path)) {
                parent.children.add(path)
            }
        }
    }

    fun symlink(target: String, link: String) {
        val parentPath = link.substringBeforeLast('/', "")
        if (parentPath.isNotEmpty()) {
            mkdirp(parentPath)
        }
        nodes[link] = TestNode.Symlink(target)
        symlinkTargets[link] = target
        if (parentPath.isNotEmpty()) {
            val parent = nodes[parentPath] as? TestNode.Dir
            if (parent != null && !parent.children.contains(link)) {
                parent.children.add(link)
            }
        }
    }

    fun resolve(path: String, maxHops: Int = 32): Pair<String, TestNode>? {
        var curr = path
        var hops = 0
        while (hops < maxHops) {
            val node = nodes[curr] ?: return null
            when (node) {
                is TestNode.Symlink -> {
                    if (node.target == curr) return null // self loop
                    curr = node.target
                    hops++
                }
                else -> return curr to node
            }
        }
        return null // loop exceeded
    }

    fun readLink(path: String): String? = symlinkTargets[path]

    private inner class MemHandle(
        val canonical: String,
    ) : Handle {
        override fun equals(other: Any?): Boolean =
            other is MemHandle && other.canonical == canonical

        override fun hashCode(): Int = canonical.hashCode()
    }

    private inner class MemMetadata(
        override val fileType: FileType,
        override val dev: ULong = 1UL,
    ) : Metadata {
        override val ino: ULong? = null
    }

    private inner class MemRawDirEntry(
        val fullPath: String,
        val rawType: FileType,
    ) : RawDirEntry {
        override fun path(): String = fullPath

        override fun fileType(): Result<FileType> = Result.success(rawType)

        override fun metadata(): Result<Metadata> = Result.success(MemMetadata(rawType))

        override val ino: ULong? = null
    }

    override fun readDir(path: String): Result<Iterator<Result<RawDirEntry>>> {
        val resolved =
            resolve(path)
                ?: return Result.failure(IoError(IoErrorKind.NOT_FOUND, "no such path: $path"))
        val dir =
            resolved.second as? TestNode.Dir
                ?: return Result.failure(IoError(IoErrorKind.NOT_A_DIRECTORY, "not a directory: $path"))

        val entries =
            dir.children.map { childPath ->
                val childNode =
                    nodes[childPath]
                        ?: return@map Result.failure<RawDirEntry>(
                            IoError(IoErrorKind.NOT_FOUND, "missing child: $childPath"),
                        )
                val childName = childPath.substringAfterLast('/')
                val fullPath = if (path.endsWith("/")) "$path$childName" else "$path/$childName"
                val rawType =
                    when (childNode) {
                        is TestNode.Dir -> FileType(isDir = true, isFile = false, isSymlink = false)
                        is TestNode.File -> FileType(isDir = false, isFile = true, isSymlink = false)
                        is TestNode.Symlink -> FileType(isDir = false, isFile = false, isSymlink = true)
                    }
                Result.success(MemRawDirEntry(fullPath, rawType) as RawDirEntry)
            }
        return Result.success(entries.iterator())
    }

    override fun metadata(path: String): Result<Metadata> {
        val resolved =
            resolve(path)
                ?: return Result.failure(IoError(IoErrorKind.NOT_FOUND, "no such path or broken symlink: $path"))
        val devNum = devices[resolved.first] ?: 1UL
        val ty =
            when (resolved.second) {
                is TestNode.Dir -> FileType(isDir = true, isFile = false, isSymlink = false)
                is TestNode.File -> FileType(isDir = false, isFile = true, isSymlink = false)
                is TestNode.Symlink -> FileType(isDir = false, isFile = false, isSymlink = true)
            }
        return Result.success(MemMetadata(ty, devNum))
    }

    override fun symlinkMetadata(path: String): Result<Metadata> {
        val node =
            nodes[path]
                ?: return Result.failure(IoError(IoErrorKind.NOT_FOUND, "no such path: $path"))
        val devNum = devices[path] ?: 1UL
        val ty =
            when (node) {
                is TestNode.Dir -> FileType(isDir = true, isFile = false, isSymlink = false)
                is TestNode.File -> FileType(isDir = false, isFile = true, isSymlink = false)
                is TestNode.Symlink -> FileType(isDir = false, isFile = false, isSymlink = true)
            }
        return Result.success(MemMetadata(ty, devNum))
    }

    override fun deviceNum(path: String): Result<ULong> {
        val resolved = resolve(path)
        val devNum = if (resolved != null) devices[resolved.first] ?: devices[path] ?: 1UL else devices[path] ?: 1UL
        return Result.success(devNum)
    }

    override fun handleFromPath(path: String): Result<Handle> {
        val node =
            nodes[path]
                ?: return Result.failure(IoError(IoErrorKind.NOT_FOUND, "no such path: $path"))
        if (node is TestNode.Symlink && node.target == path) {
            return Result.failure(IoError(IoErrorKind.OTHER, "self loop symlink"))
        }
        val resolved =
            resolve(path)
                ?: return Result.failure(IoError(IoErrorKind.NOT_FOUND, "unresolvable path: $path"))
        return Result.success(MemHandle(resolved.first))
    }
}

private fun comparePaths(p1: String, p2: String): Int {
    val s1 = p1.split('/').filter { it.isNotEmpty() }
    val s2 = p2.split('/').filter { it.isNotEmpty() }
    val len = minOf(s1.size, s2.size)
    for (i in 0 until len) {
        val cmp = s1[i].compareTo(s2[i])
        if (cmp != 0) return cmp
    }
    return s1.size.compareTo(s2.size)
}

private class RecursiveResults(
    private val ents: List<DirEntry>,
    private val errs: List<Error>,
) {
    fun errs(): List<Error> = errs

    fun assertNoErrors() {
        assertTrue(errs.isEmpty(), "expected to find no errors, but found: $errs")
    }

    fun ents(): List<DirEntry> = ents

    fun paths(): List<String> = ents.map { it.path() }

    fun sortedEnts(): List<DirEntry> = ents.sortedWith { e1, e2 -> comparePaths(e1.path(), e2.path()) }

    fun sortedPaths(): List<String> = sortedEnts().map { it.path() }
}

private class TestDir(
    val path: String,
    val sys: InMemorySys,
) {
    companion object {
        private var counter = 0

        fun tmp(): TestDir {
            val p = "test_dir_${++counter}"
            val sys = InMemorySys()
            sys.mkdirp(p)
            return TestDir(p, sys)
        }
    }

    fun join(sub: String): String = "$path/$sub"

    fun mkdirp(sub: String) {
        sys.mkdirp(join(sub))
    }

    fun touch(sub: String) {
        sys.touch(join(sub))
    }

    fun touchAll(subs: List<String>) {
        for (sub in subs) touch(sub)
    }

    fun symlinkFile(target: String, linkName: String) {
        sys.symlink(target = join(target), link = join(linkName))
    }

    fun symlinkDir(target: String, linkName: String) {
        val targetPath = if (target.startsWith("/")) target else join(target)
        sys.symlink(target = targetPath, link = join(linkName))
    }

    fun readLink(linkPath: String): String = sys.readLink(linkPath) ?: ""

    fun runRecursive(wd: WalkDir): RecursiveResults {
        val iter = wd.intoIter(sys)
        val ents = mutableListOf<DirEntry>()
        val errs = mutableListOf<Error>()
        while (iter.hasNext()) {
            val res = iter.next()
            res.fold(
                onSuccess = { ents.add(it) },
                onFailure = { errs.add(it as Error) },
            )
        }
        return RecursiveResults(ents, errs)
    }
}

private val DIR = FileType(isDir = true, isFile = false, isSymlink = false)
private val FILE = FileType(isDir = false, isFile = true, isSymlink = false)

class WalkDirTest {
    // send_sync_traits: Send and Sync are Rust compiler auto traits with no Kotlin equivalent.

    @Test
    fun visitsRootDepthFirst() {
        val sys =
            InMemorySys(
                mapOf(
                    "foo" to (DIR to listOf("foo/abc", "foo/def")),
                    "foo/abc" to (DIR to listOf("foo/abc/qrs", "foo/abc/tuv")),
                    "foo/abc/qrs" to (FILE to emptyList()),
                    "foo/abc/tuv" to (FILE to emptyList()),
                    "foo/def" to (DIR to emptyList()),
                ),
            )
        val seen =
            WalkDir
                .new("foo")
                .sortByFileName()
                .intoIter(sys)
                .asSequence()
                .map { it.getOrThrow().path() }
                .toList()
        assertEquals(
            listOf("foo", "foo/abc", "foo/abc/qrs", "foo/abc/tuv", "foo/def"),
            seen,
        )
    }

    @Test
    fun minDepthSkipsRoot() {
        val sys =
            InMemorySys(
                mapOf(
                    "foo" to (DIR to listOf("foo/a", "foo/b")),
                    "foo/a" to (FILE to emptyList()),
                    "foo/b" to (FILE to emptyList()),
                ),
            )
        val seen =
            WalkDir
                .new("foo")
                .minDepth(1)
                .sortByFileName()
                .intoIter(sys)
                .asSequence()
                .map { it.getOrThrow().path() }
                .toList()
        assertEquals(listOf("foo/a", "foo/b"), seen)
    }

    @Test
    fun maxDepthStopsDescent() {
        val sys =
            InMemorySys(
                mapOf(
                    "r" to (DIR to listOf("r/a")),
                    "r/a" to (DIR to listOf("r/a/b")),
                    "r/a/b" to (FILE to emptyList()),
                ),
            )
        val seen =
            WalkDir
                .new("r")
                .maxDepth(1)
                .sortByFileName()
                .intoIter(sys)
                .asSequence()
                .map { it.getOrThrow().path() }
                .toList()
        assertEquals(listOf("r", "r/a"), seen)
    }

    @Test
    fun contentsFirstYieldsLeavesBeforeParents() {
        val sys =
            InMemorySys(
                mapOf(
                    "foo" to (DIR to listOf("foo/abc", "foo/def")),
                    "foo/abc" to (DIR to listOf("foo/abc/qrs", "foo/abc/tuv")),
                    "foo/abc/qrs" to (FILE to emptyList()),
                    "foo/abc/tuv" to (FILE to emptyList()),
                    "foo/def" to (DIR to emptyList()),
                ),
            )
        val seen =
            WalkDir
                .new("foo")
                .contentsFirst(true)
                .sortByFileName()
                .intoIter(sys)
                .asSequence()
                .map { it.getOrThrow().path() }
                .toList()
        assertEquals(
            listOf("foo/abc/qrs", "foo/abc/tuv", "foo/abc", "foo/def", "foo"),
            seen,
        )
    }

    @Test
    fun filterEntrySkipsHidden() {
        val sys =
            InMemorySys(
                mapOf(
                    "r" to (DIR to listOf("r/.hidden", "r/visible")),
                    "r/.hidden" to (DIR to listOf("r/.hidden/x")),
                    "r/.hidden/x" to (FILE to emptyList()),
                    "r/visible" to (FILE to emptyList()),
                ),
            )
        val iter =
            WalkDir.new("r").sortByFileName().intoIter(sys).filterEntry { dent ->
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
        val base =
            InMemorySys(
                mapOf(
                    "r" to (DIR to listOf("r/a", "r/b")),
                    "r/a" to (DIR to listOf("r/a/x")),
                    "r/a/x" to (FILE to emptyList()),
                    "r/b" to (DIR to emptyList()),
                ),
            )
        val sys =
            object : Sys {
                override fun readDir(path: String): Result<Iterator<Result<RawDirEntry>>> =
                    if (path == "r/a") {
                        Result.failure(IoError(IoErrorKind.PERMISSION_DENIED, "denied"))
                    } else {
                        base.readDir(path)
                    }

                override fun metadata(path: String): Result<Metadata> = base.metadata(path)

                override fun symlinkMetadata(path: String): Result<Metadata> = base.symlinkMetadata(path)

                override fun deviceNum(path: String): Result<ULong> = base.deviceNum(path)

                override fun handleFromPath(path: String): Result<Handle> = base.handleFromPath(path)
            }
        val outcomes =
            WalkDir
                .new("r")
                .sortByFileName()
                .intoIter(sys)
                .asSequence()
                .toList()
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

    @Test
    fun empty() {
        val dir = TestDir.tmp()
        val wd = WalkDir.new(dir.path)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        assertEquals(1, r.ents().size)
        val ent = r.ents()[0]
        assertTrue(ent.fileType().isDir)
        assertFalse(ent.pathIsSymlink())
        assertEquals(0, ent.depth())
        assertEquals(dir.path, ent.path())
        assertEquals(dir.path.substringAfterLast('/'), ent.fileName())
    }

    @Test
    fun emptyFollow() {
        val dir = TestDir.tmp()
        val wd = WalkDir.new(dir.path).followLinks(true)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        assertEquals(1, r.ents().size)
        val ent = r.ents()[0]
        assertTrue(ent.fileType().isDir)
        assertFalse(ent.pathIsSymlink())
        assertEquals(0, ent.depth())
        assertEquals(dir.path, ent.path())
        assertEquals(dir.path.substringAfterLast('/'), ent.fileName())
    }

    @Test
    fun emptyFile() {
        val dir = TestDir.tmp()
        dir.touch("a")

        val wd = WalkDir.new(dir.join("a"))
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        assertEquals(1, r.ents().size)
        val ent = r.ents()[0]
        assertTrue(ent.fileType().isFile)
        assertFalse(ent.pathIsSymlink())
        assertEquals(0, ent.depth())
        assertEquals(dir.join("a"), ent.path())
        assertEquals("a", ent.fileName())
    }

    @Test
    fun emptyFileFollow() {
        val dir = TestDir.tmp()
        dir.touch("a")

        val wd = WalkDir.new(dir.join("a")).followLinks(true)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        assertEquals(1, r.ents().size)
        val ent = r.ents()[0]
        assertTrue(ent.fileType().isFile)
        assertFalse(ent.pathIsSymlink())
        assertEquals(0, ent.depth())
        assertEquals(dir.join("a"), ent.path())
        assertEquals("a", ent.fileName())
    }

    @Test
    fun oneDir() {
        val dir = TestDir.tmp()
        dir.mkdirp("a")

        val wd = WalkDir.new(dir.path)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val ents = r.ents()
        assertEquals(2, ents.size)
        val ent = ents[1]
        assertEquals(dir.join("a"), ent.path())
        assertEquals(1, ent.depth())
        assertEquals("a", ent.fileName())
        assertTrue(ent.fileType().isDir)
    }

    @Test
    fun oneFile() {
        val dir = TestDir.tmp()
        dir.touch("a")

        val wd = WalkDir.new(dir.path)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val ents = r.ents()
        assertEquals(2, ents.size)
        val ent = ents[1]
        assertEquals(dir.join("a"), ent.path())
        assertEquals(1, ent.depth())
        assertEquals("a", ent.fileName())
        assertTrue(ent.fileType().isFile)
    }

    @Test
    fun oneDirOneFile() {
        val dir = TestDir.tmp()
        dir.touch("foo/a")

        val wd = WalkDir.new(dir.path)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val expected = listOf(dir.path, dir.join("foo"), dir.join("foo/a"))
        assertEquals(expected, r.sortedPaths())
    }

    @Test
    fun manyFiles() {
        val dir = TestDir.tmp()
        dir.touchAll(listOf("foo/a", "foo/b", "foo/c"))

        val wd = WalkDir.new(dir.path)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val expected = listOf(dir.path, dir.join("foo"), dir.join("foo/a"), dir.join("foo/b"), dir.join("foo/c"))
        assertEquals(expected, r.sortedPaths())
    }

    @Test
    fun manyDirs() {
        val dir = TestDir.tmp()
        dir.mkdirp("foo/a")
        dir.mkdirp("foo/b")
        dir.mkdirp("foo/c")

        val wd = WalkDir.new(dir.path)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val expected = listOf(dir.path, dir.join("foo"), dir.join("foo/a"), dir.join("foo/b"), dir.join("foo/c"))
        assertEquals(expected, r.sortedPaths())
    }

    @Test
    fun manyMixed() {
        val dir = TestDir.tmp()
        dir.mkdirp("foo/a")
        dir.touch("foo/b")
        dir.mkdirp("foo/c")
        dir.touch("foo/d")
        dir.mkdirp("foo/e")
        dir.touch("foo/f")

        val wd = WalkDir.new(dir.path)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val expected =
            listOf(
                dir.path,
                dir.join("foo"),
                dir.join("foo/a"),
                dir.join("foo/b"),
                dir.join("foo/c"),
                dir.join("foo/d"),
                dir.join("foo/e"),
                dir.join("foo/f"),
            )
        assertEquals(expected, r.sortedPaths())
    }

    @Test
    fun nested() {
        val dir = TestDir.tmp()
        val chain = "a/b/c/d/e/f/g/h/i/j/k/l/m/n/o/p/q/r/s/t/u/v/w/x/y/z"
        dir.touch("$chain/A")

        val wd = WalkDir.new(dir.path)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val parts = chain.split('/')
        val expected =
            buildList {
                add(dir.path)
                var p = dir.path
                for (seg in parts) {
                    p = "$p/$seg"
                    add(p)
                }
                add("$p/A")
            }
        assertEquals(expected, r.sortedPaths())
    }

    @Test
    fun nestedSmallMaxOpen() {
        val dir = TestDir.tmp()
        val chain = "a/b/c/d/e/f/g/h/i/j/k/l/m/n/o/p/q/r/s/t/u/v/w/x/y/z"
        dir.touch("$chain/A")

        val wd = WalkDir.new(dir.path).maxOpen(1)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val parts = chain.split('/')
        val expected =
            buildList {
                add(dir.path)
                var p = dir.path
                for (seg in parts) {
                    p = "$p/$seg"
                    add(p)
                }
                add("$p/A")
            }
        assertEquals(expected, r.sortedPaths())
    }

    @Test
    fun siblings() {
        val dir = TestDir.tmp()
        dir.touch("bar/a")
        dir.touch("bar/b")
        dir.touch("foo/a")
        dir.touch("foo/b")

        val wd = WalkDir.new(dir.path)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val expected =
            listOf(
                dir.path,
                dir.join("bar"),
                dir.join("bar/a"),
                dir.join("bar/b"),
                dir.join("foo"),
                dir.join("foo/a"),
                dir.join("foo/b"),
            )
        assertEquals(expected, r.sortedPaths())
    }

    @Test
    fun symRootFileNofollow() {
        val dir = TestDir.tmp()
        dir.touch("a")
        dir.symlinkFile("a", "a-link")

        val wd = WalkDir.new(dir.join("a-link"))
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val ents = r.sortedEnts()
        assertEquals(1, ents.size)
        val link = ents[0]

        assertEquals(dir.join("a-link"), link.path())
        assertTrue(link.pathIsSymlink())
        assertEquals(dir.join("a"), dir.readLink(link.path()))
        assertEquals(0, link.depth())
        assertTrue(link.fileType().isSymlink)
        assertFalse(link.fileType().isFile)
        assertFalse(link.fileType().isDir)
        assertTrue(
            link
                .metadata(dir.sys)
                .getOrThrow()
                .fileType.isSymlink,
        )
        assertFalse(
            link
                .metadata(dir.sys)
                .getOrThrow()
                .fileType.isFile,
        )
        assertFalse(
            link
                .metadata(dir.sys)
                .getOrThrow()
                .fileType.isDir,
        )
    }

    @Test
    fun symRootFileFollow() {
        val dir = TestDir.tmp()
        dir.touch("a")
        dir.symlinkFile("a", "a-link")

        val wd = WalkDir.new(dir.join("a-link")).followLinks(true)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val ents = r.sortedEnts()
        val link = ents[0]

        assertEquals(dir.join("a-link"), link.path())
        assertTrue(link.pathIsSymlink())
        assertEquals(dir.join("a"), dir.readLink(link.path()))
        assertEquals(0, link.depth())
        assertFalse(link.fileType().isSymlink)
        assertTrue(link.fileType().isFile)
        assertFalse(link.fileType().isDir)
        assertFalse(
            link
                .metadata(dir.sys)
                .getOrThrow()
                .fileType.isSymlink,
        )
        assertTrue(
            link
                .metadata(dir.sys)
                .getOrThrow()
                .fileType.isFile,
        )
        assertFalse(
            link
                .metadata(dir.sys)
                .getOrThrow()
                .fileType.isDir,
        )
    }

    @Test
    fun brokenSymRootDirNofollowAndRootNofollow() {
        val dir = TestDir.tmp()
        dir.symlinkDir("broken", "a-link")

        val wd =
            WalkDir
                .new(dir.join("a-link"))
                .followLinks(false)
                .followRootLinks(false)
        val r = dir.runRecursive(wd)
        val ents = r.sortedEnts()
        assertEquals(1, ents.size)
        val link = ents[0]
        assertEquals(dir.join("a-link"), link.path())
        assertTrue(link.pathIsSymlink())
    }

    @Test
    fun brokenSymRootDirFollowAndRootNofollow() {
        val dir = TestDir.tmp()
        dir.symlinkDir("broken", "a-link")

        val wd =
            WalkDir
                .new(dir.join("a-link"))
                .followLinks(true)
                .followRootLinks(false)
        val r = dir.runRecursive(wd)
        assertTrue(r.sortedEnts().isEmpty())
        assertEquals(1, r.errs().size)
    }

    @Test
    fun brokenSymRootDirRootIsAlwaysFollowed() {
        val dir = TestDir.tmp()
        dir.symlinkDir("broken", "a-link")

        for (followSymlinks in listOf(true, false)) {
            val wd = WalkDir.new(dir.join("a-link")).followLinks(followSymlinks)
            val r = dir.runRecursive(wd)
            assertTrue(r.sortedEnts().isEmpty())
            assertEquals(1, r.errs().size)
        }
    }

    @Test
    fun symRootDirNofollowRootNofollow() {
        val dir = TestDir.tmp()
        dir.mkdirp("a")
        dir.symlinkDir("a", "a-link")
        dir.touch("a/zzz")

        val wd = WalkDir.new(dir.join("a-link")).followRootLinks(false)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val ents = r.sortedEnts()
        assertEquals(1, ents.size)
        val link = ents[0]
        assertEquals(dir.join("a-link"), link.path())
        assertEquals(0, link.depth())
    }

    @Test
    fun symRootDirNofollowRootFollow() {
        val dir = TestDir.tmp()
        dir.mkdirp("a")
        dir.symlinkDir("a", "a-link")
        dir.touch("a/zzz")

        val wd = WalkDir.new(dir.join("a-link"))
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val ents = r.sortedEnts()
        assertEquals(2, ents.size)
        val link = ents[0]

        assertEquals(dir.join("a-link"), link.path())
        assertTrue(link.pathIsSymlink())
        assertEquals(dir.join("a"), dir.readLink(link.path()))
        assertEquals(0, link.depth())
        assertTrue(link.fileType().isSymlink)
        assertFalse(link.fileType().isFile)
        assertFalse(link.fileType().isDir)

        val linkZzz = ents[1]
        assertEquals(dir.join("a-link") + "/zzz", linkZzz.path())
        assertFalse(linkZzz.pathIsSymlink())
    }

    @Test
    fun symRootDirFollow() {
        val dir = TestDir.tmp()
        dir.mkdirp("a")
        dir.symlinkDir("a", "a-link")
        dir.touch("a/zzz")

        val wd = WalkDir.new(dir.join("a-link")).followLinks(true)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val ents = r.sortedEnts()
        assertEquals(2, ents.size)
        val link = ents[0]

        assertEquals(dir.join("a-link"), link.path())
        assertTrue(link.pathIsSymlink())
        assertEquals(dir.join("a"), dir.readLink(link.path()))
        assertEquals(0, link.depth())
        assertFalse(link.fileType().isSymlink)
        assertFalse(link.fileType().isFile)
        assertTrue(link.fileType().isDir)

        val linkZzz = ents[1]
        assertEquals(dir.join("a-link") + "/zzz", linkZzz.path())
        assertFalse(linkZzz.pathIsSymlink())
    }

    @Test
    fun symFileNofollow() {
        val dir = TestDir.tmp()
        dir.touch("a")
        dir.symlinkFile("a", "a-link")

        val wd = WalkDir.new(dir.path)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val ents = r.sortedEnts()
        assertEquals(3, ents.size)
        val src = ents[1]
        val link = ents[2]

        assertEquals(dir.join("a"), src.path())
        assertEquals(dir.join("a-link"), link.path())
        assertFalse(src.pathIsSymlink())
        assertTrue(link.pathIsSymlink())
        assertEquals(dir.join("a"), dir.readLink(link.path()))
        assertEquals(1, src.depth())
        assertEquals(1, link.depth())
        assertTrue(src.fileType().isFile)
        assertTrue(link.fileType().isSymlink)
        assertFalse(link.fileType().isFile)
        assertFalse(link.fileType().isDir)
    }

    @Test
    fun symFileFollow() {
        val dir = TestDir.tmp()
        dir.touch("a")
        dir.symlinkFile("a", "a-link")

        val wd = WalkDir.new(dir.path).followLinks(true)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val ents = r.sortedEnts()
        assertEquals(3, ents.size)
        val src = ents[1]
        val link = ents[2]

        assertEquals(dir.join("a"), src.path())
        assertEquals(dir.join("a-link"), link.path())
        assertFalse(src.pathIsSymlink())
        assertTrue(link.pathIsSymlink())
        assertEquals(dir.join("a"), dir.readLink(link.path()))
        assertEquals(1, src.depth())
        assertEquals(1, link.depth())
        assertTrue(src.fileType().isFile)
        assertFalse(link.fileType().isSymlink)
        assertTrue(link.fileType().isFile)
        assertFalse(link.fileType().isDir)
    }

    @Test
    fun symDirNofollow() {
        val dir = TestDir.tmp()
        dir.mkdirp("a")
        dir.symlinkDir("a", "a-link")
        dir.touch("a/zzz")

        val wd = WalkDir.new(dir.path)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val ents = r.sortedEnts()
        assertEquals(4, ents.size)
        val src = ents[1]
        val link = ents[3]

        assertEquals(dir.join("a"), src.path())
        assertEquals(dir.join("a-link"), link.path())
        assertFalse(src.pathIsSymlink())
        assertTrue(link.pathIsSymlink())
        assertEquals(dir.join("a"), dir.readLink(link.path()))
        assertEquals(1, src.depth())
        assertEquals(1, link.depth())
        assertTrue(src.fileType().isDir)
        assertTrue(link.fileType().isSymlink)
    }

    @Test
    fun symDirFollow() {
        val dir = TestDir.tmp()
        dir.mkdirp("a")
        dir.symlinkDir("a", "a-link")
        dir.touch("a/zzz")

        val wd = WalkDir.new(dir.path).followLinks(true)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val ents = r.sortedEnts()
        assertEquals(5, ents.size)
        val src = ents[1]
        val link = ents[3]

        assertEquals(dir.join("a"), src.path())
        assertEquals(dir.join("a-link"), link.path())
        assertFalse(src.pathIsSymlink())
        assertTrue(link.pathIsSymlink())
        assertEquals(dir.join("a"), dir.readLink(link.path()))
        assertEquals(1, src.depth())
        assertEquals(1, link.depth())
        assertTrue(src.fileType().isDir)
        assertFalse(link.fileType().isSymlink)
        assertTrue(link.fileType().isDir)

        val srcZzz = ents[2]
        val linkZzz = ents[4]
        assertEquals(dir.join("a") + "/zzz", srcZzz.path())
        assertEquals(dir.join("a-link") + "/zzz", linkZzz.path())
        assertFalse(srcZzz.pathIsSymlink())
        assertFalse(linkZzz.pathIsSymlink())
    }

    @Test
    fun symNoloop() {
        val dir = TestDir.tmp()
        dir.mkdirp("a/b/c")
        dir.symlinkDir("a", "a/b/c/a-link")

        val wd = WalkDir.new(dir.path)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()
        assertEquals(5, r.ents().size)
    }

    @Test
    fun symLoopDetect() {
        val dir = TestDir.tmp()
        dir.mkdirp("a/b/c")
        dir.symlinkDir("a", "a/b/c/a-link")

        val wd = WalkDir.new(dir.path).followLinks(true)
        val r = dir.runRecursive(wd)

        val ents = r.sortedEnts()
        val errs = r.errs()
        assertEquals(4, ents.size)
        assertEquals(1, errs.size)

        val err = errs[0]
        assertEquals(dir.join("a/b/c/a-link"), err.path())
        assertEquals(dir.join("a"), err.loopAncestor())
        assertEquals(4, err.depth())
        assertNull(err.ioError())
    }

    @Test
    fun symSelfLoopNoError() {
        val dir = TestDir.tmp()
        dir.symlinkFile("a", "a")

        val wd = WalkDir.new(dir.path)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()
        assertEquals(2, r.ents().size)

        val ent = r.ents()[1]
        assertEquals(dir.join("a"), ent.path())
        assertTrue(ent.pathIsSymlink())
        assertTrue(ent.fileType().isSymlink)
        assertFalse(ent.fileType().isFile)
        assertFalse(ent.fileType().isDir)
    }

    @Test
    fun symFileSelfLoopIoError() {
        val dir = TestDir.tmp()
        dir.symlinkFile("a", "a")

        val wd = WalkDir.new(dir.path).followLinks(true)
        val r = dir.runRecursive(wd)

        val ents = r.sortedEnts()
        val errs = r.errs()
        assertEquals(1, ents.size)
        assertEquals(1, errs.size)

        val err = errs[0]
        assertEquals(dir.join("a"), err.path())
        assertEquals(1, err.depth())
        assertNull(err.loopAncestor())
        assertNotNull(err.ioError())
    }

    @Test
    fun symDirSelfLoopIoError() {
        val dir = TestDir.tmp()
        dir.symlinkDir("a", "a")

        val wd = WalkDir.new(dir.path).followLinks(true)
        val r = dir.runRecursive(wd)

        val ents = r.sortedEnts()
        val errs = r.errs()
        assertEquals(1, ents.size)
        assertEquals(1, errs.size)

        val err = errs[0]
        assertEquals(dir.join("a"), err.path())
        assertEquals(1, err.depth())
        assertNull(err.loopAncestor())
        assertNotNull(err.ioError())
    }

    @Test
    fun minDepth1() {
        val dir = TestDir.tmp()
        dir.mkdirp("a/b")

        val wd = WalkDir.new(dir.path).minDepth(1)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val expected = listOf(dir.join("a"), dir.join("a/b"))
        assertEquals(expected, r.sortedPaths())
    }

    @Test
    fun minDepth2() {
        val dir = TestDir.tmp()
        dir.mkdirp("a/b")

        val wd = WalkDir.new(dir.path).minDepth(2)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val expected = listOf(dir.join("a/b"))
        assertEquals(expected, r.sortedPaths())
    }

    @Test
    fun maxDepth0() {
        val dir = TestDir.tmp()
        dir.mkdirp("a/b")

        val wd = WalkDir.new(dir.path).maxDepth(0)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val expected = listOf(dir.path)
        assertEquals(expected, r.sortedPaths())
    }

    @Test
    fun maxDepth1() {
        val dir = TestDir.tmp()
        dir.mkdirp("a/b")

        val wd = WalkDir.new(dir.path).maxDepth(1)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val expected = listOf(dir.path, dir.join("a"))
        assertEquals(expected, r.sortedPaths())
    }

    @Test
    fun maxDepth2() {
        val dir = TestDir.tmp()
        dir.mkdirp("a/b")

        val wd = WalkDir.new(dir.path).maxDepth(2)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val expected = listOf(dir.path, dir.join("a"), dir.join("a/b"))
        assertEquals(expected, r.sortedPaths())
    }

    @Test
    fun minMaxDepthDiffNada() {
        val dir = TestDir.tmp()
        dir.mkdirp("a/b/c")

        val wd = WalkDir.new(dir.path).minDepth(3).maxDepth(2)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val expected = listOf(dir.join("a/b/c"))
        assertEquals(expected, r.sortedPaths())
    }

    @Test
    fun minMaxDepthDiff0() {
        val dir = TestDir.tmp()
        dir.mkdirp("a/b/c")

        val wd = WalkDir.new(dir.path).minDepth(2).maxDepth(2)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val expected = listOf(dir.join("a/b"))
        assertEquals(expected, r.sortedPaths())
    }

    @Test
    fun minMaxDepthDiff1() {
        val dir = TestDir.tmp()
        dir.mkdirp("a/b/c")

        val wd = WalkDir.new(dir.path).minDepth(1).maxDepth(2)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val expected = listOf(dir.join("a"), dir.join("a/b"))
        assertEquals(expected, r.sortedPaths())
    }

    @Test
    fun contentsFirst() {
        val dir = TestDir.tmp()
        dir.touch("a")

        val wd = WalkDir.new(dir.path).contentsFirst(true)
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val expected = listOf(dir.join("a"), dir.path)
        assertEquals(expected, r.paths())
    }

    @Test
    fun skipCurrentDir() {
        val dir = TestDir.tmp()
        dir.mkdirp("foo/bar/baz")
        dir.mkdirp("quux")

        val paths = mutableListOf<String>()
        val it = WalkDir.new(dir.path).intoIter(dir.sys)
        while (it.hasNext()) {
            val ent = it.next().getOrThrow()
            paths.add(ent.path())
            if (ent.fileName() == "bar") {
                it.skipCurrentDir()
            }
        }
        paths.sort()

        val expected =
            listOf(
                dir.path,
                dir.join("foo"),
                dir.join("foo/bar"),
                dir.join("quux"),
            ).sorted()
        assertEquals(expected, paths)
    }

    @Test
    fun filterEntry() {
        val dir = TestDir.tmp()
        dir.mkdirp("foo/bar/baz/abc")
        dir.mkdirp("quux")

        val wd =
            WalkDir
                .new(dir.path)
                .intoIter(dir.sys)
                .filterEntry { ent -> ent.fileName() != "baz" }
        val r = dir.runRecursive(WalkDir.new(dir.path))
        val seen = mutableListOf<String>()
        val it = wd
        while (it.hasNext()) {
            seen.add(it.next().getOrThrow().path())
        }
        seen.sort()

        val expected =
            listOf(
                dir.path,
                dir.join("foo"),
                dir.join("foo/bar"),
                dir.join("quux"),
            ).sorted()
        assertEquals(expected, seen)
    }

    @Test
    fun sortBy() {
        val dir = TestDir.tmp()
        dir.mkdirp("foo/bar/baz/abc")
        dir.mkdirp("quux")

        val wd =
            WalkDir
                .new(dir.path)
                .sortBy { a, b -> b.fileName().compareTo(a.fileName()) }
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val expected =
            listOf(
                dir.path,
                dir.join("quux"),
                dir.join("foo"),
                dir.join("foo/bar"),
                dir.join("foo/bar/baz"),
                dir.join("foo/bar/baz/abc"),
            )
        assertEquals(expected, r.paths())
    }

    @Test
    fun sortByKey() {
        val dir = TestDir.tmp()
        dir.mkdirp("foo/bar/baz/abc")
        dir.mkdirp("quux")

        val wd = WalkDir.new(dir.path).sortByKey { it.fileName() }
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val expected =
            listOf(
                dir.path,
                dir.join("foo"),
                dir.join("foo/bar"),
                dir.join("foo/bar/baz"),
                dir.join("foo/bar/baz/abc"),
                dir.join("quux"),
            )
        assertEquals(expected, r.paths())
    }

    @Test
    fun sortByFileName() {
        val dir = TestDir.tmp()
        dir.mkdirp("foo/bar/baz/abc")
        dir.mkdirp("quux")

        val wd = WalkDir.new(dir.path).sortByFileName()
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val expected =
            listOf(
                dir.path,
                dir.join("foo"),
                dir.join("foo/bar"),
                dir.join("foo/bar/baz"),
                dir.join("foo/bar/baz/abc"),
                dir.join("quux"),
            )
        assertEquals(expected, r.paths())
    }

    @Test
    fun sortMaxOpen() {
        val dir = TestDir.tmp()
        dir.mkdirp("foo/bar/baz/abc")
        dir.mkdirp("quux")

        val wd =
            WalkDir
                .new(dir.path)
                .maxOpen(1)
                .sortBy { a, b -> b.fileName().compareTo(a.fileName()) }
        val r = dir.runRecursive(wd)
        r.assertNoErrors()

        val expected =
            listOf(
                dir.path,
                dir.join("quux"),
                dir.join("foo"),
                dir.join("foo/bar"),
                dir.join("foo/bar/baz"),
                dir.join("foo/bar/baz/abc"),
            )
        assertEquals(expected, r.paths())
    }

    @Test
    fun sameFileSystem() {
        val dir = TestDir.tmp()
        dir.touch("a")
        dir.sys.mkdirp("/sys")
        dir.sys.devices["/sys"] = 99UL
        dir.symlinkDir("/sys", "sys-link")

        val wd1 = WalkDir.new(dir.path)
        val r1 = dir.runRecursive(wd1)
        r1.assertNoErrors()
        assertEquals(listOf(dir.path, dir.join("a"), dir.join("sys-link")), r1.sortedPaths())

        val wd2 = WalkDir.new(dir.path).sameFileSystem(true).followLinks(true)
        val r2 = dir.runRecursive(wd2)
        r2.assertNoErrors()
        assertEquals(listOf(dir.path, dir.join("a"), dir.join("sys-link")), r2.sortedPaths())
    }

    @Test
    fun regressionSkipCurrentDir() {
        val dir = TestDir.tmp()
        dir.mkdirp("foo/a/b")
        dir.mkdirp("foo/1/2")

        val wd = WalkDir.new(dir.path).maxOpen(1).intoIter(dir.sys)
        assertTrue(wd.hasNext())
        wd.next()
        assertTrue(wd.hasNext())
        wd.next()
        assertTrue(wd.hasNext())
        wd.next()
        assertTrue(wd.hasNext())
        wd.next()

        wd.skipCurrentDir()
        wd.skipCurrentDir()
        if (wd.hasNext()) {
            wd.next()
        }
    }
}

private fun Map<String, Pair<FileType, List<String>>>.toInMemory(): Sys = InMemorySys(this)
