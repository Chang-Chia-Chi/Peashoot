package dev.peashoot.proxy

import dev.peashoot.core.Continuable
import dev.peashoot.core.Exchange
import dev.peashoot.core.Frame
import dev.peashoot.core.FrameSource
import dev.peashoot.core.Interceptor
import dev.peashoot.core.Mode
import dev.peashoot.core.Outcome
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import org.slf4j.LoggerFactory
import org.tomlj.TomlParseResult

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

/**
 * How long after the drop a continuation match is still on offer (ADR 0002).
 *
 * The spike timed Claude Code's automatic re-issue at 31 to 47 ms after a mid-stream cut, and at
 * 625 ms after a cut before the first byte — and that second one is re-issued byte for byte, so it
 * matches exactly and never needs this. Five seconds is two orders of magnitude of margin on the
 * figure that does need it, which leaves room for a machine that has just woken up, and is still
 * far below the time it takes a person to read a truncated answer, decide it was wrong, and type a
 * different question. That gap is the whole point: an appended text block of *any* wording passes
 * the prefix relation, so a client that merges a user's interrupt-then-retype into one user turn
 * would otherwise be handed the old answer to a new question.
 */
internal val CONTINUATION_GRACE = 5.seconds

/**
 * Stream-resume (#26, design section 4 steps 3 and 5): a client that leaves mid-answer and asks
 * again is served the answer the proxy went on reading, from its first frame, live if the upstream
 * is still streaming, with no second upstream call and so nothing billed twice. First in the chain.
 *
 * Who may be resumed. Only an exchange whose client has gone: design step 5 is what makes an
 * exchange "eligible for Resume", and two equal requests from two clients that are both still there
 * are a user asking twice, which must stay two upstream calls or nobody could ask for a fresh
 * sample. Gone is [Exchange.clientGone], the engine's word, as well as the flag the client writer
 * sets, because the writer only looks between frames and a re-issue can beat it there. An error
 * status is never served again: it is no completion, and nothing was billed for it. Nor is an
 * answer whose stream ended short of its surface's terminal frame — a cut upstream does not fail,
 * it simply stops — because a broken answer served as a whole one costs the client the retry it
 * made. A re-issue that joined while the answer was still streaming shares its fate instead, cut
 * where the original was cut, which is the stream the provider would have given the client itself.
 *
 * What a re-issue looks like. The same fingerprint, on any surface; or, where the surface has a
 * measured story of its own, a [Continuable] request continued (ADR 0002), which finds its
 * candidates by the fingerprint of the request's stem and then compares the rest exactly, never
 * matching on that hash alone.
 *
 * One drop buys one resume. A claim takes the exchange out of [claimable], so the next equal
 * request goes upstream: one buffered answer would otherwise be served to every retry for the whole
 * window, and a user who does want a fresh sample gets one on the second ask. A laptop that sleeps
 * twice is still covered, because the resumed exchange is tracked like any other and becomes
 * claimable itself when its own client goes.
 *
 * Every mutation of [claimable] is one atomic map operation, and a claim is `remove(id, entry)`,
 * which succeeds for exactly one of two re-issues racing for the same answer; the loser goes
 * upstream. Nothing here launches a coroutine, so there is no job to leak: expiry is looked at when
 * a request or a departure comes by.
 *
 * Replay routes are left to Replay: a hit there is free already, and a cassette must answer the
 * same way on every run.
 *
 * ponytail: every frame of every tracked answer is held in memory as text, so the ceiling is
 * (`resume.maxBufferedExchanges` + the exchanges in flight) x the size of an answer: 100 answers of
 * 1 MB are about 100 MB, twice that for text outside Latin-1. In record mode the in-flight half
 * costs only a list, since the recorder holds the same [Frame] objects. Upgrade: a byte budget that
 * evicts oldest-first, or serving a completed answer from the store's frames, which the recorder
 * has already written.
 *
 * ponytail: an expired answer is dropped when the next request or departure arrives, not when its
 * window ends, so an idle proxy holds up to the cap until then. Upgrade: a timer in the
 * application's scope, if the idle footprint ever matters.
 *
 * ponytail: a re-issue is matched by scanning the tracked exchanges, at most the cap plus those in
 * flight. Upgrade: index them by fingerprint and stem, if the cap ever grows into the thousands.
 *
 * ponytail: a client that leaves before the upstream has answered at all is only resumable once the
 * engine reports its connection closed; a connection that goes silent without closing, which is
 * what a sleeping laptop leaves behind, looks open until a write to it fails. Upgrade: none on this
 * side of the socket; the client's own watchdog is what ends such a connection.
 */
class Resume(
    config: ProxyConfig,
    /**
     * How soon after the drop a continuation must arrive. Not a config key and not meant to be one:
     * it is a property of how clients behave, not of how anyone wants their proxy to run, and the
     * default is the figure below. Tests pass a shorter one rather than sleep through this one.
     */
    private val grace: Duration = CONTINUATION_GRACE,
) : Interceptor {
    private val window = config.resumeWindow
    private val capacity = config.maxBufferedExchanges

    /** Either key at zero is how the file says "no resume": nothing is tracked, nothing served. */
    private val enabled = window.isPositive() && capacity > 0

    /** What Resume keeps of one exchange: how to recognise its re-issue, and its answer so far. */
    private class Entry(exchange: Exchange) {
        val id = exchange.id
        val fingerprint = exchange.fingerprint
        val clientGone = exchange.clientGone
        val surface = exchange.surface

        /**
         * What the request asked for under `stream`, which the fingerprint cannot speak for:
         * `Rules.DEFAULT` ignores `/stream`, on purpose, so that one recording answers a prompt
         * however it was asked for. Resume hands over real frames and the original's own headers,
         * so here the two must agree — a `stream: false` re-issue served a buffered SSE body under
         * `text/event-stream` gets something it has no parser for, and the reverse strands a client
         * waiting for events on a single JSON frame. A mismatch is a miss and goes upstream.
         */
        val stream = exchange.request.json?.get("stream")

        /** Only what a continuation is judged by; the request itself is not kept past its end. */
        val continuable: Continuable? = exchange.normalized?.let(exchange.surface::continuable)
        val answer = FrameLog()

        /**
         * The last frame appended, which is the only thing that says whether a finished answer is
         * whole: an upstream cut short does not fail, it simply stops, so the frames that arrived
         * look like any others and only the surface's terminal frame among them tells the two
         * apart. Written by the drive alone, in frame order; read by any request coroutine.
         */
        @Volatile var last: Frame? = null

        /**
         * The response once the stream starts, or null when the exchange ended without one. A
         * re-issue that finds its original still waiting for the upstream's headers waits here.
         */
        val started = CompletableDeferred<Exchange.Response?>()

        /** When the chain heard the client go, which is when the window starts. */
        @Volatile var goneAt: ComparableTimeMark? = null

        /**
         * Whether this answer may still be offered. One that is still streaming may: a joiner rides
         * along and shares whatever fate the original meets, cut where the original was cut, which
         * is the stream the provider would have given the client itself. One that has ended may
         * only if it ended whole — its surface's terminal frame arrived and nothing failed —
         * because there a complete answer was there to serve, and a broken one put in its place
         * would cost the client the retry it made (#26, ADR 0002).
         */
        fun servable(): Boolean =
            !answer.done || (!answer.failed && last?.let(surface::terminates) == true)

        /**
         * Inside its [window] once the chain has heard it go; before that, on the engine's word.
         */
        fun clientLeft(window: Duration): Boolean =
            goneAt?.let { it.elapsedNow() <= window } ?: clientGone()

        /**
         * Whether [asked] is this request sent again: the same fingerprint, or a continuation. The
         * null check is not ceremony. The relay sets the fingerprint before any interceptor is
         * asked, so both are always set — but `null == null` here would make every request a
         * re-issue of every other, and this is the one interceptor that hands one client another
         * client's answer. Replay guards the same field the same way.
         */
        fun reissuedAs(asked: Entry, grace: Duration): Boolean {
            if (stream != asked.stream) return false
            val theirs = asked.continuable
            return (fingerprint != null && fingerprint == asked.fingerprint) ||
                (theirs != null && withinGrace(grace) && continuable?.continuedBy(theirs) == true)
        }

        /**
         * Whether a continuation may still be recognised. A continuation is a looser match than an
         * equal fingerprint — an appended text block of any content passes it — so it is bounded by
         * the thing that actually distinguishes a client's automatic re-issue from a person typing:
         * time. The clock runs from when the chain heard the client go, which for a mid-stream drop
         * is the moment the frames stop, since the writer looks at its channel before every wait.
         * An exchange the chain has not yet heard leave is matched exactly or not at all; that only
         * happens while an upstream is silent, which is the drop the spike measured as re-issued
         * byte for byte anyway.
         */
        private fun withinGrace(grace: Duration): Boolean =
            goneAt?.let { it.elapsedNow() <= grace } == true
    }

    /** Every exchange between its request and its completion, for its own hooks to find. */
    private val inFlight = ConcurrentHashMap<String, Entry>()

    /**
     * What a re-issue may still claim: every exchange in flight, since its client may go at any
     * frame, and those that completed after their client went, until a claim, the window or the cap
     * takes them out.
     */
    private val claimable = ConcurrentHashMap<String, Entry>()

    override suspend fun onRequest(exchange: Exchange): FrameSource? {
        if (!enabled || exchange.mode == Mode.REPLAY) return null
        val asked = Entry(exchange)
        evict()
        val original = claim(asked)
        // Tracked whether or not it was answered from the buffer: a resumed exchange whose own
        // client goes is the next one to be resumed.
        inFlight[exchange.id] = asked
        claimable[exchange.id] = asked
        // A claim made on the engine's word can be ahead of the upstream's first byte: wait for
        // the response it is about to have, or for the word that it never had one.
        val response = original.awaited()
        return if (original == null || response == null) null
        else served(exchange, original, response)
    }

    /**
     * The response this claimed exchange is about to have, or null when it never had one.
     *
     * A claim is spent only when it is served, so a joiner that dies while parked here puts the
     * answer back. The window it covers is real: a claim made on the engine's word can land before
     * the upstream's first byte, and a client whose connection flaps again while it waits would
     * otherwise take a whole buffered answer down with it and leave the next re-issue to pay for
     * one.
     *
     * Only a live one goes back, and [live] is the whole of why: the original can end while the
     * joiner is parked, and a joiner woken by that completion can still be cancelled before it
     * reads the result. Its completion has already decided whether the answer is worth keeping —
     * and for a client that stayed, the answer is no — so putting it back there would resurrect an
     * exchange nothing will ever remove again. Nothing would: `onComplete` has run, and eviction
     * counts only entries whose client went, which that one's never did.
     */
    private suspend fun Entry?.awaited(): Exchange.Response? =
        try {
            this?.started?.await()?.takeIf { it.worthServing() }
        } catch (e: CancellationException) {
            this?.takeIf { it.live() && it.servable() }?.let { claimable.putIfAbsent(it.id, it) }
            throw e
        }

    /**
     * Whether this entry is still one the rest of the chain will account for: in flight, so its
     * completion is still to come, or already past its departure, so the window and the cap can
     * reach it. An entry that is neither has been settled and must not come back.
     */
    private fun Entry.live(): Boolean = inFlight.containsKey(id) || goneAt != null

    /**
     * The buffered answer as a source. Only the [FrameLog] is captured, so what recognised the
     * original — its normalized shape, its continuation blocks — is free to go when it ends.
     */
    private fun served(
        exchange: Exchange,
        original: Entry,
        response: Exchange.Response,
    ): FrameSource {
        log.info("exchange {} resumed from the answer to {}", exchange.id, original.id)
        exchange.resumed = true
        val answer = original.answer
        return object : FrameSource {
            override val status = response.status
            override val headers = response.headers

            override fun frames() = answer.frames()
        }
    }

    /**
     * The tap: every frame into the log before it goes anywhere else, and the log ended the way the
     * stream ended, so a reader of it is never left waiting on a stream that broke.
     */
    override fun onFrames(exchange: Exchange, frames: Flow<Frame>): Flow<Frame> {
        val entry = inFlight[exchange.id] ?: return frames
        return frames
            .onStart { entry.started.complete(exchange.response) }
            .onEach {
                entry.answer.append(it)
                entry.last = it
            }
            .onCompletion { failure -> entry.answer.end(failure) }
    }

    /**
     * The window starts here, when the chain hears the client go, and not at the request or the
     * completion: a re-issue follows the drop, so that is the instant its clock runs from, and an
     * answer that streams for longer than the window must not expire before it was ever dropped.
     */
    override suspend fun onClientGone(exchange: Exchange) {
        val entry = inFlight[exchange.id] ?: return
        if (exchange.response?.worthServing() == true) entry.goneAt = TimeSource.Monotonic.markNow()
        else claimable.remove(entry.id, entry)
        evict()
    }

    /**
     * An exchange whose client stayed to the end has nothing to resume, and one that ended short
     * has nothing worth resuming. Ending the log and the wait here as well is what frees a re-issue
     * that claimed an exchange which then never streamed: refused, unreachable, or stopped.
     */
    override suspend fun onComplete(exchange: Exchange, outcome: Outcome) {
        val entry = inFlight.remove(exchange.id) ?: return
        entry.started.complete(null)
        entry.answer.end(IOException("exchange ${entry.id} ended before its stream did"))
        if (entry.goneAt == null || !entry.servable()) claimable.remove(entry.id, entry)
    }

    /**
     * The exchange [asked] re-issues, taken out of [claimable] so nobody else is served it, or
     * null. An equal fingerprint is preferred to a continuation, and losing the `remove` to another
     * re-issue moves on to the next candidate.
     */
    private fun claim(asked: Entry): Entry? =
        claimable.values
            .filter { it.clientLeft(window) && it.servable() && it.reissuedAs(asked, grace) }
            .sortedByDescending { it.fingerprint == asked.fingerprint }
            .firstOrNull { claimable.remove(it.id, it) }

    /**
     * Past the window by age, then past the cap oldest-first, both counted from when the client
     * went. Only answers whose client has gone count against the cap; an exchange in flight whose
     * client is still there is not buffered for anyone yet. An evicted exchange that is still in
     * flight goes on being read and recorded as before: only the offer of it is withdrawn.
     */
    private fun evict() {
        claimable.values.removeIf { entry ->
            entry.goneAt?.let { it.elapsedNow() > window } == true
        }
        claimable.values
            .filter { it.goneAt != null }
            .sortedBy { it.goneAt }
            .dropLast(capacity)
            .forEach { claimable.remove(it.id, it) }
    }

    /** Only a success is an answer anyone paid for; a 429 served again would only be obeyed. */
    private fun Exchange.Response.worthServing(): Boolean =
        HttpStatusCode.fromValue(status).isSuccess()
}

/** Any TOML number of seconds that is not negative; zero is how the file turns resume off. */
internal fun TomlParseResult.resumeWindow(default: Duration): Duration =
    when (val raw = get("resume.windowSeconds")) {
        null -> default
        is Number -> {
            check(raw.toDouble() >= 0) { "resume.windowSeconds must not be negative, not $raw" }
            raw.toDouble().seconds
        }
        else -> error("resume.windowSeconds must be a number, not $raw")
    }

/**
 * A whole number that is not negative. Read as whatever TOML parsed and then checked, because
 * tomlj's typed getters throw their own message for `100.5`, and it names no key.
 */
internal fun TomlParseResult.maxBufferedExchanges(default: Int): Int =
    when (val raw = get("resume.maxBufferedExchanges")) {
        null -> default
        is Long -> {
            check(raw in 0..Int.MAX_VALUE) {
                "resume.maxBufferedExchanges must be 0 to ${Int.MAX_VALUE}, not $raw"
            }
            raw.toInt()
        }
        else -> error("resume.maxBufferedExchanges must be a whole number, not $raw")
    }
