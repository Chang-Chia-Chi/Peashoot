package dev.peashoot.core

/**
 * One unit of a response as the client receives it: an SSE event block, or a whole non-streaming
 * body. [raw] is the text exactly as it arrived, so writing frames back out reproduces the stream
 * byte for byte (given valid UTF-8, which every provider sends). [offsetMillis] is the arrival time
 * relative to the start of the response.
 */
data class Frame(val raw: String, val offsetMillis: Long) {
    /**
     * The SSE `event:` field, or null when there is none (a comment, a non-streaming body). The
     * last one wins when a block repeats it, as the SSE spec says.
     */
    val event
        get() =
            raw.splitToSequence('\n')
                .lastOrNull { it.startsWith(EVENT_FIELD) }
                ?.removePrefix(EVENT_FIELD)
                ?.removePrefix(" ")
                ?.trimEnd('\r')

    private companion object {
        const val EVENT_FIELD = "event:"
    }
}

/**
 * Splits a response body into [Frame]s as its bytes arrive. A streaming (SSE) body splits at each
 * blank line, so one frame is one event block; whatever is left when the stream ends is the last
 * frame, so a cut stream loses nothing down to its last complete character. A non-streaming body is
 * one frame, delivered by [end]. Lines end in `\n` or `\r\n`; a bare `\r`, which no provider sends,
 * is line content.
 */
class FrameParser(private val streaming: Boolean) {
    // ponytail: pending is recopied on every chunk, quadratic for one big body (1 MB in 8 KB
    // chunks moves ~64 MB, a few ms). Streams stay linear because completed frames are cut off
    // each call. Upgrade: a doubling ByteArray with a length, private to this class; feed and
    // end keep their signatures.
    private var pending = ByteArray(0)
    private var lineStart = 0

    /** The frames these bytes complete, each stamped with [offsetMillis]. */
    fun feed(bytes: ByteArray, offsetMillis: Long): List<Frame> {
        val scanFrom = pending.size
        pending += bytes
        if (!streaming) return emptyList()
        val frames = ArrayList<Frame>()
        var frameStart = 0
        for (i in scanFrom until pending.size) {
            if (pending[i] != NEWLINE) continue
            val blankLine = i == lineStart || (i == lineStart + 1 && pending[lineStart] == RETURN)
            lineStart = i + 1
            if (blankLine) {
                frames += Frame(pending.decodeToString(frameStart, i + 1), offsetMillis)
                frameStart = i + 1
            }
        }
        if (frameStart > 0) {
            pending = pending.copyOfRange(frameStart, pending.size)
            lineStart -= frameStart
        }
        return frames
    }

    /** The unterminated remainder, if any: a non-streaming body, or the tail of a cut stream. */
    fun end(offsetMillis: Long): Frame? {
        val rest = pending
        pending = ByteArray(0)
        lineStart = 0
        return if (rest.isEmpty()) null else Frame(rest.decodeToString(), offsetMillis)
    }

    companion object {
        private const val NEWLINE = '\n'.code.toByte()
        private const val RETURN = '\r'.code.toByte()

        /** Parses a complete stream, every frame at offset 0. */
        fun parse(bytes: ByteArray) =
            FrameParser(streaming = true).run { feed(bytes, 0) + listOfNotNull(end(0)) }
    }
}
