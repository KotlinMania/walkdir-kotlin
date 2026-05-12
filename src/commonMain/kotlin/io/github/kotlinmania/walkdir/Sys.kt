// port-lint: ignore — filesystem seam with no single Rust counterpart. Upstream
// `walkdir` calls `std::fs::{read_dir, metadata, symlink_metadata}` directly;
// Kotlin Multiplatform `commonMain` has no filesystem, so this interface stands
// in for those calls. Each KMP target supplies its own actual elsewhere; the
// pure-Kotlin port consumes the abstraction here.
package io.github.kotlinmania.walkdir

/**
 * Kinds of IO errors observable by walkdir.
 *
 * Mirrors a small subset of `std::io::ErrorKind` — only the variants the port
 * actually inspects via [IoError.kind]. Other kinds collapse into [Other].
 */
enum class IoErrorKind {
    NOT_FOUND,
    PERMISSION_DENIED,
    ALREADY_EXISTS,
    NOT_A_DIRECTORY,
    IS_A_DIRECTORY,
    OTHER,
}

/**
 * An IO error reported by a [Sys] operation.
 *
 * Stands in for upstream's `std::io::Error`. Carries an [IoErrorKind] discriminator
 * so callers that match on `ErrorKind` (as in the upstream `From<Error> for io::Error`
 * conversion) can do the same here.
 */
open class IoError(
    val kind: IoErrorKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The file type of a filesystem entry.
 *
 * Mirrors `std::fs::FileType`. Note that this is intentionally exhaustive over
 * the three flags walkdir uses; other Rust file-type predicates (`is_block_device`,
 * `is_char_device`, etc.) are not required by the port and so are omitted.
 */
data class FileType(
    val isDir: Boolean,
    val isFile: Boolean,
    val isSymlink: Boolean,
)

/**
 * Metadata for a filesystem entry.
 *
 * Mirrors `std::fs::Metadata`, exposing only the bits walkdir reads:
 *
 *  - [fileType] for the `is_dir` / `is_symlink` checks.
 *  - [ino] for `MetadataExt::ino` (Unix only; null elsewhere).
 *  - [dev] for `MetadataExt::dev` (Unix only; null elsewhere).
 */
interface Metadata {
    val fileType: FileType
    val ino: ULong?
    val dev: ULong?
}

/**
 * A raw directory entry yielded by [Sys.readDir].
 *
 * Stands in for `std::fs::DirEntry`. The implementation may cache file-type and
 * metadata answers from the underlying `readdir`/`FindNextFile` call; the
 * port's [io.github.kotlinmania.walkdir.DirEntry] copies them once during
 * construction, so subsequent calls are not required to be cheap.
 */
interface RawDirEntry {
    /** The absolute or root-relative path of the entry. */
    fun path(): String

    /** Returns the file type without following symlinks. */
    fun fileType(): Result<FileType>

    /** Returns metadata without following symlinks. */
    fun metadata(): Result<Metadata>

    /** Returns the underlying `d_ino` field on Unix, or null elsewhere. */
    val ino: ULong?
}

/**
 * An opaque handle to a filesystem object, used for loop detection.
 *
 * Mirrors the `same_file::Handle` type from upstream. Two handles compare equal
 * if and only if they refer to the same underlying filesystem object (matching
 * inode/device pair on Unix, file-id on Windows). The `equals` and `hashCode`
 * contract on the implementing type is the comparison.
 */
interface Handle

/**
 * The filesystem seam that the rest of the port calls through.
 *
 * A caller that wants to walk a real filesystem must construct a target-specific
 * implementation. A caller that wants to walk a synthetic tree (tests, an
 * in-memory snapshot, an archive) implements this directly.
 */
interface Sys {
    /**
     * Reads the directory at [path].
     *
     * Returns an iterator over the directory's entries. If the initial open
     * fails, the result is a failure; per-entry errors are surfaced in the
     * inner [Result].
     */
    fun readDir(path: String): Result<Iterator<Result<RawDirEntry>>>

    /** Returns metadata for [path], following symbolic links. */
    fun metadata(path: String): Result<Metadata>

    /** Returns metadata for [path], not following symbolic links. */
    fun symlinkMetadata(path: String): Result<Metadata>

    /**
     * Returns the device number containing [path].
     *
     * Used by the `same_file_system` option. Implementations that cannot answer
     * (`wasm32-unknown-unknown`, JS without `fs`, etc.) return a failure with an
     * [IoError] of kind [IoErrorKind.OTHER] and the upstream message
     * `"walkdir: same_file_system option not supported on this platform"`.
     */
    fun deviceNum(path: String): Result<ULong>

    /**
     * Returns an opaque handle to the filesystem object at [path].
     *
     * Used for symlink-loop detection. Equality and hash codes on the returned
     * value identify the underlying filesystem object.
     */
    fun handleFromPath(path: String): Result<Handle>
}

/**
 * Path separators recognised by the pure-Kotlin path helpers below.
 *
 * Both `/` and `\` are recognised so the port behaves identically on Unix and
 * Windows hosts. Upstream `walkdir` defers to `std::path::Path`, which is
 * separator-aware per target; the port collapses both separators since it never
 * needs to round-trip a path back through the host APIs.
 */
private val PATH_SEPARATORS: CharArray = charArrayOf('/', '\\')

/**
 * Joins [parent] and [child] with a `/` separator.
 *
 * If [parent] already ends in a separator (`/` or `\`), no separator is added.
 * If [parent] is empty, [child] is returned unchanged. Mirrors the relevant
 * subset of `Path::join` used by walkdir.
 */
internal fun joinPath(parent: String, child: String): String {
    if (parent.isEmpty()) return child
    val last = parent[parent.length - 1]
    if (last == '/' || last == '\\') return parent + child
    return "$parent/$child"
}

/**
 * Returns the file-name component of [path], or `null` if [path] has no name
 * component (e.g. `/` or `C:\`). Mirrors `Path::file_name`.
 */
internal fun fileNameOf(path: String): String? {
    if (path.isEmpty()) return null
    var end = path.length
    while (end > 0 && (path[end - 1] == '/' || path[end - 1] == '\\')) end--
    if (end == 0) return null
    val start = path.lastIndexOfAny(PATH_SEPARATORS, end - 1)
    return path.substring(start + 1, end)
}
