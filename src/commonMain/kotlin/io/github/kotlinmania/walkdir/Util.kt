// port-lint: source util.rs
package io.github.kotlinmania.walkdir

/**
 * Returns the device number for the filesystem object at [path].
 *
 * On Unix this is the device number. On Windows it is the volume serial number.
 * On targets where neither exists, the result is an [IoError] of kind
 * [IoErrorKind.OTHER] with the error message
 * `"walkdir: sameFileSystem option not supported on this platform"`.
 *
 * The Kotlin port delegates the per-target work to the supplied [Sys].
 */
internal fun deviceNum(sys: Sys, path: String): Result<ULong> =
    sys.deviceNum(path)

