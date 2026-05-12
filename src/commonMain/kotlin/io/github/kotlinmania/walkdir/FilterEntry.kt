// port-lint: source src/lib.rs
package io.github.kotlinmania.walkdir

/**
 * A recursive directory iterator that skips entries.
 *
 * Values of this type are created by calling [IntoIter.filterEntry] on an
 * [IntoIter], which is formed by calling [WalkDir.intoIter] on a [WalkDir].
 *
 * Directories that fail the predicate are skipped. Namely, they are never
 * yielded and never descended into.
 *
 * Entries that are skipped with the [WalkDir.minDepth] and [WalkDir.maxDepth]
 * options are not passed through this filter.
 *
 * If opening a handle to a directory resulted in an error, then it is yielded
 * and no corresponding call to the predicate is made.
 *
 * Upstream Rust generalises this over the underlying iterator type `I` and the
 * predicate type `P`. The Kotlin port specialises to [IntoIter] because the
 * only callers and the only `impl Iterator for FilterEntry<...>` block in
 * upstream both pin `I = IntoIter`.
 *
 * Upstream also implements `iter::FusedIterator` for this type; Kotlin
 * iterators have no equivalent marker, but the same contract holds.
 */
class FilterEntry internal constructor(
    private val it: IntoIter,
    private val predicate: (DirEntry) -> Boolean,
) : Iterator<Result<DirEntry>> {

    private var pending: Result<DirEntry>? = null
    private var exhausted: Boolean = false

    /**
     * Advances the iterator and returns the next value.
     *
     * # Errors
     *
     * If the iterator fails to retrieve the next value, this method returns
     * an error value. The error will be wrapped in a [Result.failure].
     */
    override fun hasNext(): Boolean {
        if (pending != null) return true
        if (exhausted) return false
        while (it.hasNext()) {
            val nextResult = it.next()
            val failure = nextResult.exceptionOrNull()
            if (failure != null) {
                pending = Result.failure(failure)
                return true
            }
            val dent = nextResult.getOrThrow()
            if (!predicate(dent)) {
                if (dent.isDirPublic()) {
                    it.skipCurrentDir()
                }
                continue
            }
            pending = Result.success(dent)
            return true
        }
        exhausted = true
        return false
    }

    override fun next(): Result<DirEntry> {
        if (!hasNext()) throw NoSuchElementException()
        val out = pending!!
        pending = null
        return out
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
        FilterEntry(it, predicate)

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
        it.skipCurrentDir()
    }
}

/**
 * Public alias for [DirEntry.fileType]`.isDir`, used by [FilterEntry] to
 * decide whether to call [IntoIter.skipCurrentDir] on a rejected entry.
 * Mirrors upstream's `DirEntry::is_dir`, which is `pub(crate)` and therefore
 * accessible from `filter_entry`'s body.
 */
private fun DirEntry.isDirPublic(): Boolean = fileType().isDir
