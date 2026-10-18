// port-lint: source util.rs
package io.github.kotlinmania.walkdir

import io.github.kotlinmania.winapiutil.Handle
import io.github.kotlinmania.winapiutil.information

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

/**
 * Windows device number implementation using winapi-util.
 *
 * Corresponds to upstream `device_num` on Windows:
 * `let h = Handle::from_path_any(path)?;`
 * `file::information(h).map(|info| info.volume_serial_number())`
 */
internal fun windowsDeviceNum(path: String): Result<ULong> =
    runCatching {
        val h = Handle.fromPathAny(path)
        information(h).volumeSerialNumber()
    }
