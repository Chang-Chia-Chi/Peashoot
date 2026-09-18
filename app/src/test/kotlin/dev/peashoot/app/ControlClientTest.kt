package dev.peashoot.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

/**
 * The app's seam: a real proxy on a real socket, driven over the control API exactly as the window
 * drives it. Everything asserted here is what the window would have shown.
 */
class ControlClientTest {
    @Test
    fun `health parses the version, the uptime, and the mode of every route`() =
        withTestProxy { proxy ->
            ControlClient(proxy.url, { proxy.token }).use { client ->
                val health = client.health()
                assertTrue(health.version.isNotEmpty(), "a version")
                assertTrue(health.uptimeSeconds >= 0, "an uptime")
                assertEquals(mapOf("default" to "record"), health.routes)
            }
        }

    @Test
    fun `the feed backfills what it missed and then keeps arriving live`() =
        withTestProxy { proxy ->
            // Stored before anything listens, so only a backfill can deliver it. The proxy
            // subscribes before it reads the backfill, so a backfilled line proves the
            // subscription is live: whatever is stored next has nowhere to get lost.
            val backfilled = proxy.emit("exchange.started")
            ControlClient(proxy.url, { proxy.token }).use { client ->
                coroutineScope {
                    val seen = Seen(this, client, since = 0)
                    seen.await(lines = 1)
                    val live = proxy.emit("exchange.completed")
                    seen.await(lines = 2)
                    assertEquals(listOf(backfilled, live), seen.lines.map { it.id })
                    assertEquals(
                        listOf("exchange.started", "exchange.completed"),
                        seen.lines.map { it.event.eventName() },
                    )
                    seen.stop()
                }
            }
        }

    @Test
    fun `the feed reconnects across a proxy restart and the ids continue`() =
        withTestProxy { proxy ->
            val before = proxy.emit("exchange.started")
            ControlClient(proxy.url, { proxy.token }).use { client ->
                coroutineScope {
                    val seen = Seen(this, client, since = 0)
                    seen.await(lines = 1)
                    val live = proxy.emit("exchange.completed")
                    seen.await(lines = 2)
                    proxy.stop()
                    until { seen.states.any { !it.connected } }
                    // Stored while the proxy was down: only the reconnect's backfill delivers this.
                    val whileDown = proxy.emit("exchange.client_gone")
                    proxy.restart()
                    val after = proxy.emit("exchange.completed")
                    seen.await(lines = 4)
                    // Exactly four, in order: the reconnect resumed from the last id it saw, so no
                    // line was dropped and nothing the first connection had was sent twice.
                    assertEquals(listOf(before, live, whileDown, after), seen.lines.map { it.id })
                    seen.stop()
                }
            }
        }

    @Test
    fun `a wrong token ends the feed saying so, rather than retrying forever`() =
        withTestProxy { proxy ->
            ControlClient(proxy.url, { "not-the-token" }).use { client ->
                val states = client.events().toList().filterIsInstance<Feed.State>()
                assertTrue(states.none { it.connected }, "never connected: $states")
                assertContains(states.last().detail, "401")
            }
        }

    @Test
    fun `a missing token file names the file, and the feed ends rather than guessing`() =
        withTestProxy { proxy ->
            val empty = Files.createTempDirectory("peashoot-no-token")
            val failure = assertFailsWith<IllegalStateException> { readToken(empty) }
            assertContains(failure.message.orEmpty(), empty.resolve("token").toString())
            ControlClient(proxy.url, { readToken(empty) }).use { client ->
                val states = client.events().toList().filterIsInstance<Feed.State>()
                assertContains(states.last().detail, empty.resolve("token").toString())
            }
        }
}

/**
 * What one collector heard so far. The flow is collected in the test's own scope, which
 * `runBlocking` confines to one thread, so the lists are read as they are written.
 */
private class Seen(scope: CoroutineScope, client: ControlClient, since: Long? = null) {
    val lines = mutableListOf<Feed.Line>()
    val states = mutableListOf<Feed.State>()

    private val job: Job = scope.launch {
        client.events(since).collect {
            when (it) {
                is Feed.Line -> lines += it
                is Feed.State -> states += it
            }
        }
    }

    /** Waits for that many lines, or fails rather than asserting on a half-read feed. */
    suspend fun await(lines: Int) = until { this.lines.size >= lines }

    fun stop() = job.cancel()
}
