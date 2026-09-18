package dev.peashoot.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Long enough for the proxy to write the keep-alive comments a quiet feed is mostly made of. */
private const val IDLE_MS = 700L

/**
 * The path the window actually takes, which the [ControlClientTest] cases do not: [AppModel.watch]
 * asks for no backfill at all on its first connection, so nothing there proves it resumes.
 */
class AppModelTest {
    @Test
    fun `the window's feed sits out a quiet spell and a cut, and shows each line exactly once`() =
        withTestProxy { proxy ->
            Wire(proxy.port).use { wire ->
                val model = AppModel(home = proxy.home, port = wire.port)
                coroutineScope {
                    val watching = launch { model.watch() }
                    until { model.status.startsWith("connected") }
                    val live = proxy.emit("exchange.started")
                    until { model.lines.size >= 1 }
                    // Nothing on the feed but keep-alives, which carry no id and are not lines.
                    delay(IDLE_MS)
                    assertEquals(1, model.lines.size, "keep-alives are not event lines")
                    // Cut the way a killed proxy cuts: the reconnect has only the id of the last
                    // line it delivered to resume from.
                    wire.crash()
                    val whileDown = proxy.emit("exchange.completed")
                    val afterCrash = proxy.emit("exchange.started")
                    until { model.lines.size >= 3 }
                    assertEquals(3, model.lines.size, "no line twice")
                    // Newest first, as the window lists them.
                    assertEquals(
                        listOf(afterCrash, whileDown, live),
                        model.lines.map { it.feedId() },
                    )
                    // Health kept being polled through all of it.
                    assertEquals(mapOf("default" to "record"), model.health?.routes)
                    watching.cancel()
                }
            }
        }

    @Test
    fun `an event line shaped unlike ours is shown blank, and the feed carries on`() =
        withTestProxy { proxy ->
            val model = AppModel(home = proxy.home, port = proxy.port)
            coroutineScope {
                val watching = launch { model.watch() }
                until { model.status.startsWith("connected") }
                val normal = proxy.emit("exchange.completed")
                until { model.lines.size >= 1 }
                // A later proxy putting an object where this one puts a name. Reading it must not
                // throw out of the collector: that would take the whole feed down with it. The id
                // follows the one before, because the feed skips anything that does not.
                val strange = normal + 1
                proxy.publish(
                    strange,
                    buildJsonObject {
                        put("ts", "2026-09-19T00:00:00Z")
                        put("event", buildJsonObject { put("name", "exchange.started") })
                        put("exchangeId", "01ABC")
                    },
                )
                until { model.lines.size >= 2 }
                assertEquals(listOf(strange, normal), model.lines.map { it.feedId() })
                // Shown, with the field that is not a name left blank rather than printed raw.
                assertContains(model.lines.first(), "01ABC")
                assertFalse(model.lines.first().contains("exchange.started"), model.lines.first())
                watching.cancel()
            }
        }

    @Test
    fun `watching ends when the feed has, rather than hanging on the health poll`() =
        withTestProxy { proxy ->
            // No token where this app looks, so the feed ends at once and for good.
            val homeless = Files.createTempDirectory("peashoot-no-token")
            val model = AppModel(home = homeless, port = proxy.port)
            withTimeout(PATIENCE) { model.watch() }
            assertContains(model.status, "token")
        }
}

/** The feed id off the front of a row, which is how the window prints it. */
private fun String.feedId(): Long = substringBefore("  ").toLong()
