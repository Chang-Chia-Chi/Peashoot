package dev.peashoot.core

/**
 * One unit of a response as the client receives it: an SSE event block, or a whole non-streaming
 * body. [raw] is the text exactly as it arrived, so writing frames back out reproduces the stream
 * byte for byte; a malformed byte ends the response rather than being rewritten as U+FFFD, so a
 * recording is never silently corrupted. [offsetMillis] is the arrival time relative to the start
 * of the response.
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

    /**
     * The frames the first [length] bytes of [bytes] complete, each stamped with [offsetMillis].
     * The caller keeps its read buffer; only the bytes it just filled are taken.
     */
    fun feed(bytes: ByteArray, length: Int, offsetMillis: Long): List<Frame> {
        val scanFrom = pending.size
        val grown = pending.copyOf(scanFrom + length)
        bytes.copyInto(grown, destinationOffset = scanFrom, startIndex = 0, endIndex = length)
        pending = grown
        if (!streaming) return emptyList()
        val frames = ArrayList<Frame>()
        var frameStart = 0
        for (i in scanFrom until pending.size) {
            if (pending[i] != NEWLINE) continue
            val blankLine = i == lineStart || (i == lineStart + 1 && pending[lineStart] == RETURN)
            lineStart = i + 1
            if (blankLine) {
                // ponytail: a malformed byte here throws away the valid frames this call already
                // decoded; the stream is ending anyway. Park the exception and rethrow on the next
                // call if a chunk of good frames turns out to matter.
                frames +=
                    Frame(
                        pending.decodeToString(frameStart, i + 1, throwOnInvalidSequence = true),
                        offsetMillis,
                    )
                frameStart = i + 1
            }
        }
        if (frameStart > 0) {
            pending = pending.copyOfRange(frameStart, pending.size)
            lineStart -= frameStart
        }
        return frames
    }

    /**
     * The unterminated remainder, if any: a non-streaming body, or the tail of a cut stream. A cut
     * inside a multi-byte character drops that character's leading bytes, so the frame ends on the
     * last complete one.
     */
    fun end(offsetMillis: Long): Frame? {
        val rest = pending
        pending = ByteArray(0)
        lineStart = 0
        val complete = rest.size - rest.incompleteTail()
        return if (complete == 0) null
        else Frame(rest.decodeToString(0, complete, throwOnInvalidSequence = true), offsetMillis)
    }

    companion object {
        private const val NEWLINE = '\n'.code.toByte()
        private const val RETURN = '\r'.code.toByte()
        private const val BYTE_MASK = 0xFF
        private const val CONTINUATION_MASK = 0xC0
        private const val CONTINUATION = 0x80
        private const val LONGEST_CHARACTER = 4
        private val LEAD_OF_4 = 0xF0..0xF7
        private val LEAD_OF_3 = 0xE0..0xEF
        private val LEAD_OF_2 = 0xC2..0xDF

        /**
         * How many trailing bytes start a multi-byte character that has not finished: 0 when the
         * array ends on a complete character, or on bytes that are malformed either way and are
         * left for the strict decode to report.
         */
        private fun ByteArray.incompleteTail(): Int {
            var lead = size - 1
            while (lead >= 0 && size - lead < LONGEST_CHARACTER && isContinuation(this[lead])) {
                lead--
            }
            val expected =
                if (lead < 0) 0
                else
                    when (this[lead].toInt() and BYTE_MASK) {
                        in LEAD_OF_4 -> 4
                        in LEAD_OF_3 -> 3
                        in LEAD_OF_2 -> 2
                        else -> 0
                    }
            val present = size - lead
            return if (present < expected) present else 0
        }

        private fun isContinuation(byte: Byte) = byte.toInt() and CONTINUATION_MASK == CONTINUATION

        /** Parses a complete stream, every frame at offset 0. */
        fun parse(bytes: ByteArray) =
            FrameParser(streaming = true).run { feed(bytes, bytes.size, 0) + listOfNotNull(end(0)) }
    }
}
