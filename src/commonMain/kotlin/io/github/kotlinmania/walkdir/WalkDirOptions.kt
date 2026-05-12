// port-lint: source src/lib.rs
package io.github.kotlinmania.walkdir

/**
 * Configuration state shared between a [WalkDir] builder and the [IntoIter] it
 * produces.
 *
 * Mirrors the upstream private struct of the same name. The Kotlin port keeps
 * this internal because nothing outside the module is expected to construct
 * one directly; consumers configure these values through chained methods on
 * [WalkDir].
 */
internal class WalkDirOptions(
    var followLinks: Boolean = false,
    var followRootLinks: Boolean = true,
    var maxOpen: Int = 10,
    var minDepth: Int = 0,
    var maxDepth: Int = Int.MAX_VALUE,
    var sorter: Comparator<DirEntry>? = null,
    var contentsFirst: Boolean = false,
    var sameFileSystem: Boolean = false,
) {
    override fun toString(): String {
        val sorterStr = if (sorter != null) "Some(...)" else "None"
        return "WalkDirOptions(" +
            "followLinks=$followLinks, " +
            "followRootLink=$followRootLinks, " +
            "maxOpen=$maxOpen, " +
            "minDepth=$minDepth, " +
            "maxDepth=$maxDepth, " +
            "sorter=$sorterStr, " +
            "contentsFirst=$contentsFirst, " +
            "sameFileSystem=$sameFileSystem" +
            ")"
    }
}
