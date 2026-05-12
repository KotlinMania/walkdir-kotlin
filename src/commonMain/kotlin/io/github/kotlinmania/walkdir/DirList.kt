// port-lint: source src/lib.rs
package io.github.kotlinmania.walkdir

/**
 * A sequence of unconsumed directory entries.
 *
 * This represents the opened or closed state of a directory handle. When
 * open, future entries are read by iterating over the raw [Sys.readDir]
 * iterator. When closed, all future entries are read into memory. Iteration
 * then proceeds over a `List<Result<DirEntry>>`.
 */
internal sealed class DirList : Iterator<Result<DirEntry>> {
    /**
     * An opened handle.
     *
     * This includes the depth of the handle itself.
     *
     * If there was an error with the initial [Sys.readDir] call, then it is
     * stored here. (We use a nullable wrapper to make yielding the error
     * exactly once simpler.)
     */
    class Opened(
        val depth: Int,
        // Either a successful iterator over raw entries, or a pending error to
        // be yielded exactly once on the next call to [next].
        var pendingError: Error?,
        val it: Iterator<Result<RawDirEntry>>?,
    ) : DirList() {
        private val cached: ArrayDeque<Result<DirEntry>> = ArrayDeque()
        private var pulledOne: Boolean = false

        override fun hasNext(): Boolean {
            if (cached.isNotEmpty()) return true
            if (pendingError != null) return true
            val src = it ?: return false
            if (!src.hasNext()) return false
            // Materialise one entry eagerly so hasNext is honest.
            advanceOne(src)
            return cached.isNotEmpty()
        }

        override fun next(): Result<DirEntry> {
            if (cached.isNotEmpty()) return cached.removeFirst()
            val err = pendingError
            if (err != null) {
                pendingError = null
                return Result.failure(err)
            }
            val src = it ?: throw NoSuchElementException()
            advanceOne(src)
            if (cached.isEmpty()) throw NoSuchElementException()
            return cached.removeFirst()
        }

        private fun advanceOne(src: Iterator<Result<RawDirEntry>>) {
            if (!src.hasNext()) return
            val raw = src.next()
            val entry = raw.fold(
                onSuccess = { rd -> DirEntry.fromRawEntry(depth + 1, rd) },
                onFailure = { err ->
                    val io = err as? IoError
                        ?: IoError(IoErrorKind.OTHER, err.message ?: "", err)
                    Result.failure<DirEntry>(Error.fromIo(depth + 1, io))
                },
            )
            cached.addLast(entry)
            pulledOne = true
        }
    }

    /** A closed handle. All remaining directory entries are read into memory. */
    class Closed(
        val entries: MutableList<Result<DirEntry>>,
    ) : DirList() {
        private var index: Int = 0

        override fun hasNext(): Boolean = index < entries.size

        override fun next(): Result<DirEntry> {
            if (index >= entries.size) throw NoSuchElementException()
            return entries[index++]
        }
    }

    /**
     * Drains this iterator and replaces it with a closed snapshot.
     *
     * Returns the same instance flipped to [Closed]. The Kotlin port returns a
     * fresh instance instead of mutating in place because [DirList] subclasses
     * have different fields; callers must therefore replace the slot in the
     * stack with the returned value.
     */
    fun close(): DirList {
        if (this !is Opened) return this
        val drained = ArrayList<Result<DirEntry>>()
        while (this.hasNext()) drained.add(this.next())
        return Closed(drained)
    }

    override fun toString(): String = when (this) {
        is Opened -> "DirList.Opened(depth=$depth)"
        is Closed -> "DirList.Closed(entries=${entries.size})"
    }
}
