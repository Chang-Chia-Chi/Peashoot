package dev.peashoot.core

import kotlinx.coroutines.flow.Flow

/**
 * One link of the chain every exchange passes through, in order (Resume, Replay, Recorder, Deriver
 * in v1). Every hook defaults to doing nothing, so an interceptor overrides only what it needs.
 */
interface Interceptor {
    /** Continue down the chain, or answer from a source of your own and skip the upstream. */
    suspend fun onRequest(exchange: Exchange): Decision = Decision.Continue

    /** Observe or transform the frames on their way to the sinks. */
    fun onFrames(exchange: Exchange, frames: Flow<Frame>): Flow<Frame> = frames

    suspend fun onComplete(exchange: Exchange, outcome: Outcome) = Unit

    suspend fun onClientGone(exchange: Exchange) = Unit
}

sealed interface Decision {
    data object Continue : Decision

    class Respond(val source: FrameSource) : Decision
}

/** Where a response comes from: the upstream, a cassette, or a resume buffer. */
interface FrameSource {
    val status: Int
    val headers: Map<String, List<String>>

    fun frames(): Flow<Frame>
}

/**
 * What the chain learns when a response completes. Usage and stop reason arrive with the deriver.
 */
data class Outcome(val status: Int)
