package dev.peashoot.proxy

import dev.peashoot.core.Exchange
import dev.peashoot.core.Frame
import dev.peashoot.core.Interceptor
import dev.peashoot.core.Mode
import dev.peashoot.core.Outcome
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/**
 * Record mode persists every exchange a source answered, when it ends; the other modes persist
 * nothing here, and neither does a proxy-side failure, which never had a source.
 */
class Recorder(private val store: Store) : Interceptor {
    /**
     * Frames so far, per exchange with a source. Only the exchange's own coroutine touches its
     * list.
     */
    private val buffers = ConcurrentHashMap<String, MutableList<Frame>>()

    override fun onFrames(exchange: Exchange, frames: Flow<Frame>): Flow<Frame> {
        if (exchange.mode != Mode.RECORD) return frames
        val buffer = buffers.getOrPut(exchange.id) { mutableListOf() }
        return frames.onEach { buffer += it }
    }

    override suspend fun onComplete(exchange: Exchange, outcome: Outcome) = persist(exchange)

    /** What arrived before the client left is kept, flagged. #9 keeps consuming and completes. */
    override suspend fun onClientGone(exchange: Exchange) = persist(exchange)

    private suspend fun persist(exchange: Exchange) {
        val frames = buffers.remove(exchange.id) ?: return
        store.put(exchange, frames)
    }
}
