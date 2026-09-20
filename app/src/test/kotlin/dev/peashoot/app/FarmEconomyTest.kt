package dev.peashoot.app

import dev.peashoot.app.farm.events
import dev.peashoot.app.farm.nameFor
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Float noise, and deliberately nothing bigger.
 *
 * Every cost in these fixtures is an exact binary fraction — 0.25, 0.125, 0.5, 0.0 — so the two
 * sums are bit-equal today and even 0.0 would pass. This is here for the day a fixture carries a
 * price like 0.1, where the same addends in two orders differ in the last bit or two and nothing
 * larger than that would be a real disagreement. Half a cent, which this used to be, is four per
 * cent of the smallest priced turn here: it would hide a whole dropped sub-cent cost.
 */
private const val FLOAT_NOISE = 1e-9

/** The line of `bin.jsonl` that ends a turn and names a price; see the fixture README. */
private const val A_PRICED_TURN = 1
private const val THE_SESSION = "sess-bin"

/** Comfortably past the window `AppModel` ends a quiet day after. */
private val LONG_QUIET = Duration.ofMinutes(45)

/**
 * The farm's economy against the proxy's own books: issue #21's second and third criteria, at the
 * seam `docs/spec.md` names — a real proxy, a real store, a real feed, and the window's own
 * `AppModel` folding what arrives.
 *
 * `runBlocking` by way of [withTestProxy], like its neighbours: every wait here is on a socket or
 * on the window's own 5-second tick, where a virtual clock would fire the timeouts before any of it
 * had happened.
 */
class FarmEconomyTest {
    @Test
    fun `the shipping bin's ledger is what the sessions endpoint says those turns cost`() =
        withTestProxy { proxy ->
            val model = AppModel(home = proxy.home, port = proxy.port)
            coroutineScope {
                val watching = launch { model.watch() }
                until { model.status.startsWith("connected") }
                // Two fixtures, so the endpoint has several sessions and one sub-agent to group by:
                // the bin is one number for the whole farm and the endpoint is one row per pair,
                // which is the only interesting thing about comparing them.
                val lines = events("bin.jsonl") + events("day.jsonl")
                lines.forEach { proxy.put(it) }
                until { model.lines.size >= lines.size }
                val reported = proxy.sessionCosts()
                // How the endpoint groups its rows is the endpoint's business — one per session and
                // agent today — and the criterion is about the total, not the shape. Only that it
                // answered with costs at all, so that a sum of nothing cannot pass for agreement.
                assertTrue(reported.isNotEmpty(), "the endpoint reported no costs to compare with")
                assertEquals(
                    reported.sum(),
                    model.farm.bin.ledger,
                    FLOAT_NOISE,
                    "the farm's ledger and the proxy's books disagree about the same traffic",
                )
                assertTrue(model.farm.bin.ledger > 0.0, "a ledger of nothing proves nothing")
                // And the farm said why it is quieter than the day: one turn reported usage and no
                // price, which is counted rather than read as free.
                assertEquals(1, model.farm.bin.unpriced)
                watching.cancel()
            }
        }

    @Test
    fun `a day gone quiet leaves a card the window shows, and dismissing takes it away`() =
        withTestProxy { proxy ->
            val model = AppModel(home = proxy.home, port = proxy.port)
            coroutineScope {
                val watching = launch { model.watch() }
                until { model.status.startsWith("connected") }
                // A real completed line, stamped before the idle window rather than inside it:
                // nothing else about the farm is different, and only the clock ends a day.
                proxy.put(events("bin.jsonl")[A_PRICED_TURN].at(Instant.now() - LONG_QUIET))
                until { model.farm.pendingCards.isNotEmpty() }
                val card = model.farm.pendingCards.single()
                assertEquals(nameFor(THE_SESSION), card.villager, "a card is read by a person")
                assertEquals(THE_SESSION, card.session)
                assertTrue(card.cost > 0.0, "the day's card lost what the turn cost")
                assertTrue(
                    model.farm.villagers.isEmpty(),
                    "ending a day retires its villagers, stranded or not",
                )
                model.dismissCard()
                assertTrue(model.farm.pendingCards.isEmpty(), "a card that cannot be put down")
                watching.cancel()
            }
        }
}

/** The same line, said to have happened at another time. */
private fun JsonObject.at(ts: Instant): JsonObject =
    JsonObject(this + ("ts" to JsonPrimitive(ts.toString())))

/**
 * What `GET /sessions` reports one cost at a time: the proxy's own reading of the same event lines,
 * out of the store's session view rather than out of anything the window worked out.
 */
private suspend fun TestProxy.sessionCosts(): List<Double> =
    HttpClient(CIO).use { client ->
        val body =
            client
                .get("$url/_peashoot/v1/sessions") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                .bodyAsText()
        Json.parseToJsonElement(body).jsonObject.getValue("sessions").jsonArray.mapNotNull {
            (it.jsonObject["costUsd"] as? JsonPrimitive)?.doubleOrNull
        }
    }
