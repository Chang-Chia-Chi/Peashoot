package dev.peashoot.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FrameParserTest {
    private fun fixture(name: String, dir: String = "anthropic-messages"): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/$dir/$name")) { "$dir/$name" }.readBytes()

    /**
     * Every streamed fixture in the repository, whichever surface it belongs to: this is what the
     * fixture READMEs mean when they say the round trip is proven byte for byte, so a new one that
     * is not listed here has no such proof.
     */
    @Test
    fun `re-serialized frames are byte-equal to the fixture`() {
        val streams =
            listOf(
                "anthropic-messages" to "stream-with-tool-use.sse",
                "anthropic-messages" to "stream-ending-in-error.sse",
                "openai-chat" to "stream-with-tool-calls.sse",
                "openai-chat" to "stream-without-usage.sse",
                "openai-responses" to "stream-with-function-call.sse",
                "openai-responses" to "stream-incomplete.sse",
            )
        for ((dir, name) in streams) {
            val bytes = fixture(name, dir)
            val frames = FrameParser.parse(bytes)
            assertTrue(frames.size > 1, "$dir/$name should split into frames")
            assertContentEquals(
                bytes,
                frames.joinToString("") { it.raw }.toByteArray(),
                "$dir/$name",
            )
        }
    }

    @Test
    fun `handles every Messages event type`() {
        val turn = FrameParser.parse(fixture("stream-with-tool-use.sse")).map { it.event }
        assertEquals("message_start", turn.first())
        assertEquals("message_stop", turn.last())
        assertTrue(
            turn.containsAll(
                listOf(
                    "ping",
                    "content_block_start",
                    "content_block_delta",
                    "content_block_stop",
                    "message_delta",
                )
            ),
            "$turn",
        )
        assertEquals(
            listOf("message_start", "error"),
            FrameParser.parse(fixture("stream-ending-in-error.sse")).map { it.event },
        )
    }

    @Test
    fun `frames are the same whatever the chunk boundaries, stamped with the chunk that completed them`() {
        val bytes = fixture("stream-with-tool-use.sse")
        val whole = FrameParser.parse(bytes)

        val parser = FrameParser(streaming = true)
        val byteAtATime =
            bytes.indices.flatMap { i ->
                parser.feed(byteArrayOf(bytes[i]), length = 1, offsetMillis = i.toLong())
            } + listOfNotNull(parser.end(bytes.size.toLong()))

        assertEquals(whole.map { it.raw }, byteAtATime.map { it.raw })
        // Each frame completes with its own last byte.
        val lastByteIndexes =
            whole.runningFold(0) { end, frame -> end + frame.raw.toByteArray().size }.drop(1)
        assertEquals(lastByteIndexes.map { it - 1L }, byteAtATime.map { it.offsetMillis })
    }

    @Test
    fun `a non-streaming JSON response is one frame, however it arrived`() {
        val bytes = fixture("non-streaming-401.json")
        val parser = FrameParser(streaming = false)
        val head = bytes.copyOfRange(0, 10)
        val tail = bytes.copyOfRange(10, bytes.size)
        val early = parser.feed(head, head.size, 0) + parser.feed(tail, tail.size, 5)
        val frame = parser.end(7)

        assertEquals(emptyList(), early)
        assertEquals(Frame(bytes.decodeToString(), 7), frame)
        assertContentEquals(bytes, frame?.raw?.toByteArray())
        assertEquals(null, frame?.event)
    }

    @Test
    fun `a cut stream keeps its partial last frame`() {
        val bytes = fixture("stream-with-tool-use.sse")
        val cut = bytes.copyOfRange(0, bytes.size - 12) // inside the message_stop block
        val frames = FrameParser.parse(cut)

        assertContentEquals(cut, frames.joinToString("") { it.raw }.toByteArray())
        assertEquals("message_stop", frames.last().event)
        assertTrue(!frames.last().raw.endsWith("\n\n"), frames.last().raw)
    }

    @Test
    fun `crlf line endings end frames too`() {
        val lf = fixture("stream-with-tool-use.sse")
        val crlf = lf.decodeToString().replace("\n", "\r\n").toByteArray()
        val frames = FrameParser.parse(crlf)

        assertEquals(FrameParser.parse(lf).map { it.event }, frames.map { it.event })
        assertContentEquals(crlf, frames.joinToString("") { it.raw }.toByteArray())
    }

    @Test
    fun `a malformed byte ends the response rather than being rewritten`() {
        // A lone 0x80 is a continuation byte with nothing to continue: no valid split produces it.
        val bytes = "event: x\ndata: ".toByteArray() + 0x80.toByte() + "\n\n".toByteArray()

        assertFailsWith<CharacterCodingException> {
            FrameParser(streaming = true).feed(bytes, bytes.size, offsetMillis = 0)
        }
    }

    @Test
    fun `a stream cut inside a multi-byte character ends on the last complete one`() {
        val bytes = "event: content_block_delta\ndata: {\"text\":\"café".toByteArray()
        val cut = bytes.copyOfRange(0, bytes.size - 1) // é is 0xC3 0xA9; only 0xC3 arrived
        val parser = FrameParser(streaming = true)

        assertEquals(emptyList(), parser.feed(cut, cut.size, offsetMillis = 0))
        assertEquals("event: content_block_delta\ndata: {\"text\":\"caf", parser.end(0)?.raw)
    }

    @Test
    fun `a multi-byte character split across chunks decodes intact`() {
        val text = "event: content_block_delta\ndata: {\"text\":\"café\"}\n\n"
        val bytes = text.toByteArray()
        val split = bytes.indexOf(0xC3.toByte()) + 1 // between the two bytes of é
        val parser = FrameParser(streaming = true)

        val head = bytes.copyOfRange(0, split)
        val tail = bytes.copyOfRange(split, bytes.size)
        val frames = parser.feed(head, head.size, 0) + parser.feed(tail, tail.size, 1)

        assertEquals(listOf(Frame(text, 1)), frames)
    }
}
