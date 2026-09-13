package dev.peashoot.proxy

import dev.peashoot.core.Exchange
import dev.peashoot.core.Frame
import dev.peashoot.core.Interceptor
import dev.peashoot.core.Mode
import dev.peashoot.core.Outcome
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import org.jdbi.v3.core.JdbiException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

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

    /**
     * A persist that started finishes, and a store that cannot write costs this recording only:
     * never the client's response, never the rest of the chain.
     */
    private suspend fun persist(exchange: Exchange) {
        val frames = buffers.remove(exchange.id) ?: return
        withContext(NonCancellable) {
            try {
                store.put(exchange, frames)
            } catch (e: IOException) {
                dropped(exchange, e)
            } catch (e: JdbiException) {
                dropped(exchange, e)
            }
        }
    }

    private fun dropped(exchange: Exchange, cause: Exception) =
        log.warn("exchange {} not recorded: {}", exchange.id, cause.toString())
}
