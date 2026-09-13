package dev.peashoot.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameParserTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/anthropic-messages/$name")) { name }
            .readBytes()

    @Test
    fun `re-serialized frames are byte-equal to the fixture`() {
        for (name in listOf("stream-with-tool-use.sse", "stream-ending-in-error.sse")) {
            val bytes = fixture(name)
            val frames = FrameParser.parse(bytes)
            assertTrue(frames.size > 1, "$name should split into frames")
            assertContentEquals(bytes, frames.joinToString("") { it.raw }.toByteArray(), name)
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
                parser.feed(byteArrayOf(bytes[i]), offsetMillis = i.toLong())
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
        val early =
            parser.feed(bytes.copyOfRange(0, 10), 0) +
                parser.feed(bytes.copyOfRange(10, bytes.size), 5)
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
    fun `a multi-byte character split across chunks decodes intact`() {
        val text = "event: content_block_delta\ndata: {\"text\":\"café\"}\n\n"
        val bytes = text.toByteArray()
        val split = bytes.indexOf(0xC3.toByte()) + 1 // between the two bytes of é
        val parser = FrameParser(streaming = true)

        val frames =
            parser.feed(bytes.copyOfRange(0, split), 0) +
                parser.feed(bytes.copyOfRange(split, bytes.size), 1)

        assertEquals(listOf(Frame(text, 1)), frames)
    }
}
