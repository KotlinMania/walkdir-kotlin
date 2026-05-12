// port-lint: source src/lib.rs
package io.github.kotlinmania.walkdir

import kotlin.math.min

/**
 * An iterator for recursively descending into a directory.
 *
 * A value with this type must be constructed with the [WalkDir] type, which
 * uses a builder pattern to set options such as min/max depth, max open file
 * descriptors and whether the iterator should follow symbolic links. After
 * constructing a [WalkDir], call [WalkDir.intoIter] at the end of the chain.
 *
 * The order of elements yielded by this iterator is unspecified.
 *
 * Upstream Rust implements `iter::FusedIterator` for this type, which is a
 * marker trait promising that once `next` returns `None` it will keep returning
 * `None`. Kotlin iterators don't have a corresponding marker, but the same
 * contract holds: after [hasNext] returns `false` it stays false.
 */
class IntoIter internal constructor(
    /** Options specified in the builder. Depths, max fds, etc. */
    private val opts: WalkDirOptions,
    /** The filesystem seam these iterations call through. */
    private val sys: Sys,
    /**
     * The start path.
     *
     * This is only non-null at the beginning. After the first iteration, this
     * is always `null`.
     */
    private var start: String?,
) : Iterator<Result<DirEntry>> {

    /**
     * A stack of open (up to max fd) or closed handles to directories. An
     * open handle is a plain [Sys.readDir] iterator while a closed handle is
     * a `List<Result<DirEntry>>` corresponding to the as-of-yet consumed
     * entries.
     */
    private val stackList: MutableList<DirList> = mutableListOf()

    /**
     * A stack of file paths.
     *
     * This is *only* used when [WalkDir.followLinks] is enabled. In all other
     * cases this stack is empty.
     */
    private val stackPath: MutableList<Ancestor> = mutableListOf()

    /**
     * An index into [stackList] that points to the oldest open directory
     * handle. If the maximum fd limit is reached and a new directory needs to
     * be read, the handle at this index is closed before the new directory is
     * opened.
     */
    private var oldestOpened: Int = 0

    /**
     * The current depth of iteration (the length of the stack at the
     * beginning of each iteration).
     */
    private var depth: Int = 0

    /**
     * A list of DirEntries corresponding to directories, that are yielded
     * after their contents has been fully yielded. This is only used when
     * [WalkDirOptions.contentsFirst] is enabled.
     */
    private val deferredDirs: MutableList<DirEntry> = mutableListOf()

    /**
     * The device of the root file path when the first call to [next] was
     * made.
     *
     * If the [WalkDirOptions.sameFileSystem] option isn't enabled, then this
     * is always `null`. Conversely, if it is enabled, this is always non-null
     * after handling the root path.
     */
    private var rootDevice: ULong? = null

    /**
     * Buffer holding the next computed value if one is available. The Kotlin
     * [Iterator] interface splits the Rust `Option<Result<DirEntry>>` shape
     * into `hasNext` + `next`, so we pre-compute on demand and stash the
     * result here.
     */
    private var pending: Result<DirEntry>? = null
    private var exhausted: Boolean = false

    override fun hasNext(): Boolean {
        if (pending != null) return true
        if (exhausted) return false
        val next = advance()
        if (next == null) {
            exhausted = true
            return false
        }
        pending = next
        return true
    }

    override fun next(): Result<DirEntry> {
        if (!hasNext()) throw NoSuchElementException()
        val out = pending!!
        pending = null
        return out
    }

    /**
     * Computes the next entry, mirroring upstream `Iterator::next`.
     *
     * Returns `null` when iteration is exhausted (corresponds to upstream's
     * `None`).
     */
    private fun advance(): Result<DirEntry>? {
        val s = start
        if (s != null) {
            start = null
            if (opts.sameFileSystem) {
                val devRes = deviceNum(sys, s).fold(
                    onSuccess = { Result.success(it) },
                    onFailure = { err ->
                        val io = err as? IoError
                            ?: IoError(IoErrorKind.OTHER, err.message ?: "", err)
                        Result.failure(Error.fromPath(0, s, io))
                    },
                )
                val dev = devRes.getOrElse { return Result.failure(it) }
                rootDevice = dev
            }
            val dentRes = DirEntry.fromPath(sys, 0, s, false)
            val dent = dentRes.getOrElse { return Result.failure(it) }
            val handled = handleEntry(dent)
            if (handled != null) return handled
        }
        while (stackList.isNotEmpty()) {
            depth = stackList.size
            val deferred = getDeferredDir()
            if (deferred != null) return Result.success(deferred)
            if (depth > opts.maxDepth) {
                // If we've exceeded the max depth, pop the current dir so that
                // we don't descend.
                pop()
                continue
            }
            val top = stackList.last()
            if (!top.hasNext()) {
                pop()
                continue
            }
            val nextEntry = top.next()
            val failure = nextEntry.exceptionOrNull()
            if (failure != null) {
                return Result.failure(failure)
            }
            val dent = nextEntry.getOrThrow()
            val handled = handleEntry(dent)
            if (handled != null) return handled
        }
        if (opts.contentsFirst) {
            depth = stackList.size
            val deferred = getDeferredDir()
            if (deferred != null) return Result.success(deferred)
        }
        return null
    }

    /**
     * Skips the current directory.
     *
     * This causes the iterator to stop traversing the contents of the least
     * recently yielded directory. This means any remaining entries in that
     * directory will be skipped (including sub-directories).
     *
     * Note that the ergonomics of this method are questionable since it
     * borrows the iterator mutably. Namely, you must write out the looping
     * condition manually. For example, to skip hidden entries efficiently on
     * unix systems:
     *
     * ```kotlin
     * fun isHidden(entry: DirEntry): Boolean =
     *     entry.fileName().startsWith(".")
     *
     * val it = WalkDir.new("foo").intoIter(sys)
     * while (it.hasNext()) {
     *     val entry = it.next().getOrThrow()
     *     if (isHidden(entry)) {
     *         if (entry.fileType().isDir) it.skipCurrentDir()
     *         continue
     *     }
     *     println(entry.path())
     * }
     * ```
     *
     * You may find it more convenient to use the [filterEntry] iterator
     * adapter. (See its documentation for the same example functionality as
     * above.)
     */
    fun skipCurrentDir() {
        if (stackList.isNotEmpty()) {
            pop()
        }
    }

    /**
     * Yields only entries which satisfy the given predicate and skips
     * descending into directories that do not satisfy the given predicate.
     *
     * The predicate is applied to all entries. If the predicate is true,
     * iteration carries on as normal. If the predicate is false, the entry is
     * ignored and if it is a directory, it is not descended into.
     *
     * This is often more convenient to use than [skipCurrentDir]. For
     * example, to skip hidden files and directories efficiently on unix
     * systems:
     *
     * ```kotlin
     * fun isHidden(entry: DirEntry): Boolean =
     *     entry.fileName().startsWith(".")
     *
     * for (entry in WalkDir.new("foo").intoIter(sys).filterEntry { !isHidden(it) }) {
     *     println(entry.getOrThrow().path())
     * }
     * ```
     *
     * Note that the iterator will still yield errors for reading entries that
     * may not satisfy the predicate.
     *
     * Note that entries skipped with [WalkDir.minDepth] and [WalkDir.maxDepth]
     * are not passed to this predicate.
     *
     * Note that if the iterator has [WalkDir.contentsFirst] enabled, then
     * this method is no different than calling the standard
     * `Sequence.filter` method (because directory entries are yielded after
     * they've been descended into).
     */
    fun filterEntry(predicate: (DirEntry) -> Boolean): FilterEntry =
        FilterEntry(this, predicate)

    private fun handleEntry(initial: DirEntry): Result<DirEntry>? {
        var dent = initial
        if (opts.followLinks && dent.fileType().isSymlink) {
            val followed = follow(dent).getOrElse { return Result.failure(it) }
            dent = followed
        }
        val isNormalDir = !dent.fileType().isSymlink && dent.isDir()
        if (isNormalDir) {
            if (opts.sameFileSystem && dent.depth() > 0) {
                val same = isSameFileSystem(dent).getOrElse { return Result.failure(it) }
                if (same) {
                    push(dent).getOrElse { return Result.failure(it) }
                }
            } else {
                push(dent).getOrElse { return Result.failure(it) }
            }
        } else if (dent.depth() == 0 &&
            dent.fileType().isSymlink &&
            opts.followRootLinks
        ) {
            // As a special case, if we are processing a root entry, then we
            // always follow it even if it's a symlink and followLinks is
            // false. We are careful to not let this change the semantics of
            // the DirEntry however. Namely, the DirEntry should still respect
            // the followLinks setting. When it's disabled, it should report
            // itself as a symlink. When it's enabled, it should always report
            // itself as the target.
            val mdRes = sys.metadata(dent.path())
            val md = mdRes.getOrElse { err ->
                val io = err as? IoError
                    ?: IoError(IoErrorKind.OTHER, err.message ?: "", err)
                return Result.failure(Error.fromPath(dent.depth(), dent.path(), io))
            }
            if (md.fileType.isDir) {
                push(dent).getOrElse { return Result.failure(it) }
            }
        }
        return if (isNormalDir && opts.contentsFirst) {
            deferredDirs.add(dent)
            null
        } else if (skippable()) {
            null
        } else {
            Result.success(dent)
        }
    }

    private fun getDeferredDir(): DirEntry? {
        if (opts.contentsFirst) {
            if (depth < deferredDirs.size) {
                val deferred = deferredDirs.removeAt(deferredDirs.size - 1)
                if (!skippable()) {
                    return deferred
                }
            }
        }
        return null
    }

    private fun push(dent: DirEntry): Result<Unit> {
        // Make room for another open file descriptor if we've hit the max.
        val free = stackList.size - oldestOpened
        if (free == opts.maxOpen) {
            stackList[oldestOpened] = stackList[oldestOpened].close()
        }
        // Open a handle to reading the directory's entries.
        val rdRes = sys.readDir(dent.path())
        var list: DirList = rdRes.fold(
            onSuccess = { iter ->
                DirList.Opened(depth = depth, pendingError = null, it = iter)
            },
            onFailure = { err ->
                val io = err as? IoError
                    ?: IoError(IoErrorKind.OTHER, err.message ?: "", err)
                DirList.Opened(
                    depth = depth,
                    pendingError = Error.fromPath(depth, dent.path(), io),
                    it = null,
                )
            },
        )
        val sorter = opts.sorter
        if (sorter != null) {
            val entries = ArrayList<Result<DirEntry>>()
            while (list.hasNext()) entries.add(list.next())
            entries.sortWith(Comparator { a, b ->
                val aOk = a.getOrNull()
                val bOk = b.getOrNull()
                when {
                    aOk != null && bOk != null -> sorter.compare(aOk, bOk)
                    aOk == null && bOk == null -> 0
                    aOk != null && bOk == null -> 1
                    else -> -1
                }
            })
            list = DirList.Closed(entries)
        }
        if (opts.followLinks) {
            val ancestor = Ancestor.new(sys, dent).getOrElse { err ->
                val io = err as? IoError
                    ?: IoError(IoErrorKind.OTHER, err.message ?: "", err)
                return Result.failure(Error.fromIo(depth, io))
            }
            stackPath.add(ancestor)
        }
        // We push this after stackPath since creating the Ancestor can fail.
        // If it fails, then we return the error and won't descend.
        stackList.add(list)
        // If we had to close out a previous directory stream, then we need to
        // increment our index to the oldest still-open stream. We do this
        // only after adding to our stack, in order to ensure that the
        // oldestOpened index remains valid. The worst that can happen is that
        // an already closed stream will be closed again, which is a no-op.
        if (free == opts.maxOpen) {
            oldestOpened += 1
        }
        return Result.success(Unit)
    }

    private fun pop() {
        check(stackList.isNotEmpty()) { "BUG: cannot pop from empty stack" }
        stackList.removeAt(stackList.size - 1)
        if (opts.followLinks) {
            check(stackPath.isNotEmpty()) { "BUG: list/path stacks out of sync" }
            stackPath.removeAt(stackPath.size - 1)
        }
        // If everything in the stack is already closed, then there is room
        // for at least one more open descriptor and it will always be at the
        // top of the stack.
        oldestOpened = min(oldestOpened, stackList.size)
    }

    private fun follow(initial: DirEntry): Result<DirEntry> {
        var dent = DirEntry.fromPath(sys, depth, initial.path(), true)
            .getOrElse { return Result.failure(it) }
        // The only way a symlink can cause a loop is if it points to a
        // directory. Otherwise, it always points to a leaf and we can omit
        // any loop checks.
        if (dent.isDir()) {
            checkLoop(dent.path()).getOrElse { return Result.failure(it) }
        }
        return Result.success(dent)
    }

    private fun checkLoop(child: String): Result<Unit> {
        val hchildRes = sys.handleFromPath(child)
        val hchild = hchildRes.getOrElse { err ->
            val io = err as? IoError
                ?: IoError(IoErrorKind.OTHER, err.message ?: "", err)
            return Result.failure(Error.fromIo(depth, io))
        }
        for (ancestor in stackPath.asReversed()) {
            val isSame = ancestor.isSame(hchild).getOrElse { err ->
                val io = err as? IoError
                    ?: IoError(IoErrorKind.OTHER, err.message ?: "", err)
                return Result.failure(Error.fromIo(depth, io))
            }
            if (isSame) {
                return Result.failure(Error.fromLoop(depth, ancestor.path, child))
            }
        }
        return Result.success(Unit)
    }

    private fun isSameFileSystem(dent: DirEntry): Result<Boolean> {
        val dentDeviceRes = sys.deviceNum(dent.path())
        val dentDevice = dentDeviceRes.getOrElse { err ->
            val io = err as? IoError
                ?: IoError(IoErrorKind.OTHER, err.message ?: "", err)
            return Result.failure(Error.fromEntry(dent, io))
        }
        val rd = rootDevice ?: error("BUG: called isSameFileSystem without root device")
        return Result.success(rd == dentDevice)
    }

    private fun skippable(): Boolean =
        depth < opts.minDepth || depth > opts.maxDepth
}

