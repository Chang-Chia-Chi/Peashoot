package dev.peashoot.proxy

import dev.peashoot.core.Exchange
import dev.peashoot.core.Frame
import dev.peashoot.core.Interceptor
import dev.peashoot.core.Mode
import dev.peashoot.core.Outcome
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/** Record mode persists every exchange when it completes; the other modes persist nothing here. */
class Recorder(private val store: Store) : Interceptor {
    /** Frames so far, per exchange. Only the exchange's own coroutine touches its list. */
    private val buffers = ConcurrentHashMap<String, MutableList<Frame>>()

    override fun onFrames(exchange: Exchange, frames: Flow<Frame>): Flow<Frame> =
        if (exchange.mode != Mode.RECORD) frames
        else frames.onEach { buffers.getOrPut(exchange.id) { mutableListOf() } += it }

    override suspend fun onComplete(exchange: Exchange, outcome: Outcome) {
        if (exchange.mode == Mode.RECORD) store.put(exchange, buffers.remove(exchange.id).orEmpty())
    }
}
