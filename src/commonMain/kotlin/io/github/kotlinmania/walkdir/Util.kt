// port-lint: source src/util.rs
package io.github.kotlinmania.walkdir

/**
 * Returns the device number for the filesystem object at [path].
 *
 * On Unix this is `Metadata::dev` from `MetadataExt`. On Windows it is the
 * volume serial number reported by `GetFileInformationByHandle`. On targets
 * where neither shape exists, the result is an [IoError] of kind
 * [IoErrorKind.OTHER] with the upstream message
 * `"walkdir: same_file_system option not supported on this platform"`.
 *
 * The Kotlin port delegates the per-target work to the supplied [Sys] so the
 * three upstream `#[cfg]`-gated bodies collapse into a single dispatch here.
 */
internal fun deviceNum(sys: Sys, path: String): Result<ULong> =
    sys.deviceNum(path)
