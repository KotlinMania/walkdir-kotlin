// port-lint: source src/lib.rs
package io.github.kotlinmania.walkdir

/**
 * An ancestor is an item in the directory tree traversed by walkdir, and is
 * used to check for loops in the tree when traversing symlinks.
 *
 * The upstream Rust struct stores a cached `same_file::Handle` on Windows
 * because opening a file handle there is comparatively expensive. The Kotlin
 * port mirrors that by caching a [Handle] (the [Sys] seam's stand-in for
 * `same_file::Handle`) on every target. The cost on Unix is one extra opened
 * handle per ancestor; the win on Windows is unchanged.
 */
internal class Ancestor(
    /** The path of this ancestor. */
    val path: String,
    /**
     * A cached opaque handle to this ancestor.
     *
     * Upstream gates this field on `cfg(windows)`. The Kotlin port always
     * stores it because the [Sys.handleFromPath] seam may be expensive on any
     * target, and the cache costs at most one handle per ancestor.
     */
    val handle: Handle,
) {
    /**
     * Returns true if and only if the given open file handle corresponds to
     * the same directory as this ancestor.
     */
    fun isSame(child: Handle): Result<Boolean> =
        Result.success(child == handle)

    companion object {
        /** Create a new ancestor from the given directory path. */
        fun new(sys: Sys, dent: DirEntry): Result<Ancestor> =
            sys.handleFromPath(dent.path()).map { handle ->
                Ancestor(path = dent.path(), handle = handle)
            }
    }
}
