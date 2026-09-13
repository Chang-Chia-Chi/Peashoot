package dev.peashoot.core

import io.ktor.http.Headers
import kotlinx.coroutines.flow.Flow

/**
 * One link of the chain every exchange passes through, in order (Resume, Replay, Recorder, Deriver
 * in v1). Every hook defaults to doing nothing, so an interceptor overrides only what it needs. All
 * interceptors wrap the one flow the client writer collects, so a recorder and a deriver see every
 * frame once, with no second parse and no second read of the upstream.
 */
interface Interceptor {
    /**
     * Answer from a source of your own and skip the upstream, or null to offer nothing and let the
     * chain continue.
     */
    suspend fun onRequest(exchange: Exchange): FrameSource? = null

    /**
     * Observe or transform the frames on their way to the sinks. The returned flow must collect
     * [frames] exactly once and stay unbuffered: the source is cold and single-use, and the
     * client's pace is the upstream's pace.
     */
    fun onFrames(exchange: Exchange, frames: Flow<Frame>): Flow<Frame> = frames

    /**
     * Must not throw: this runs where a stream ended, so a throw here would replace the exception
     * that ended it. Catch your own failures, as the Recorder catches the store's.
     */
    suspend fun onComplete(exchange: Exchange, outcome: Outcome) = Unit

    suspend fun onClientGone(exchange: Exchange) = Unit
}

/** Where a response comes from: the upstream, a cassette, or a resume buffer. */
interface FrameSource {
    val status: Int
    val headers: Headers

    fun frames(): Flow<Frame>
}

/**
 * What the chain learns when a response completes. Usage and stop reason arrive with the deriver
 * (#8); timings come from the frames' offsets.
 */
data class Outcome(val status: Int)
