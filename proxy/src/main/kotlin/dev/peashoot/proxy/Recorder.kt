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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import org.jdbi.v3.core.JdbiException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

/**
 * Record mode persists every exchange a source answered, when it ends, and so does a replay route
 * for a miss the upstream answered, which is how a lenient cassette grows. Passthrough persists
 * nothing here, nor does a replay hit, which is a recording already, nor a proxy-side failure,
 * which never had a source.
 */
class Recorder(private val store: Store) : Interceptor {
    /**
     * Frames so far, per exchange with a source. Only the exchange's drive coroutine touches its
     * list.
     */
    private val buffers = ConcurrentHashMap<String, MutableList<Frame>>()

    override fun onFrames(exchange: Exchange, frames: Flow<Frame>): Flow<Frame> {
        if (exchange.mode == Mode.PASSTHROUGH || exchange.replayHit) return frames
        val buffer = mutableListOf<Frame>()
        // Registered when collection starts, so a response that never began leaves no entry.
        return frames.onStart { buffers[exchange.id] = buffer }.onEach { buffer += it }
    }

    override suspend fun onComplete(exchange: Exchange, outcome: Outcome) = persist(exchange)

    /**
     * A persist that started finishes, and a store that cannot write costs this recording only:
     * never the client's response, never the rest of the chain.
     */
    private suspend fun persist(exchange: Exchange) {
        val frames = buffers.remove(exchange.id) ?: return
        withContext(NonCancellable) {
            try {
                store.put(listOf(Recorded(exchange, frames)))
            } catch (e: IOException) {
                log.warn("exchange {} not recorded: {}", exchange.id, e.toString())
            } catch (e: JdbiException) {
                log.warn("exchange {} not recorded: {}", exchange.id, e.toString())
            }
        }
    }
}
