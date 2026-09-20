package dev.peashoot.app

import dev.peashoot.proxy.ExchangeQuery
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** How often a wait that cannot go through [until] asks again. */
private const val POLL_MS = 20L

/** What the fake provider answers, and what a replay of it must answer byte for byte. */
private const val UPSTREAM_BODY = """{"id":"msg_01","content":[{"type":"text","text":"hello"}]}"""

/** A request worth recording, and nothing a real key ever went near. */
private const val A_CALL = """{"model":"claude-opus-4-1","messages":[{"role":"user"}]}"""

/** The same shape, never recorded: what a strict replay must refuse rather than relay. */
private const val UNRECORDED = """{"model":"claude-opus-4-1","messages":[{"role":"other"}]}"""

/** A candidate rule set that ignores the one field two recordings differ in. */
private const val COLLIDING = """{"keepHeaders":[],"ignorePointers":["/note"],"replace":[]}"""

/** A rule file key nothing reads, which is what the parser refuses most loudly. */
private const val NO_SUCH_RULE = """{"replacements":[]}"""

/** Long enough to match the redaction rule's `sk-` pattern, and obviously not a key. */
private const val A_SECRET = "sk-notARealKey000000000000000"

/**
 * The control plane's seam (#23): a real proxy on a real socket, with the relay in front of a fake
 * provider, driven through the same model the window draws. What is asserted here is what the proxy
 * *does* — the answer to a request through it, the rules it serves, the file on disk — and not what
 * this window believes about it.
 */
class ControlPlaneTest {
    @Test
    fun `a route switched to replay answers the next request from the store, asking no provider`() =
        Upstream(UPSTREAM_BODY).use { upstream ->
            withTestProxy(upstream.url) { proxy ->
                HttpClient(CIO).use { caller ->
                    withControl(proxy) { model, _ ->
                        until { model.routes.isNotEmpty() && !model.busy }
                        assertEquals("record", model.routes.single().mode)

                        // Record: the request reaches the provider and the recorder keeps it.
                        val live = caller.call(proxy.url, A_CALL)
                        assertEquals(HttpStatusCode.OK, live.status)
                        assertEquals(UPSTREAM_BODY, live.bodyAsText())
                        assertEquals(1, upstream.calls.get(), "record mode asks the provider")
                        // The recorder writes the row once the response is through, so a replay
                        // has something to hit. Its own wait and not [until], which takes no
                        // suspending condition and this one reads the store.
                        withTimeout(PATIENCE) {
                            while (proxy.store.list(1, ExchangeQuery(live = true)).isEmpty()) {
                                delay(POLL_MS)
                            }
                        }

                        model.setMode("default", "replay", strict = true)
                        until { !model.busy }
                        assertEquals("replay", model.routes.single().mode)
                        assertTrue(model.routes.single().strict, "strict was sent with the mode")

                        // The same request, and a different answer: the store's copy of it, with
                        // the provider's count standing still. That is the behaviour changing, not
                        // a config value being read back.
                        val replayed = caller.call(proxy.url, A_CALL)
                        assertEquals(HttpStatusCode.OK, replayed.status)
                        assertEquals(UPSTREAM_BODY, replayed.bodyAsText())
                        assertEquals(1, upstream.calls.get(), "a replay hit asks no provider")

                        // And a request nothing recorded is refused where a minute ago it would
                        // have been relayed and billed.
                        val missed = caller.call(proxy.url, UNRECORDED)
                        assertEquals(HttpStatusCode.Conflict, missed.status)
                        assertContains(missed.bodyAsText(), "replay_miss")
                        assertEquals(1, upstream.calls.get(), "a strict miss asks no provider")
                    }
                }
            }
        }

    @Test
    fun `an invalid rule set is refused in the proxy's own words, and nothing is saved`() =
        withTestProxy { proxy ->
            withControl(proxy) { model, client ->
                val rules = model.rules
                until { rules.saved != null }
                val before = served(client)

                rules.edit(NO_SUCH_RULE)
                rules.test()
                until { !rules.busy }
                // The parser's sentence, word for word: which key it will not take. The window
                // adds a sentence of its own around it and changes nothing inside it.
                assertContains(rules.note.orEmpty(), "no such rule: replacements")
                assertNull(rules.tested, "a refused test is no test")
                assertFalse(rules.mayTry, "an untested draft is not offered to save")

                // Pressing save anyway — which the window does not offer — writes nothing.
                rules.save()
                until { !rules.busy }
                assertEquals("test this draft before saving it", rules.note)
                assertEquals(before, served(client), "the proxy still serves what it served")

                // The same refusal is what a `PUT` of it would answer, since one parser reads
                // both: [Refused] carries the status and the proxy's detail, unedited.
                val refused = client.send(HttpMethod.Put, "/rules", NO_SUCH_RULE).exceptionOrNull()
                val said = assertIs<Refused>(refused)
                assertEquals(HttpStatusCode.BadRequest.value, said.status)
                assertContains(said.detail, "no such rule: replacements")
                assertEquals(before, served(client))
            }
        }

    @Test
    fun `a draft that would collide two recordings shows the collision before anything is saved`() =
        withTestProxy { proxy ->
            proxy.recorded("01COLLIDEA", """{"model":"m","note":"one"}""")
            proxy.recorded("01COLLIDEB", """{"model":"m","note":"two"}""")
            withControl(proxy) { model, client ->
                val rules = model.rules
                until { rules.saved != null }
                val before = served(client)

                rules.edit(COLLIDING)
                rules.test()
                until { !rules.busy && rules.tested != null }
                assertContains(rules.outcome, "2 recordings tested")
                val collision = rules.outcome.single { it.startsWith("collision") }
                assertContains(collision, "01COLLIDEA")
                assertContains(collision, "01COLLIDEB")
                assertTrue(rules.risky, "a collision is a reason to ask again")
                // Tested, and still not saved: this is the whole point of the view.
                assertEquals(before, served(client))

                // Pressing save without ticking the box writes nothing and says why.
                rules.save()
                until { !rules.busy }
                assertContains(rules.note.orEmpty(), "tick the box")
                assertEquals(before, served(client), "a collision is confirmed before it is saved")

                rules.confirm(true)
                rules.save()
                until { !rules.busy && rules.saved != before }
                assertEquals(rules.saved, served(client), "the proxy matches on the draft now")
                assertFalse(rules.confirming, "a saved draft is not still waiting to be confirmed")

                // Saving rules changes what an export of the same rows would strip, so a preview
                // taken before it is not a preview of what would be written now.
                val export = model.export
                export.select("demo", "")
                export.dryRun()
                until { !export.busy && export.mayWrite }
                rules.edit(COLLIDING.replace("/note", "/other"))
                rules.test()
                until { !rules.busy && rules.tested != null }
                rules.save()
                until { !rules.busy && rules.note?.startsWith("the proxy is matching") == true }
                assertFalse(export.mayWrite, "a rule change puts the export's preview out")
                assertContains(export.note.orEmpty(), "preview this export again")
            }
        }

    @Test
    fun `switching a mode keeps the cassette the route was pinned to`() = withTestProxy { proxy ->
        withControl(proxy) { model, client ->
            // Pinned outside the window, as `proxy.toml` or another client would pin it.
            client
                .send(
                    HttpMethod.Put,
                    "/routes/default",
                    """{"mode":"replay","strict":false,"cassette":"demo"}""",
                )
                .getOrThrow()
            until { !model.busy }
            model.reload()
            until { !model.busy }
            assertEquals("demo", model.routes.single().cassette)

            model.setMode("default", "record", strict = false)
            until { !model.busy }
            // `PUT /routes/{name}` replaces the whole route, so a switch that said nothing
            // about the cassette would unpin it and a later replay would answer from any
            // recording at all.
            assertEquals("demo", model.routes.single().cassette, "the pin survived the switch")
            assertEquals(
                RouteRow("default", "record", false, "demo"),
                routesServed(client)?.single(),
                "and the proxy holds it, not just this window",
            )
        }
    }

    @Test
    fun `an export previews what would be stripped, writes nothing, and then writes the cassette`() =
        withTestProxy { proxy ->
            proxy.recorded("01EXPORTED", """{"model":"m","key":"$A_SECRET"}""")
            withControl(proxy) { model, _ ->
                val export = model.export
                val file = proxy.home.resolve("cassettes").resolve("demo.jsonl")
                export.select("demo", "")
                assertFalse(export.mayWrite, "nothing is written before a preview")

                export.dryRun()
                until { !export.busy && export.previewed != null }
                assertContains(export.preview, "1 exchanges, 1 redactions")
                assertTrue(
                    export.preview.any { it.contains("[REDACTED]") },
                    "the preview says what the secret becomes: ${export.preview.toList()}",
                )
                assertFalse(Files.exists(file), "a dry run writes no file")
                assertTrue(export.mayWrite)

                // A different selection is not this one: the preview goes out with it, and so does
                // the button, even when the text comes back to what it was.
                export.select("other", "")
                assertFalse(export.mayWrite)
                export.select("demo", "")
                assertFalse(export.mayWrite, "a preview is of the selection it was asked for")

                export.dryRun()
                until { !export.busy && export.mayWrite }
                export.write()
                until { !export.busy && export.written != null }
                assertEquals(file.toString(), export.written)
                val written = Files.readString(Path.of(export.written.orEmpty()))
                assertContains(written, "[REDACTED]")
                assertFalse(written.contains(A_SECRET), "the secret is not in the file")
            }
        }

    @Test
    fun `the config screen shows what the proxy runs, and no proxy is said rather than shown`() =
        withTestProxy { proxy ->
            withControl(proxy) { model, _ ->
                until { model.config != null }
                val config = model.config.orEmpty()
                assertContains(config, "\"routes\"")
                assertContains(config, "\"secretHeaders\"")
                // The token is not a config value at all, which is why nothing here filters it.
                assertFalse(config.contains(proxy.token), "the token is not in the config")
                model.detach()
                model.reload()
                assertEquals("there is no proxy to ask", model.note)
            }
        }
}

/** A relay call, the way a client makes one, with the headers the rules keep. */
private suspend fun HttpClient.call(url: String, body: String): HttpResponse =
    post("$url/v1/messages") {
        header("anthropic-version", "2023-06-01")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

/** What `GET /rules` serves right now, read straight rather than through the editor's state. */
private suspend fun served(client: ControlClient): String =
    pretty(client.send(HttpMethod.Get, "/rules").getOrThrow())

/** What `GET /routes` serves right now, read the same way. */
private suspend fun routesServed(client: ControlClient): List<RouteRow>? =
    routesOf(client.send(HttpMethod.Get, "/routes").getOrThrow())

/**
 * A control plane attached to a real proxy, in the test's own scope, given up again afterwards. The
 * scope is `runBlocking`'s one thread, which is where the window's model would be written from too,
 * so what a fold writes is read here as it is written.
 */
private suspend fun withControl(
    proxy: TestProxy,
    block: suspend (ControlPlaneModel, ControlClient) -> Unit,
) = coroutineScope {
    ControlClient(proxy.url, { proxy.token }).use { client ->
        val model = ControlPlaneModel()
        model.attach(this, client)
        try {
            block(model, client)
        } finally {
            model.detach()
        }
    }
}
