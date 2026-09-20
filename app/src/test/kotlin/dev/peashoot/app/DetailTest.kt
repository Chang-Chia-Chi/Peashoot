package dev.peashoot.app

import dev.peashoot.app.farm.Touch
import dev.peashoot.app.farm.TouchKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private const val A_PATH = "src/main/kotlin/dev/peashoot/app/ControlClient.kt"

/** Two summary rows as `GET /exchanges?session=` serves them, newest first, as it orders them. */
private const val TWO_ROWS =
    """{"exchanges":[
        {"id":"01EX02","receivedAt":"2026-09-20T09:00:20Z","route":"default","mode":"record",
         "method":"POST","path":"/v1/messages","status":200,"fingerprint":"fp2",
         "session":"sess-one","agent":"agent-two","client":"claude-code","cassette":null,
         "clientDisconnected":true},
        {"id":"01EX01","receivedAt":"2026-09-20T09:00:10Z","route":"default","mode":"record",
         "method":"POST","path":"/v1/messages","status":200,"fingerprint":"fp1",
         "session":"sess-one","agent":null,"client":"claude-code","cassette":null,
         "clientDisconnected":false}],"nextCursor":null}"""

/** The completed line for the older of them, as the Deriver writes one. */
private const val A_LINE =
    """{"ts":"2026-09-20T09:00:14Z","event":"exchange.completed","exchangeId":"01EX01",
        "session":"sess-one","agent":null,"model":"claude-opus-4-1",
        "tools":[{"name":"Edit","path":"src/main/App.kt"},{"name":"Bash","command":"ls"}],
        "usage":{"input":120,"output":340,"cacheRead":8,"cacheWrite":0},"costUsd":0.25,
        "status":200,"latencyMs":1500,"replayHit":true,"clientDisconnected":false}"""

/**
 * The panes' pure half: an endpoint answer in, rows out, and what a pane is allowed to write where
 * a path would go. No window, no socket — the seam `docs/spec.md` names, for the detail panes.
 */
class DetailTest {
    @Test
    fun `a row is the endpoint's, with the event line filling in what a summary cannot carry`() {
        val heard = mapOf("01EX01" to json(A_LINE))
        val rows = checkNotNull(exchangeRows(TWO_ROWS, heard::get))
        // The endpoint decides which rows there are and in what order; the feed only fills in.
        assertEquals(listOf("01EX02", "01EX01"), rows.map { it.id })
        val filled = rows.last()
        assertEquals("2026-09-20T09:00:10Z", filled.at)
        assertEquals("claude-opus-4-1", filled.model)
        assertEquals("120 in, 340 out, 8 cached, 0 written", filled.usage)
        assertEquals(0.25, filled.costUsd)
        assertEquals(1500L, filled.latencyMs)
        assertTrue(filled.replayHit)
        // Nothing emits `resumed` until resume (#26), so an absent flag reads as not resumed.
        assertFalse(filled.resumed)
        // A tool that named no path contributes none.
        assertEquals(listOf("src/main/App.kt"), filled.paths)
    }

    @Test
    fun `an exchange the window never heard shows what the endpoint knows and no more`() {
        val bare = checkNotNull(exchangeRows(TWO_ROWS) { null }).first()
        assertEquals("01EX02", bare.id)
        assertEquals("agent-two", bare.agent)
        assertEquals(200, bare.status)
        // The store's own flag, which is on the summary row and needs no line.
        assertTrue(bare.clientDisconnected)
        assertNull(bare.model)
        assertNull(bare.usage)
        assertNull(bare.costUsd)
        assertNull(bare.latencyMs)
        assertFalse(bare.replayHit)
        assertTrue(bare.paths.isEmpty())
    }

    @Test
    fun `an unreadable answer is told apart from a session that has done nothing`() {
        // Null, not empty: the pane says "this session has done nothing" for an empty list, and
        // saying that about a truncated answer would be the one wrong thing to tell someone.
        assertNull(exchangeRows("not json at all") { null })
        // `exchanges` of another shape, or missing altogether, is not the endpoint answering.
        assertNull(exchangeRows("""{"exchanges":"a string"}""") { null })
        assertNull(exchangeRows("""{"nextCursor":null}""") { null })
        // A well-formed answer with an empty list really is a session with nothing in it.
        assertEquals(emptyList(), exchangeRows("""{"exchanges":[],"nextCursor":null}""") { null })
    }

    @Test
    fun `rows naming no id are dropped, because the list is drawn keyed by it`() {
        // Two rows keyed on the same blank id is an IllegalArgumentException out of LazyColumn,
        // which is a window falling over — the one thing these panes exist not to do.
        val nameless =
            """{"exchanges":[{"receivedAt":"2026-09-20T09:00:20Z"},
                {"receivedAt":"2026-09-20T09:00:10Z"},
                {"id":"01EX07","receivedAt":"2026-09-20T09:00:00Z"}]}"""
        assertEquals(listOf("01EX07"), checkNotNull(exchangeRows(nameless) { null }).map { it.id })
    }

    @Test
    fun `a path in a pane obeys the show-paths toggle, and says the same thing every time`() {
        assertEquals(A_PATH, pathLabel(A_PATH, hidden = false))
        val stood = pathLabel(A_PATH, hidden = true)
        assertFalse(stood.contains("ControlClient"), stood)
        assertFalse(stood.contains("/"), stood)
        // Stable, so one file reads the same in the timeline, in the touch history and in a still.
        assertEquals(stood, pathLabel(A_PATH, hidden = true))
        // And two files do not read alike, or the pane would be saying nothing.
        assertTrue(stood != pathLabel("src/main/App.kt", hidden = true))
    }

    @Test
    fun `a touch names the villager and the time, and admits a line that carried none`() {
        assertEquals(
            "2026-09-20T09:00:05Z  Clover  planted",
            touchText(Touch("sess-x", "2026-09-20T09:00:05Z", TouchKind.PLANTED), "Clover"),
        )
        assertContains(touchText(Touch("sess-x", null, TouchKind.GROWN), "Ada"), "no time given")
    }

    @Test
    fun `a body too long to show says how much of it there was`() {
        val small = bodyText("""{"requestBody":"hello"}""")
        assertEquals("hello", small)
        val huge = "x".repeat(200_000)
        val capped = bodyText("""{"requestBody":"$huge"}""")
        assertTrue(capped.length < huge.length, "the viewer holds a cap's worth, not the body")
        assertContains(capped, "200000 bytes")
        // An answer that is not an exchange says so rather than throwing at the window.
        assertContains(bodyText("{"), "cannot read")
    }
}

private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
