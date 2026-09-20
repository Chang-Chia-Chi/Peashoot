package dev.peashoot.proxy

import io.ktor.client.statement.bodyAsText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

/**
 * What `[resume]` bounds (#26 criterion 2), at the proxy's HTTP boundary: an answer older than the
 * window is gone, an answer past the cap is gone oldest first, and either key at zero turns resume
 * off. The window here is a few hundred milliseconds where the default is five minutes, because
 * nothing in this codebase takes an injectable clock and watching a real one expire is the honest
 * alternative; only the negative is asserted, so a slow box makes the test slower and not flaky.
 */
class ResumeBoundsTest {
    private val frames = fixtureFrames("/anthropic-messages/stream-with-tool-use.sse")
    private val whole = frames.joinToString("")

    private fun JsonObject.flag(name: String): Boolean = getValue(name).jsonPrimitive.boolean

    /** The whole answer came back, from the provider, and no line claims it was resumed. */
    private suspend fun ResumeRig.assertAskedAgain(body: String, calls: Int, why: String) {
        assertEquals(whole, post(body).bodyAsText(), why)
        assertEquals(calls, upstream.received.size, why)
        assertFalse(awaitEvents(ResumeRig.COMPLETED, calls).any { it.flag("resumed") }, why)
    }

    @Test
    fun `an answer older than the window is no longer offered`() =
        withResume({ it.copy(resumeWindow = WINDOW) }) {
            streams(frames)
            val request = messagesRequest()
            dropMidStream(request)
            release.complete(Unit)
            awaitEvents(ResumeRig.COMPLETED, 1)
            delay(WINDOW * EXPIRY_MARGIN)

            assertAskedAgain(request, calls = 2, why = "the window had passed")
        }

    /**
     * Past the cap the oldest goes first, and the eviction is only the withdrawal of an offer: the
     * evicted exchange is still in flight here, and is read and recorded to its end as before.
     */
    @Test
    fun `past the cap the oldest answer is evicted, and it keeps recording`() =
        withResume({ it.copy(maxBufferedExchanges = 1) }) {
            streams(frames)
            val older = messagesRequest(system = "the older question")
            val newer = messagesRequest(system = "the newer question")
            dropMidStream(older)
            dropMidStream(newer, drops = 2)
            release.complete(Unit)
            awaitEvents(ResumeRig.COMPLETED, 2)

            assertAskedAgain(older, calls = 3, why = "the oldest went when the cap was reached")
            assertEquals(whole, post(newer).bodyAsText())
            assertEquals(3, upstream.received.size, "and the newest is still there to be resumed")
            val recorded =
                withTimeout(ResumeRig.TIMEOUT_MS) {
                    while (store.list().size < 4) delay(ResumeRig.POLL_MS)
                    store.list()
                }
            assertEquals(
                listOf(frames.size),
                recorded.map { it.frames.size }.distinct(),
                "every row holds the whole answer, the evicted one included",
            )
        }

    @Test
    fun `a zero window turns resume off`() =
        withResume({ it.copy(resumeWindow = Duration.ZERO) }) {
            streams(frames)
            val request = messagesRequest()
            dropMidStream(request)
            release.complete(Unit)
            awaitEvents(ResumeRig.COMPLETED, 1)

            assertAskedAgain(request, calls = 2, why = "nothing tracked, nothing served")
        }

    @Test
    fun `a zero cap turns resume off too`() =
        withResume({ it.copy(maxBufferedExchanges = 0) }) {
            streams(frames)
            val request = messagesRequest()
            dropMidStream(request)
            release.complete(Unit)
            awaitEvents(ResumeRig.COMPLETED, 1)

            assertAskedAgain(request, calls = 2, why = "no room to buffer one")
        }

    private companion object {
        val WINDOW = 400.milliseconds

        /** Three windows past the drop: long enough that a loaded box still sees it expire. */
        const val EXPIRY_MARGIN = 3
    }
}
