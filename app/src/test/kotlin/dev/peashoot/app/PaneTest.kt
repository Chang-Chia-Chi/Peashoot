package dev.peashoot.app

import dev.peashoot.app.render.Hit
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val MINE = "sess-mine"
private const val THEIRS = "sess-theirs"

/** Far enough apart that newest-first is an order and not a coin toss. */
private val START = Instant.parse("2026-09-20T09:00:00Z")

/**
 * Issue #22's first criterion at the seam `docs/spec.md` names: a real proxy, a real store, the
 * real control API over a real socket, and the window's own `PaneModel` asking it.
 *
 * `runBlocking` by way of [withTestProxy], like its neighbours: every wait here is on a socket,
 * where a virtual clock would fire the timeouts before the bytes arrived.
 */
class PaneTest {
    @Test
    fun `a villager's timeline is the exchanges endpoint's rows for that session and no other's`() =
        withTestProxy { proxy ->
            val model = AppModel(home = proxy.home, port = proxy.port)
            coroutineScope {
                val watching = launch { model.watch() }
                until { model.status.startsWith("connected") }
                // Two sessions, interleaved, so that "this session's" is a real filter and not
                // simply everything the store has.
                proxy.record(MINE, "01EX01", START)
                proxy.record(THEIRS, "01EX02", START.plusSeconds(1))
                proxy.record(MINE, "01EX03", START.plusSeconds(2))
                proxy.record(THEIRS, "01EX04", START.plusSeconds(3))
                until { model.farm.villagers.containsKey(MINE) }

                model.panes.select(Hit.OnVillager(MINE))
                until { model.panes.rows.isNotEmpty() }

                // The criterion, asserted against the endpoint itself rather than against a
                // literal: the pane's rows are its rows, in its order.
                val endpoint = exchangeIds(proxy.url, proxy.token, MINE)
                assertEquals(listOf("01EX03", "01EX01"), endpoint, "newest first, as the API lists")
                assertEquals(endpoint, model.panes.rows.map { it.id })
                assertTrue(
                    model.panes.rows.none { it.id == "01EX02" || it.id == "01EX04" },
                    "the other session's exchanges are not in this villager's timeline",
                )
                // The feed carried each of these, so the rows say what a summary row cannot.
                val row = model.panes.rows.first()
                assertEquals("claude-opus-4-1", row.model)
                assertEquals("120 in, 340 out, 0 cached, 0 written", row.usage)
                assertEquals(0.25, row.costUsd)
                assertEquals(1500L, row.latencyMs)
                assertNull(model.panes.note, "a timeline that loaded says nothing about itself")

                // Clicking a crop is a different pane over the same model: the rows go with it.
                model.panes.select(Hit.OnCrop("src/main/App.kt"))
                assertTrue(model.panes.rows.isEmpty())
                model.panes.dismiss()
                assertNull(model.panes.selected)
                watching.cancel()
            }
        }

    @Test
    fun `an exchange from before the window connected shows the endpoint's half of the row`() =
        withTestProxy { proxy ->
            // Stored and never published: exactly what a proxy that has been running all day has,
            // since the window's first feed connection asks for no backfill.
            proxy.record(MINE, "01EX09", START, announce = false)
            val model = AppModel(home = proxy.home, port = proxy.port)
            coroutineScope {
                val watching = launch { model.watch() }
                until { model.status.startsWith("connected") }
                model.panes.select(Hit.OnVillager(MINE))
                until { model.panes.rows.isNotEmpty() }
                val row = model.panes.rows.single()
                assertEquals("01EX09", row.id)
                assertEquals(START.toString(), row.at)
                // Usage, cost and latency live on the event line alone, and this window heard none.
                assertNull(row.model)
                assertNull(row.usage)
                assertNull(row.costUsd)
                watching.cancel()
            }
        }

    @Test
    fun `a body is refused while paths are hidden, and read on demand once they are shown`() =
        withTestProxy { proxy ->
            proxy.record(MINE, "01EX11", START, announce = false)
            val model = AppModel(home = proxy.home, port = proxy.port)
            coroutineScope {
                val watching = launch { model.watch() }
                until { model.status.startsWith("connected") }
                model.panes.select(Hit.OnVillager(MINE))
                until { model.panes.rows.isNotEmpty() }
                // Labels are hidden until something turns them on, and a body is nothing but paths.
                assertTrue(model.farm.labelsHidden)
                model.panes.showBody("01EX11", hidden = true)
                assertNull(model.panes.body)
                model.showPaths(true)
                model.panes.showBody("01EX11", hidden = model.farm.labelsHidden)
                until { model.panes.body != null }
                assertContains(model.panes.body.orEmpty(), "claude-opus-4-1")
                // Turning the toggle back off drops it. Ceasing to draw it would not be enough:
                // the pane would be one click from putting every path in it back on screen with
                // the badge gone, which is the screenshot the toggle exists to prevent.
                model.showPaths(false)
                assertNull(model.panes.body, "the toggle going off drops the body being read")
                model.panes.hideBody()
                assertNull(model.panes.body, "only the body being looked at is held")
                watching.cancel()
            }
        }

    @Test
    fun `an exchange the proxy does not have leaves its reason in the pane, not an exception`() =
        withTestProxy { proxy ->
            val model = AppModel(home = proxy.home, port = proxy.port)
            coroutineScope {
                val watching = launch { model.watch() }
                until { model.status.startsWith("connected") }
                model.showPaths(true)
                model.panes.showBody("01NOSUCHEXCHANGE", hidden = false)
                until { model.panes.note?.contains("could not be read") == true }
                assertNull(model.panes.body)
                // A session with nothing in it is an empty timeline and a line saying so — and
                // that line is only ever said about an answer this window actually read.
                model.panes.select(Hit.OnVillager("sess-nobody"))
                until { model.panes.note?.contains("no exchanges") == true }
                assertTrue(model.panes.rows.isEmpty())
                watching.cancel()
            }
        }
}

/** What `GET /exchanges?session=` lists, ids only: the proxy's own answer, over the wire. */
private suspend fun exchangeIds(url: String, token: String, session: String): List<String> =
    HttpClient(CIO).use { client ->
        val body =
            client
                .get("$url/_peashoot/v1/exchanges?session=$session") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                .bodyAsText()
        Json.parseToJsonElement(body).jsonObject.getValue("exchanges").jsonArray.map {
            it.jsonObject.getValue("id").jsonPrimitive.content
        }
    }
