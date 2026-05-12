// port-lint: source src/lib.rs
package io.github.kotlinmania.walkdir

/**
 * Crate `walkdir` provides an efficient and cross platform implementation of
 * recursive directory traversal. Several options are exposed to control
 * iteration, such as whether to follow symbolic links (default off), limit the
 * maximum number of simultaneous open file descriptors and the ability to
 * efficiently skip descending into directories.
 *
 * # From the top
 *
 * The [WalkDir] type builds iterators. The [DirEntry] type describes values
 * yielded by the iterator. Finally, the [Error] type is a small wrapper around
 * [IoError] with additional information, such as if a loop was detected while
 * following symbolic links (not enabled by default).
 *
 * # Example
 *
 * The following code recursively iterates over the directory given and prints
 * the path for each entry:
 *
 * ```kotlin
 * for (entry in WalkDir.new("foo").intoIter(sys)) {
 *     println(entry.getOrThrow().path())
 * }
 * ```
 *
 * Or, if you'd like to iterate over all entries and ignore any errors that may
 * arise, filter the iterator. (e.g., This code below will silently skip
 * directories that the owner of the running process does not have permission
 * to access.)
 *
 * ```kotlin
 * for (entry in WalkDir.new("foo").intoIter(sys).asSequence().mapNotNull { it.getOrNull() }) {
 *     println(entry.path())
 * }
 * ```
 *
 * # Example: follow symbolic links
 *
 * The same code as above, except [followLinks] is enabled:
 *
 * ```kotlin
 * for (entry in WalkDir.new("foo").followLinks(true).intoIter(sys)) {
 *     println(entry.getOrThrow().path())
 * }
 * ```
 *
 * # Example: skip hidden files and directories on unix
 *
 * This uses the [IntoIter.filterEntry] iterator adapter to avoid yielding
 * hidden files and directories efficiently (i.e. without recursing into
 * hidden directories):
 *
 * ```kotlin
 * fun isHidden(entry: DirEntry): Boolean =
 *     entry.fileName().startsWith(".")
 *
 * val walker = WalkDir.new("foo").intoIter(sys)
 * for (entry in walker.filterEntry { !isHidden(it) }) {
 *     println(entry.getOrThrow().path())
 * }
 * ```
 */

/**
 * A builder to create an iterator for recursively walking a directory.
 *
 * Results are returned in depth first fashion, with directories yielded before
 * their contents. If [contentsFirst] is true, contents are yielded before
 * their directories. The order is unspecified but if [sortBy] is given,
 * directory entries are sorted according to this function. Directory entries
 * `.` and `..` are always omitted.
 *
 * If an error occurs at any point during iteration, then it is returned in
 * place of its corresponding directory entry and iteration continues as
 * normal. If an error occurs while opening a directory for reading, then it is
 * not descended into (but the error is still yielded by the iterator).
 * Iteration may be stopped at any time. When the iterator is destroyed, all
 * resources associated with it are freed.
 *
 * # Usage
 *
 * Idiomatic use of this type should use method chaining to set desired
 * options. For example, this only shows entries with a depth of `1`, `2` or
 * `3` (relative to `foo`):
 *
 * ```kotlin
 * for (entry in WalkDir.new("foo").minDepth(1).maxDepth(3).intoIter(sys)) {
 *     println(entry.getOrThrow().path())
 * }
 * ```
 *
 * Note that the iterator by default includes the top-most directory. Since
 * this is the only directory yielded with depth `0`, it is easy to ignore it
 * with the [minDepth] setting:
 *
 * ```kotlin
 * for (entry in WalkDir.new("foo").minDepth(1).intoIter(sys)) {
 *     println(entry.getOrThrow().path())
 * }
 * ```
 *
 * This will only return descendents of the `foo` directory and not `foo`
 * itself.
 *
 * # Loops
 *
 * This iterator (like most/all recursive directory iterators) assumes that no
 * loops can be made with *hard* links on your file system. In particular,
 * this would require creating a hard link to a directory such that it creates
 * a loop. On most platforms, this operation is illegal.
 *
 * Note that when following symbolic/soft links, loops are detected and an
 * error is reported.
 */
class WalkDir private constructor(
    internal val opts: WalkDirOptions,
    internal val root: String,
) {
    /**
     * Set the minimum depth of entries yielded by the iterator.
     *
     * The smallest depth is `0` and always corresponds to the path given to
     * the [new] function on this type. Its direct descendents have depth `1`,
     * and their descendents have depth `2`, and so on.
     */
    fun minDepth(depth: Int): WalkDir {
        opts.minDepth = depth
        if (opts.minDepth > opts.maxDepth) {
            opts.minDepth = opts.maxDepth
        }
        return this
    }

    /**
     * Set the maximum depth of entries yield by the iterator.
     *
     * The smallest depth is `0` and always corresponds to the path given to
     * the [new] function on this type. Its direct descendents have depth `1`,
     * and their descendents have depth `2`, and so on.
     *
     * Note that this will not simply filter the entries of the iterator, but
     * it will actually avoid descending into directories when the depth is
     * exceeded.
     */
    fun maxDepth(depth: Int): WalkDir {
        opts.maxDepth = depth
        if (opts.maxDepth < opts.minDepth) {
            opts.maxDepth = opts.minDepth
        }
        return this
    }

    /**
     * Follow symbolic links. By default, this is disabled.
     *
     * When `yes` is `true`, symbolic links are followed as if they were normal
     * directories and files. If a symbolic link is broken or is involved in a
     * loop, an error is yielded.
     *
     * When enabled, the yielded [DirEntry] values represent the target of the
     * link while the path corresponds to the link. See the [DirEntry] type for
     * more details.
     */
    fun followLinks(yes: Boolean): WalkDir {
        opts.followLinks = yes
        return this
    }

    /**
     * Follow symbolic links if these are the root of the traversal. By
     * default, this is enabled.
     *
     * When `yes` is `true`, symbolic links on root paths are followed which is
     * effective if the symbolic link points to a directory. If a symbolic
     * link is broken or is involved in a loop, an error is yielded as the
     * first entry of the traversal.
     *
     * When enabled, the yielded [DirEntry] values represent the target of the
     * link while the path corresponds to the link. See the [DirEntry] type for
     * more details, and all future entries will be contained within the
     * resolved directory behind the symbolic link of the root path.
     */
    fun followRootLinks(yes: Boolean): WalkDir {
        opts.followRootLinks = yes
        return this
    }

    /**
     * Set the maximum number of simultaneously open file descriptors used by
     * the iterator.
     *
     * `n` must be greater than or equal to `1`. If `n` is `0`, then it is set
     * to `1` automatically. If this is not set, then it defaults to some
     * reasonably low number.
     *
     * This setting has no impact on the results yielded by the iterator (even
     * when `n` is `1`). Instead, this setting represents a trade off between
     * scarce resources (file descriptors) and memory. Namely, when the
     * maximum number of file descriptors is reached and a new directory needs
     * to be opened to continue iteration, then a previous directory handle is
     * closed and has its unyielded entries stored in memory. In practice,
     * this is a satisfying trade off because it scales with respect to the
     * *depth* of your file tree. Therefore, low values (even `1`) are
     * acceptable.
     *
     * Note that this value does not impact the number of system calls made by
     * an exhausted iterator.
     *
     * # Platform behavior
     *
     * On Windows, if [followLinks] is enabled, then this limit is not
     * respected. In particular, the maximum number of file descriptors opened
     * is proportional to the depth of the directory tree traversed.
     */
    fun maxOpen(n: Int): WalkDir {
        var fixed = n
        if (fixed == 0) {
            fixed = 1
        }
        opts.maxOpen = fixed
        return this
    }

    /**
     * Set a function for sorting directory entries with a comparator function.
     *
     * If a compare function is set, the resulting iterator will return all
     * paths in sorted order. The compare function will be called to compare
     * entries from the same directory.
     *
     * ```kotlin
     * WalkDir.new("foo").sortBy { a, b -> a.fileName().compareTo(b.fileName()) }
     * ```
     */
    fun sortBy(cmp: Comparator<DirEntry>): WalkDir {
        opts.sorter = cmp
        return this
    }

    /**
     * Set a function for sorting directory entries with a key extraction
     * function.
     *
     * If a compare function is set, the resulting iterator will return all
     * paths in sorted order. The compare function will be called to compare
     * entries from the same directory.
     *
     * ```kotlin
     * WalkDir.new("foo").sortByKey<String> { it.fileName() }
     * ```
     */
    fun <K : Comparable<K>> sortByKey(extract: (DirEntry) -> K): WalkDir =
        sortBy(Comparator { a, b -> extract(a).compareTo(extract(b)) })

    /**
     * Sort directory entries by file name, to ensure a deterministic order.
     *
     * This is a convenience function for calling [sortBy].
     *
     * ```kotlin
     * WalkDir.new("foo").sortByFileName()
     * ```
     */
    fun sortByFileName(): WalkDir =
        sortBy(Comparator { a, b -> a.fileName().compareTo(b.fileName()) })

    /**
     * Yield a directory's contents before the directory itself. By default,
     * this is disabled.
     *
     * When `yes` is `false` (as is the default), the directory is yielded
     * before its contents are read. This is useful when, e.g. you want to
     * skip processing of some directories.
     *
     * When `yes` is `true`, the iterator yields the contents of a directory
     * before yielding the directory itself. This is useful when, e.g. you
     * want to recursively delete a directory.
     *
     * # Example
     *
     * Assume the following directory tree:
     *
     * ```text
     * foo/
     *   abc/
     *     qrs
     *     tuv
     *   def/
     * ```
     *
     * With contentsFirst disabled (the default), the following code visits
     * the directory tree in depth-first order:
     *
     * ```kotlin
     * for (entry in WalkDir.new("foo").intoIter(sys)) {
     *     val e = entry.getOrThrow()
     *     println(e.path())
     * }
     *
     * // foo
     * // foo/abc
     * // foo/abc/qrs
     * // foo/abc/tuv
     * // foo/def
     * ```
     *
     * With contentsFirst enabled:
     *
     * ```kotlin
     * for (entry in WalkDir.new("foo").contentsFirst(true).intoIter(sys)) {
     *     val e = entry.getOrThrow()
     *     println(e.path())
     * }
     *
     * // foo/abc/qrs
     * // foo/abc/tuv
     * // foo/abc
     * // foo/def
     * // foo
     * ```
     */
    fun contentsFirst(yes: Boolean): WalkDir {
        opts.contentsFirst = yes
        return this
    }

    /**
     * Do not cross file system boundaries.
     *
     * When this option is enabled, directory traversal will not descend into
     * directories that are on a different file system from the root path.
     *
     * Currently, this option is only supported on Unix and Windows. If this
     * option is used on an unsupported platform, then directory traversal
     * will immediately return an error and will not yield any entries.
     */
    fun sameFileSystem(yes: Boolean): WalkDir {
        opts.sameFileSystem = yes
        return this
    }

    /**
     * Constructs the iterator for this builder.
     *
     * Mirrors `impl IntoIterator for WalkDir`. The Kotlin port takes the
     * filesystem [sys] here rather than threading it through every method,
     * because there is no implicit ambient filesystem in `commonMain`.
     */
    fun intoIter(sys: Sys): IntoIter = IntoIter(
        opts = opts,
        sys = sys,
        start = root,
    )

    override fun toString(): String = "WalkDir(opts=$opts, root=$root)"

    companion object {
        /**
         * Create a builder for a recursive directory iterator starting at the
         * file path `root`. If `root` is a directory, then it is the first
         * item yielded by the iterator. If `root` is a file, then it is the
         * first and only item yielded by the iterator. If `root` is a
         * symlink, then it is always followed for the purposes of directory
         * traversal. (A root `DirEntry` still obeys its documentation with
         * respect to symlinks and the `followLinks` setting.)
         */
        fun new(root: String): WalkDir = WalkDir(
            opts = WalkDirOptions(),
            root = root,
        )
    }
}
