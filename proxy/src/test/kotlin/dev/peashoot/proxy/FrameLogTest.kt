package dev.peashoot.proxy

import dev.peashoot.core.Frame
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * The hand-over resume rests on, on its own and away from any socket: a reader that arrives late
 * reads from the first frame while the owner is still appending, and gets every frame once, in
 * order, with nothing lost or repeated at the seam.
 *
 * On `runBlocking` rather than `runTest`, though this is suspend logic and not a seam: nothing here
 * waits on a clock, so a virtual one would buy nothing, and `kotlinx-coroutines-test` is in neither
 * the version catalog nor any module. `runBlocking`'s event loop is single-threaded, so `yield`
 * hands over to the reader exactly where the test says it does.
 */
class FrameLogTest {
    private fun frame(n: Int) = Frame("frame $n\n\n", n.toLong())

    private fun frames(range: IntRange) = range.map(::frame)

    @Test
    fun `a reader that joins mid-append gets the frames so far and then the rest`() = runBlocking {
        val log = FrameLog()
        frames(0..2).forEach { log.append(it) }

        val joined = async { log.frames().toList() }
        yield()
        frames(3..5).forEach {
            log.append(it)
            yield()
        }
        log.end(null)

        assertEquals(frames(0..5), joined.await())
    }

    /** Every reader is served from its own cursor, so two that join at different points agree. */
    @Test
    fun `two readers joining at different points each get every frame once`() = runBlocking {
        val log = FrameLog()
        val early = async { log.frames().toList() }
        yield()
        frames(0..2).forEach { log.append(it) }
        val late = async { log.frames().toList() }
        yield()
        frames(3..4).forEach { log.append(it) }
        log.end(null)

        assertEquals(frames(0..4), early.await())
        assertEquals(frames(0..4), late.await(), "from the first frame, however late it arrived")
    }

    @Test
    fun `a reader that arrives after the end still gets the whole answer`() = runBlocking {
        val log = FrameLog()
        frames(0..2).forEach { log.append(it) }
        log.end(null)

        assertTrue(log.done)
        assertFalse(log.failed)
        assertEquals(frames(0..2), log.frames().toList())
    }

    /**
     * A response that ended short ends its readers short too: the frames that arrived, and then the
     * failure, so a broken answer can never be mistaken for a whole one downstream.
     */
    @Test
    fun `a failed log gives the frames it had and then the failure`() = runBlocking {
        val log = FrameLog()
        frames(0..1).forEach { log.append(it) }
        log.end(IOException("the upstream went away"))

        val seen = mutableListOf<Frame>()
        assertFailsWith<IOException> { log.frames().collect { seen += it } }
        assertEquals(frames(0..1), seen)
        assertTrue(log.failed)
    }

    /**
     * The owner ends its flow's completion and then the exchange's; the first call is the truth.
     */
    @Test
    fun `the first end wins`() = runBlocking {
        val log = FrameLog()
        log.append(frame(0))
        log.end(null)
        log.end(IOException("too late to call it broken"))

        assertFalse(log.failed)
        assertEquals(frames(0..0), log.frames().toList())
    }

    /**
     * A reader that stops reading holds nothing up: the owner goes on, and so does everyone else.
     */
    @Test
    fun `a reader that leaves early stops nothing`() = runBlocking {
        val log = FrameLog()
        log.append(frame(0))
        assertEquals(frame(0), log.frames().first())

        val staying = async { log.frames().toList() }
        yield()
        frames(1..2).forEach { log.append(it) }
        log.end(null)

        assertEquals(frames(0..2), staying.await())
    }
}
