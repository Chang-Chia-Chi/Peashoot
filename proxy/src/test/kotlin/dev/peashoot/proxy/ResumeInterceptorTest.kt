package dev.peashoot.proxy

import dev.peashoot.core.Exchange
import dev.peashoot.core.Frame
import dev.peashoot.core.Mode
import dev.peashoot.core.Outcome
import dev.peashoot.core.Route
import io.ktor.http.Headers
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * `Resume` driven through its own hooks, in the order the relay calls them.
 *
 * Everything else about resume is tested at the HTTP boundary, which is where the seam is. This one
 * interleaving cannot be reached from there: a joiner has to be cancelled in the instant between
 * the original completing and the joiner waking to find it complete, and nothing a client does over
 * a socket cancels a call — a departure is heard on the channel and never cancels the coroutine. So
 * the hooks are called by hand, on `runBlocking`'s single thread, where "cancelled with its
 * resumption already queued" is a sequence of statements rather than a race to provoke.
 */
class ResumeInterceptorTest {
    private fun exchange(gone: Boolean = false): Exchange =
        Exchange(
                Exchange.Request("POST", MESSAGES_PATH, Headers.Empty, BODY.toByteArray()),
                route = DEFAULT_ROUTE,
                routing = Route(Mode.RECORD),
            )
            .apply {
                fingerprint = PRINT
                clientGone = { gone }
            }

    /** Answers the exchange whole, as the drive does: the response first, then every frame. */
    private suspend fun Resume.streamed(exchange: Exchange) {
        exchange.response = Exchange.Response(200, Headers.Empty)
        onFrames(exchange, flowOf(TERMINAL)).collect {}
    }

    /**
     * A claim is spent only when it is served, so a joiner cancelled while parked on the original's
     * first byte gives it back — the answer is still being read, and the next re-issue should have
     * it rather than pay for a second call.
     */
    @Test
    fun `a claim a cancelled joiner gives back is there for the next re-issue`() = runBlocking {
        val resume = Resume(ProxyConfig(port = 0))
        val original = exchange(gone = true)
        assertNull(resume.onRequest(original), "the first request has nothing to resume")

        val parked = launch { resume.onRequest(exchange()) }
        yield() // far enough to claim the original and park on its response

        parked.cancel()
        parked.join()
        resume.streamed(original)

        assertNotNull(resume.onRequest(exchange(gone = true)), "the claim went back")
    }

    /**
     * And it gives back only what is still alive. If the original completes first, its own
     * completion takes it out of the buffer — its client never went, so there is nothing to keep —
     * and the joiner then waking into a cancel must not put it back. An entry restored there would
     * have no `goneAt`, which is the only thing eviction counts, so it would sit in the buffer for
     * the life of the process and be served to any later request that happened to match.
     */
    @Test
    fun `a claim a cancelled joiner gives back does not outlive the exchange`() = runBlocking {
        val resume = Resume(ProxyConfig(port = 0))
        val original = exchange(gone = true)
        resume.onRequest(original)

        val parked = launch { resume.onRequest(exchange()) }
        yield()

        // The original ends before the joiner wakes. Its response exists and is whole, so nothing
        // but "the client stayed" keeps it from being resumable — which is the point.
        resume.streamed(original)
        resume.onComplete(original, Outcome(200))

        parked.cancel()
        parked.join()

        assertNull(resume.onRequest(exchange(gone = true)), "a finished exchange is not revived")
    }

    /** The window is counted from the departure, so an exchange nobody left has no clock at all. */
    @Test
    fun `an exchange whose client stayed is never offered`() = runBlocking {
        val resume = Resume(ProxyConfig(port = 0))
        val original = exchange()
        resume.onRequest(original)
        resume.streamed(original)
        resume.onComplete(original, Outcome(200))

        assertNull(resume.onRequest(exchange(gone = true)))
    }

    private companion object {
        const val PRINT = "0f0f0f0f"
        const val BODY = """{"model":"claude-sonnet-5","messages":[]}"""
        val TERMINAL = Frame("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n", 0)
    }
}
