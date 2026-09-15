package dev.peashoot.core

import io.ktor.http.Headers
import kotlinx.coroutines.flow.Flow

/**
 * One link of the chain every exchange passes through, in order (Resume, Replay, Recorder, Deriver
 * in v1). Every hook defaults to doing nothing, so an interceptor overrides only what it needs. All
 * interceptors wrap the one flow the exchange's drive collects, so a recorder and a deriver see
 * every frame once, with no second parse and no second read of the upstream.
 */
interface Interceptor {
    /**
     * Answer from a source of your own and skip the upstream, or null to offer nothing and let the
     * chain continue.
     */
    suspend fun onRequest(exchange: Exchange): FrameSource? = null

    /**
     * Observe or transform the frames on their way to the sinks. The returned flow must collect
     * [frames] exactly once and stay unbuffered: the source is cold and single-use. It is collected
     * once, on the exchange's drive coroutine, and it keeps being collected after the client
     * leaves; [onComplete] runs once, last. Must not throw while wrapping: this runs before the
     * stream starts, so a throw here means no stream and no completion. A failure inside the
     * returned flow is caught, and the exchange still completes.
     */
    fun onFrames(exchange: Exchange, frames: Flow<Frame>): Flow<Frame> = frames

    /**
     * Runs exactly once for every exchange the proxy answered, from a source or with a proxy
     * failure, whether or not the client stayed to hear it. Must not throw: this runs where a
     * stream ended, so a throw here would replace the exception that ended it. Catch your own
     * failures, as the Recorder catches the store's.
     */
    suspend fun onComplete(exchange: Exchange, outcome: Outcome) = Unit

    /**
     * The client left mid-stream. Fires at most once, and before [onComplete]; the frames keep
     * flowing to the other sinks. By then [Exchange.clientDisconnected] and [Exchange.clientBytes]
     * are set. Must not throw, for the same reason [onComplete] must not: this runs where the
     * client's write failed, so a throw here would replace the exception that ended it.
     */
    suspend fun onClientGone(exchange: Exchange) = Unit
}

/** Where a response comes from: the upstream, a cassette, or a resume buffer. */
interface FrameSource {
    val status: Int
    val headers: Headers

    fun frames(): Flow<Frame>
}

/**
 * What the chain learns when a response completes: the status. Usage, stop reason, and timings are
 * the deriver's, read from the frames it observed.
 */
data class Outcome(val status: Int)
