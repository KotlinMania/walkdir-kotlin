// port-lint: source src/dent.rs
package io.github.kotlinmania.walkdir

/**
 * A directory entry.
 *
 * This is the type of value that is yielded from the iterators defined in this
 * crate.
 *
 * On Unix systems, this type exposes the [ino] property for efficient access
 * to the inode number of the directory entry. On non-Unix targets [ino] is
 * `null`.
 *
 * # Differences with `std::fs::DirEntry`
 *
 * This type mostly mirrors the upstream Rust type by the same name. There are
 * some differences however:
 *
 * * All recursive directory iterators must inspect the entry's type. Therefore,
 * the value is stored and its access is guaranteed to be cheap and successful.
 * * [path] and [fileName] return borrowed variants.
 * * If [WalkDir.followLinks] was enabled on the originating iterator, then all
 * operations except for [path] operate on the link target. Otherwise, all
 * operations operate on the symbolic link.
 */
class DirEntry private constructor(
    /**
     * The path as reported by the [Sys.readDir] iterator (even if it's a
     * symbolic link).
     */
    private val path: String,
    /** The file type. Necessary for recursive iteration, so store it. */
    private val ty: FileType,
    /**
     * Is set when this entry was created from a symbolic link and the user
     * expects the iterator to follow symbolic links.
     */
    private val followLink: Boolean,
    /** The depth at which this entry was generated relative to the root. */
    private val depth: Int,
    /** The underlying inode number (Unix only; `null` elsewhere). */
    val ino: ULong?,
    /**
     * The underlying metadata snapshot taken at entry creation, retained on
     * Windows because it comes for free while reading a directory. The Kotlin
     * port stores it on every target so [metadata] can answer without a
     * follow-up syscall when [followLink] is false; the field is `null` if the
     * directory entry was constructed without metadata (the Unix `from_entry`
     * fast path in upstream).
     *
     * We use this to determine whether an entry is a directory or not on
     * Windows, which works around a bug in Rust's standard library:
     * https://github.com/rust-lang/rust/issues/46484
     */
    private val cachedMetadata: Metadata?,
) {
    /**
     * The full path that this entry represents.
     *
     * The full path is created by joining the parents of this entry up to the
     * root initially given to [WalkDir.new] with the file name of this entry.
     *
     * Note that this *always* returns the path reported by the underlying
     * directory entry, even when symbolic links are followed. To get the
     * target path, use [pathIsSymlink] to (cheaply) check if this entry
     * corresponds to a symbolic link, and `Sys.readLink` to resolve the
     * target.
     */
    fun path(): String = path

    /**
     * The full path that this entry represents.
     *
     * Analogous to [path]. The Kotlin port keeps paths as immutable strings,
     * so there is no separate owning-vs-borrowed distinction.
     */
    fun intoPath(): String = path

    /**
     * Returns `true` if and only if this entry was created from a symbolic
     * link. This is unaffected by the [WalkDir.followLinks] setting.
     *
     * When `true`, the value returned by the [path] method is a symbolic link
     * name. To get the full target path, you must call `Sys.readLink` on the
     * supplied [Sys] with [path].
     */
    fun pathIsSymlink(): Boolean = ty.isSymlink || followLink

    /**
     * Return the metadata for the file that this entry points to.
     *
     * This will follow symbolic links if and only if the [WalkDir] value has
     * [WalkDir.followLinks] enabled.
     *
     * # Platform behavior
     *
     * This always calls [Sys.symlinkMetadata].
     *
     * If this entry is a symbolic link and [WalkDir.followLinks] is enabled,
     * then [Sys.metadata] is called instead.
     *
     * # Errors
     *
     * Similar to [Sys.metadata], returns failures for path values that the
     * program does not have permissions to access or if the path does not
     * exist.
     */
    fun metadata(sys: Sys): Result<Metadata> = metadataInternal(sys)

    private fun metadataInternal(sys: Sys): Result<Metadata> {
        if (!followLink) {
            cachedMetadata?.let { return Result.success(it) }
        }
        val res = if (followLink) sys.metadata(path) else sys.symlinkMetadata(path)
        return res.fold(
            onSuccess = { Result.success(it) },
            onFailure = { err ->
                val io = err as? IoError ?: IoError(IoErrorKind.OTHER, err.message ?: "", err)
                Result.failure(Error.fromEntry(this, io))
            },
        )
    }

    /**
     * Return the file type for the file that this entry points to.
     *
     * If this is a symbolic link and [WalkDir.followLinks] is `true`, then
     * this returns the type of the target.
     *
     * This never makes any system calls.
     */
    fun fileType(): FileType = ty

    /**
     * Return the file name of this entry.
     *
     * If this entry has no file name (e.g., `/`), then the full path is
     * returned.
     */
    fun fileName(): String = fileNameOf(path) ?: path

    /**
     * Returns the depth at which this entry was created relative to the root.
     *
     * The smallest depth is `0` and always corresponds to the path given to
     * the [WalkDir.new] function on `WalkDir`. Its direct descendents have
     * depth `1`, and their descendents have depth `2`, and so on.
     */
    fun depth(): Int = depth

    /** Returns true if and only if this entry points to a directory. */
    internal fun isDir(): Boolean = ty.isDir

    override fun toString(): String = "DirEntry($path)"

    internal companion object {
        internal fun fromRawEntry(depth: Int, ent: RawDirEntry): Result<DirEntry> {
            val tyRes = ent.fileType()
            val ty = tyRes.getOrElse { err ->
                val io = err as? IoError ?: IoError(IoErrorKind.OTHER, err.message ?: "", err)
                return Result.failure(Error.fromPath(depth, ent.path(), io))
            }
            // On Windows the upstream eagerly calls `ent.metadata()` for both the
            // file-type workaround and to cache it on the DirEntry. The Kotlin
            // port keeps the metadata if the seam can answer it cheaply (the
            // implementation is free to short-circuit), and falls back to null
            // when only the file type was requested.
            val mdRes = ent.metadata()
            val md = mdRes.getOrNull()
            return Result.success(
                DirEntry(
                    path = ent.path(),
                    ty = ty,
                    followLink = false,
                    depth = depth,
                    ino = ent.ino ?: md?.ino,
                    cachedMetadata = md,
                ),
            )
        }

        internal fun fromPath(
            sys: Sys,
            depth: Int,
            pb: String,
            follow: Boolean,
        ): Result<DirEntry> {
            val mdRes = if (follow) sys.metadata(pb) else sys.symlinkMetadata(pb)
            val md = mdRes.getOrElse { err ->
                val io = err as? IoError ?: IoError(IoErrorKind.OTHER, err.message ?: "", err)
                return Result.failure(Error.fromPath(depth, pb, io))
            }
            return Result.success(
                DirEntry(
                    path = pb,
                    ty = md.fileType,
                    followLink = follow,
                    depth = depth,
                    ino = md.ino,
                    cachedMetadata = md,
                ),
            )
        }
    }
}

/**
 * Unix-specific accessor for [DirEntry.ino], mirroring the upstream Rust
 * `DirEntryExt::ino` method shape so callers ported from Rust that wrote
 * `entry.ino()` can keep the call. On non-Unix targets the [DirEntry.ino]
 * property is `null`, so this returns `null` rather than panicking.
 */
fun DirEntry.ino(): ULong? = ino
