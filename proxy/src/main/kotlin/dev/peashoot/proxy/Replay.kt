package dev.peashoot.proxy

import dev.peashoot.core.Exchange
import dev.peashoot.core.FrameSource
import dev.peashoot.core.Interceptor
import dev.peashoot.core.Mode
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import org.jdbi.v3.core.JdbiException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

/**
 * A replay route answers from the store: a hit serves the recording byte for byte and never asks
 * the upstream. A miss on a strict route is refused with 409, naming what missed; on a lenient one
 * it is let through, and the recorder appends what the upstream says.
 */
class Replay(private val store: Store, private val config: ProxyConfig) : Interceptor {
    /**
     * How many hits each fingerprint has had since this proxy started, for [RepeatPolicy.IN_ORDER].
     * Held here, not in the store, so a restart replays a retry sequence from its start.
     */
    private val cursors = ConcurrentHashMap<String, AtomicInteger>()

    override suspend fun onRequest(exchange: Exchange): FrameSource? {
        val fingerprint = exchange.fingerprint
        if (exchange.mode != Mode.REPLAY || fingerprint == null) return null
        // The route as the request arrived under it, never as the table says now.
        val route = exchange.routing
        val hit = recording(fingerprint, route.cassette)
        return when {
            hit != null -> {
                exchange.replayHit = true
                replaySource(hit)
            }
            route.strict ->
                Refusal(
                    HttpStatusCode.Conflict.value,
                    "replay_miss",
                    mapOf("fingerprint" to fingerprint, "route" to exchange.route),
                )
            else -> null
        }
    }

    /**
     * The recording the policy picks, only from [cassette] when the route names one, or null for a
     * miss. A store that cannot be read is a miss too, logged, rather than a throw the chain
     * contract forbids.
     *
     * ponytail: in order reads every recording of the fingerprint to serve one. Upgrade: an OFFSET
     * query on the fingerprint index, if a cassette ever repeats one request hundreds of times.
     */
    private suspend fun recording(fingerprint: String, cassette: String?): Recorded? =
        try {
            val query = ExchangeQuery(fingerprint = fingerprint, cassette = cassette)
            when (config.repeatPolicy) {
                RepeatPolicy.LATEST -> store.list(limit = 1, query).firstOrNull()
                RepeatPolicy.IN_ORDER -> {
                    val oldestFirst = store.list(Int.MAX_VALUE, query).asReversed()
                    // Advanced only on a hit, so a lenient miss leaves the new recording first.
                    if (oldestFirst.isEmpty()) null
                    else {
                        val served = cursors.computeIfAbsent(fingerprint) { AtomicInteger() }
                        oldestFirst[served.getAndIncrement().coerceAtMost(oldestFirst.lastIndex)]
                    }
                }
            }
        } catch (e: JdbiException) {
            log.warn("replay lookup failed for {}: {}", fingerprint, e.toString())
            null
        } catch (e: IOException) {
            log.warn("replay lookup failed for {}: {}", fingerprint, e.toString())
            null
        }

    /** The recorded response, instantly or with each frame held to its recorded offset. */
    private fun replaySource(recorded: Recorded): FrameSource {
        val response = checkNotNull(recorded.exchange.response) { "a stored exchange has one" }
        val paced = config.replayCadence == Cadence.RECORDED
        return object : FrameSource {
            override val status = response.status
            override val headers = response.headers

            override fun frames() = flow {
                var previous = 0L
                recorded.frames.forEach { frame ->
                    if (paced) delay(frame.offsetMillis - previous)
                    previous = frame.offsetMillis
                    emit(frame)
                }
            }
        }
    }
}
