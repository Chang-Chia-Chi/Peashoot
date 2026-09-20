package dev.peashoot.proxy

import dev.peashoot.core.text
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * `GET /touches`: which exchanges touched one path, the crop pane's question (#22), answered from
 * the event table so it reaches back further than any window's own lifetime (#85).
 */
class ControlTouchesTest {
    @Test
    fun `touches name the turns that touched one path, newest first, and page by cursor`() =
        withProxy {
            upstream.reply = { streamReply(FIXTURE) }
            relay(REQUEST, mapOf("x-claude-code-session-id" to "s1"))
            relay(
                REQUEST,
                mapOf("x-claude-code-session-id" to "s2", "x-claude-code-agent-id" to "a"),
            )
            awaitEvents(4)

            val page = touches("/touches?path=$TOUCHED")
            assertEquals(listOf("s2", "s1"), page.map { it["session"].text() }, "newest first")
            val newest = page.first()
            assertEquals("a", newest["agent"].text())
            // The fixture's turn reads the file and then edits it: two touches, one exchange.
            assertEquals(
                listOf("Read", "Edit"),
                newest.getValue("tools").jsonArray.map { it.text() },
            )
            assertContains(
                store.events().values.map { it["exchangeId"].text() },
                newest["exchangeId"].text(),
            )
            // The event line's own `ts`, so the pane and the feed spell one turn's time alike.
            assertTrue(newest["ts"].text().orEmpty().endsWith("Z"), "$newest")

            // A page is bounded and says so the way `/exchanges` does.
            val first = json("/touches?path=$TOUCHED&limit=1")
            assertEquals(1, first.getValue("touches").jsonArray.size)
            val cursor = first.getValue("nextCursor").jsonPrimitive.long
            val second = touches("/touches?path=$TOUCHED&limit=1&cursor=$cursor")
            assertEquals(listOf("s1"), second.map { it["session"].text() })
            assertNull(json("/touches?path=$TOUCHED&limit=1&cursor=$cursor")["nextCursor"].text())

            // A path nothing touched is an empty history, not a failure.
            assertEquals(emptyList(), touches("/touches?path=src/Other.kt"))
        }

    @Test
    fun `a replay hit's touches are there, though its exchange was never stored`() = withProxy {
        upstream.reply = { streamReply(FIXTURE) }
        relay(REQUEST, mapOf("x-claude-code-session-id" to "s1"))
        awaitRecordings(1)
        awaitEvents(2)
        assertEquals(
            200,
            call(HttpMethod.Put, "/routes/default", """{"mode":"replay"}""").status.value,
        )
        relay(REQUEST, mapOf("x-claude-code-session-id" to "s1"))
        awaitEvents(4)

        assertEquals(1, upstream.received.size, "the second turn was served from the recording")
        assertEquals(1, store.list().size, "a replay hit is not a row")
        // Which is exactly why this endpoint reads the event table: the touch happened twice.
        assertEquals(2, touches("/touches?path=$TOUCHED").size)
    }

    @Test
    fun `a path that cannot be used is a 400, and a hostile one is never echoed back`() =
        withProxy {
            listOf("", "%20", "x".repeat(MAX_PATH_LENGTH + 1)).forEach {
                assertProblem(call(HttpMethod.Get, "/touches?path=$it"), 400)
            }
            assertContains(
                assertProblem(call(HttpMethod.Get, "/touches"), 400),
                "path",
                message = "a call with no path is told what it is missing",
            )
            listOf("0", "-1", "ten").forEach {
                assertProblem(call(HttpMethod.Get, "/touches?path=a&cursor=$it"), 400)
            }
            listOf("0", "${MAX_EXCHANGES_LIMIT + 1}").forEach {
                assertProblem(call(HttpMethod.Get, "/touches?path=a&limit=$it"), 400)
            }
            // A path is a client's own text. It is bound into the query and never written into
            // the answer, so nothing a client sent can come back out of this endpoint.
            val hostile = "a%27%20OR%201%3D1%20--%3Cscript%3E"
            val body = call(HttpMethod.Get, "/touches?path=$hostile").bodyAsText()
            assertEquals("""{"touches":[],"nextCursor":null}""", body)
            assertFalse("script" in body, body)
            assertEquals(0, upstream.received.size)
        }

    /** The rows of a touch page that must have worked. */
    private suspend fun Proxy.touches(path: String): List<JsonObject> =
        json(path).getValue("touches").jsonArray.map { it.jsonObject }

    private companion object {
        const val REQUEST = """{"model":"claude-sonnet-4-5","messages":[]}"""
        const val FIXTURE = "stream-with-file-tools.sse"
        /** What that fixture's turn reads and then edits. */
        const val TOUCHED = "src/Main.kt"
    }
}
