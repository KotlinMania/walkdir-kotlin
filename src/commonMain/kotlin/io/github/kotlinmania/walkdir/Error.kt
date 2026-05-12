// port-lint: source src/error.rs
package io.github.kotlinmania.walkdir

/**
 * An error produced by recursively walking a directory.
 *
 * This error type is a light wrapper around [IoError]. In particular, it adds
 * the following information:
 *
 * * The depth at which the error occurred in the file tree, relative to the
 * root.
 * * The path, if any, associated with the IO error.
 * * An indication that a loop occurred when following symbolic links. In this
 * case, there is no underlying IO error.
 *
 * To maintain good ergonomics, this type has a [toIoError] conversion defined
 * which preserves the original context. This allows you to use a `Result<T>`
 * with methods in this crate if you don't care about accessing the underlying
 * error data in a structured form.
 */
class Error private constructor(
    private val depth: Int,
    private val inner: ErrorInner,
) : Exception(buildMessage(inner), inner.cause()) {
    /**
     * Returns the path associated with this error if one exists.
     *
     * For example, if an error occurred while opening a directory handle, the
     * error will include the path passed to [Sys.readDir].
     */
    fun path(): String? = when (val it = inner) {
        is ErrorInner.Io -> it.path
        is ErrorInner.Loop -> it.child
    }

    /**
     * Returns the path at which a cycle was detected.
     *
     * If no cycle was detected, `null` is returned.
     *
     * A cycle is detected when a directory entry is equivalent to one of its
     * ancestors.
     *
     * To get the path to the child directory entry in the cycle, use the
     * [path] method.
     */
    fun loopAncestor(): String? = when (val it = inner) {
        is ErrorInner.Loop -> it.ancestor
        else -> null
    }

    /**
     * Returns the depth at which this error occurred relative to the root.
     *
     * The smallest depth is `0` and always corresponds to the path given to the
     * [WalkDir.new] function on [WalkDir]. Its direct descendents have depth
     * `1`, and their descendents have depth `2`, and so on.
     */
    fun depth(): Int = depth

    /**
     * Inspect the original [IoError] if there is one.
     *
     * `null` is returned if the [Error] doesn't correspond to an [IoError].
     * This might happen, for example, when the error was produced because a
     * cycle was found in the directory tree while following symbolic links.
     *
     * This method returns a borrowed value that is bound to the lifetime of
     * the [Error]. To obtain an owned value, the [intoIoError] can be used
     * instead.
     *
     * > This is the original [IoError] and is _not_ the same as
     * > [toIoError] which contains additional context about the error.
     *
     * # Example
     *
     * ```kotlin
     * import io.github.kotlinmania.walkdir.WalkDir
     *
     * for (entry in WalkDir.new("foo").intoIter(sys)) {
     *     entry.onSuccess { e ->
     *         println(e.path())
     *     }.onFailure { err ->
     *         val wErr = err as Error
     *         val path = wErr.path() ?: ""
     *         println("failed to access entry $path")
     *         wErr.ioError()?.let { inner ->
     *             when (inner.kind) {
     *                 IoErrorKind.NOT_FOUND ->
     *                     println("entry not found: ${inner.message}")
     *                 IoErrorKind.PERMISSION_DENIED ->
     *                     println("Missing permission to read entry: ${inner.message}")
     *                 else ->
     *                     println("Unexpected error occurred: ${inner.message}")
     *             }
     *         }
     *     }
     * }
     * ```
     */
    fun ioError(): IoError? = when (val it = inner) {
        is ErrorInner.Io -> it.err
        is ErrorInner.Loop -> null
    }

    /**
     * Similar to [ioError] except consumes self to convert to the original
     * [IoError] if one exists.
     */
    fun intoIoError(): IoError? = ioError()

    /**
     * Convert the [Error] to an [IoError], preserving the original [Error] as
     * the cause. Note that this also makes the display of the error include
     * the context.
     *
     * This is different from [intoIoError] which returns the original
     * [IoError].
     */
    fun toIoError(): IoError {
        val kind = when (val it = inner) {
            is ErrorInner.Io -> it.err.kind
            is ErrorInner.Loop -> IoErrorKind.OTHER
        }
        return IoError(kind, message ?: "", this)
    }

    internal companion object {
        internal fun fromPath(depth: Int, pb: String, err: IoError): Error =
            Error(depth, ErrorInner.Io(path = pb, err = err))

        internal fun fromEntry(dent: DirEntry, err: IoError): Error =
            Error(dent.depth(), ErrorInner.Io(path = dent.path(), err = err))

        internal fun fromIo(depth: Int, err: IoError): Error =
            Error(depth, ErrorInner.Io(path = null, err = err))

        internal fun fromLoop(depth: Int, ancestor: String, child: String): Error =
            Error(depth, ErrorInner.Loop(ancestor = ancestor, child = child))

        private fun buildMessage(inner: ErrorInner): String = when (inner) {
            is ErrorInner.Io ->
                if (inner.path == null) inner.err.message ?: ""
                else "IO error for operation on ${inner.path}: ${inner.err.message ?: ""}"
            is ErrorInner.Loop ->
                "File system loop found: ${inner.child} points to an ancestor ${inner.ancestor}"
        }
    }
}

private sealed class ErrorInner {
    class Io(val path: String?, val err: IoError) : ErrorInner()
    class Loop(val ancestor: String, val child: String) : ErrorInner()

    fun cause(): Throwable? = when (this) {
        is Io -> err
        is Loop -> null
    }
}
