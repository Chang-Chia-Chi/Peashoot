package dev.peashoot.app.farm

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val ALPHA = "sess-alpha"
private const val BRAVO = "sess-bravo"
private const val CHARLIE = "sess-charlie"
private const val DELTA = "sess-delta"
private const val ECHO = "sess-echo"

/**
 * The reducer at the seam `docs/spec.md` names: recorded event lines in, the state after every one
 * of them out. Nothing here needs a Compose runtime, or a coroutine: the reducer is a function.
 */
class FarmTest {
    @Test
    fun `a session's turns walk to the well and come back with water`() {
        val states = replay("one-turn.jsonl")
        assertEquals(4, states.size)
        assertEquals(listOf(1, 1, 1, 1), states.map { it.villagers.size })
        assertEquals(
            listOf(
                Activity.WALKING_TO_WELL,
                Activity.RETURNING,
                Activity.WALKING_TO_WELL,
                Activity.RETURNING,
            ),
            states.map { it.villager(ALPHA).activity },
        )
        assertEquals(
            listOf(listOf(ALPHA), emptyList(), listOf(ALPHA), emptyList()),
            states.map { it.wellQueue },
        )
        assertEquals(listOf(1, 0, 1, 0), states.map { it.villager(ALPHA).inFlight.size })
        // Output tokens, added up across the villager's completions.
        assertEquals(listOf(0, 340, 340, 460), states.map { it.villager(ALPHA).water })
        // A Write plants, the Edit in the same turn advances it, the second turn's Edit again.
        assertEquals(
            listOf(null, Growth.SPROUT, Growth.SPROUT, Growth.GROWING),
            states.map { it.crop("src/main/App.kt")?.growth },
        )
        assertEquals(listOf(null, 1, 1, 1), states.map { it.crop("src/main/App.kt")?.inspections })
        // A Read of an unseen path plants nothing, and Grep and a pathless Bash touch nothing.
        assertEquals(listOf(0, 1, 1, 2), states.map { it.crops().size })
        assertNull(states.last().crop("docs/notes.md"))
        assertEquals(listOf("src/main", "src/test"), states.last().fields.keys.sorted())
        // What each turn planted or grew, for the walk home: a Read tends nothing, and the list
        // stands until the next turn completes.
        assertEquals(
            listOf(
                emptyList(),
                listOf("src/main/App.kt"),
                listOf("src/main/App.kt"),
                listOf("src/main/App.kt", "src/test/AppTest.kt"),
            ),
            states.map { it.villager(ALPHA).tended },
        )
        assertEquals(listOf(0, 1, 1, 2), states.map { it.villager(ALPHA).turns })
    }

    @Test
    fun `one exchange completing leaves the villager at the well while another is out`() {
        val states = replay("two-in-flight.jsonl")
        assertEquals(
            listOf(
                Activity.WALKING_TO_WELL,
                Activity.WALKING_TO_WELL,
                Activity.WALKING_TO_WELL,
                Activity.RETURNING,
            ),
            states.map { it.villager(BRAVO).activity },
        )
        assertEquals(listOf(1, 2, 1, 0), states.map { it.villager(BRAVO).inFlight.size })
        assertEquals(
            listOf(listOf(BRAVO), listOf(BRAVO), listOf(BRAVO), emptyList()),
            states.map { it.wellQueue },
        )
        assertEquals(listOf(0, 0, 200, 250), states.map { it.villager(BRAVO).water })
    }

    @Test
    fun `sub-agents are helpers beside the villager they belong to`() {
        val states = replay("sub-agents.jsonl")
        assertEquals(listOf(1, 1, 2, 3, 3, 4, 5), states.map { it.villagers.size })
        val last = states.last()
        assertNull(last.villager(CHARLIE).parent, "a main-thread turn has no parent")
        assertNull(last.parentOf(last.villager(CHARLIE)))
        assertEquals(CHARLIE, last.parentOf(last.villager("$CHARLIE/agent-one"))?.id)
        // A parentAgent names a helper, and the helper stands beside that one, not the session.
        assertEquals("$CHARLIE/agent-one", last.parentOf(last.villager("$CHARLIE/agent-two"))?.id)
        // A helper heard before the parent it names stands beside the session's villager until
        // the parent is heard, and beside the parent from then on: a window that connects in the
        // middle of a run hears them in that order.
        val early = states[5]
        assertEquals(CHARLIE, early.parentOf(early.villager("$CHARLIE/agent-three"))?.id)
        assertEquals(
            "$CHARLIE/agent-late",
            last.parentOf(last.villager("$CHARLIE/agent-three"))?.id,
        )
        assertEquals(Activity.RETURNING, last.villager("$CHARLIE/agent-two").activity)
        assertEquals(260, last.villager("$CHARLIE/agent-two").water)
        assertEquals(
            listOf("$CHARLIE/agent-one", "$CHARLIE/agent-three", "$CHARLIE/agent-late"),
            last.wellQueue,
        )
        // The helper's turn planted its crop like anyone's.
        assertEquals(listOf("src/deep/Thing.kt"), last.crops().map { it.label })
    }

    @Test
    fun `the two spellings of a path meet on one crop, which stops growing when it is ripe`() {
        val states = replay("paths.jsonl")
        val last = states.last()
        assertEquals(listOf(0, 2, 3), states.map { it.crops().size })
        // Six edits in one turn, in both spellings, and one more in the next: ripe, and no riper.
        assertEquals(
            listOf(null, Growth.RIPE, Growth.RIPE),
            states.map { it.crop("src/main/App.kt")?.growth },
        )
        // An Edit to a path never planted plants it, and that edit is the planting.
        assertEquals(
            listOf(null, Growth.SEED, Growth.SEED),
            states.map { it.crop("docs/plan.md")?.growth },
        )
        assertEquals(listOf("docs", "src/main"), last.fields.keys.sorted())
        // The last line of that file carries no `ts` at all, which the fields do not read: a line
        // with no time works them exactly as one with a time does.
        assertEquals(1, last.crop("docs/plan.md")?.inspections)
        // That line planted this one, and nothing has been back to it since.
        assertEquals(Growth.SEED, last.crop("docs/new.md")?.growth)
        assertEquals(0, last.crop("docs/new.md")?.inspections)
        assertEquals(810, last.villager(DELTA).water)
    }

    @Test
    fun `a helper and the session it helps work one crop between them`() {
        val last = replay("touches.jsonl").last()
        val crop = checkNotNull(last.crop("src/main/Shared.kt"))
        // The helper's Read is the same crop as the main thread's Write because the spellings
        // normalise, so the three turns fold into one crop: planted, looked at, grown once.
        assertEquals(Growth.SPROUT, crop.growth)
        assertEquals(1, crop.inspections)
        // Who touched it and when is the proxy's to answer since #85 (`GET /touches`), not this
        // fold's; what the fold owes is that both turns landed on the one crop, and that a helper
        // is a villager of its own beside the session it helps.
        assertEquals(setOf("sess-touch", "sess-touch/agent-two"), last.villagers.keys)
        // A Grep names a path and touches nothing, so it leaves no crop at all.
        assertEquals(listOf("src/main/Shared.kt"), last.crops().map { it.label })
    }

    @Test
    fun `a completed turn heard first still makes a villager and plants its crops`() {
        val last = replay("late-join.jsonl").single()
        assertEquals(setOf("sess-late"), last.villagers.keys)
        assertEquals(Activity.RETURNING, last.villager("sess-late").activity)
        assertEquals(90, last.villager("sess-late").water)
        assertEquals(Growth.SEED, last.crop("src/main/Late.kt")?.growth)
        assertTrue(last.wellQueue.isEmpty())
    }

    @Test
    fun `a villager's name is the same one every run`() {
        val name = replay("one-turn.jsonl").last().villager(ALPHA).name
        assertContains(VILLAGER_NAMES, name)
        // The literals are the whole test: a hash index is the same twice in one run whatever it
        // does, so only a written-down name catches the list or the index rule changing under a
        // villager between runs of the app.
        assertEquals("Clover", name)
        assertEquals("Yara", replay("sub-agents.jsonl").last().villager("$CHARLIE/agent-one").name)
    }

    @Test
    fun `two sessions whose names collide are told apart, the same way every run`() {
        // Two ids the hash sends to one name, found rather than written down, so the test holds
        // whatever the list holds.
        val first = "sess-0"
        val second =
            (1..10_000).map { "sess-$it" }.first { nameFor(it) == nameFor(first) && it != first }
        val farm =
            listOf(first, second)
                .map { session ->
                    buildJsonObject {
                        put("event", "exchange.started")
                        put("session", session)
                        put("exchangeId", "ex-$session")
                    }
                }
                .fold(FarmState()) { state, event -> reduce(state, event) }
        val one = farm.villager(first).name
        val two = farm.villager(second).name
        assertEquals(nameFor(first), one, "the first keeps the name its id picks")
        assertTrue(one != two, "two farmers answer to $one")
        val next = VILLAGER_NAMES[(VILLAGER_NAMES.indexOf(one) + 1) % VILLAGER_NAMES.size]
        assertEquals(next, two, "the second takes the next free name, so a replay names it alike")
    }

    @Test
    fun `label text is in the state, and hidden`() {
        assertTrue(FarmState().labelsHidden, "labels are hidden until something says otherwise")
        val last = replay("one-turn.jsonl").last()
        assertTrue(last.labelsHidden)
        assertEquals("src/main/App.kt", last.crop("src/main/App.kt")?.label)
        assertEquals("src/main", last.fields.getValue("src/main").label)
    }

    @Test
    fun `a line the reducer cannot use leaves the farm as it was`() {
        val states = replay("odd-lines.jsonl")
        assertEquals(7, states.size)
        // `event` as an object and an unknown event name change nothing at all, tools and all.
        assertEquals(states[0], states[1])
        assertEquals(states[0], states[2])
        // A client_gone is lightning and one spilled bucket, on the villager whose in-flight
        // exchange it names, and nothing else (#18).
        assertEquals(
            states[0].copy(
                weather = Weather.LIGHTNING,
                villagers = mapOf(ECHO to states[0].villager(ECHO).copy(spills = 1)),
            ),
            states[3],
        )
        // A null session is a line with no villager to be about.
        assertEquals(states[3], states[4])
        // `tools` as a string and `usage` null: the villager still comes home, carrying nothing.
        assertEquals(Activity.RETURNING, states[5].villager(ECHO).activity)
        assertEquals(0, states[5].villager(ECHO).water)
        assertTrue(states[5].crops().isEmpty())
        // No exchangeId, a tool with no path, a path that is an object, a tool that is a string:
        // nothing about the farm changes but what the turn itself adds to the bin, to the day and
        // to the villager's count of turns.
        val echo = states[5].villager(ECHO)
        assertEquals(
            states[5].copy(
                bin = states[6].bin,
                days = states[6].days,
                villagers = mapOf(ECHO to echo.copy(turns = echo.turns + 1)),
            ),
            states[6],
        )
        // Both turns ended; only the second reported usage with no cost, which is what unpriced
        // means. The first reported nothing at all, so there was nothing to price.
        assertEquals(ShippingBin(produce = 2, ledger = 0.0, unpriced = 1), states[6].bin)
        assertEquals(1, states.last().villagers.size)
    }

    /**
     * A Responses get-by-id and a cancel (#81). Each is an exchange of its own, with its own
     * started and completed line, and neither generated anything: the response they name was
     * generated by the exchange that created it. Counting them drew a second turn beside a cancel
     * and one per poll of a background response.
     */
    @Test
    fun `a get-by-id and a cancel end their exchange and count for nothing else`() {
        val states = replay("responses-poll.jsonl")
        assertEquals(9, states.size)
        val turn = states[1]
        assertEquals(120, turn.villager(CODEX).water)
        assertEquals(Weather.RAIN, turn.weather, "the turn read from the cache")
        assertEquals(Growth.SEED, turn.crop("src/codex/Main.kt")?.growth)
        // A Responses create ends by saying `completed`, which is what ending is called there
        // (#93): the turn ships its produce like any other.
        assertEquals(ShippingBin(produce = 1, ledger = 0.03, unpriced = 0), turn.bin)

        // The walk is undone and not prevented: nothing on a started line says what is coming, so
        // the poll sends its villager to the well and its completion is what brings it home.
        assertEquals(Activity.WALKING_TO_WELL, states[2].villager(CODEX).activity)
        assertEquals(listOf(CODEX), states[2].wellQueue)
        assertEquals(Activity.RETURNING, states[3].villager(CODEX).activity)
        assertTrue(states[3].villager(CODEX).inFlight.isEmpty(), "the exchange ended")
        assertTrue(states[3].wellQueue.isEmpty(), "nobody is left standing at the well")
        // And everything the turn before it left is exactly as it was: the sky, the water, the
        // crop the poll's body repeated back, the bin. Only the day has heard another line.
        assertEquals(turn.copy(days = states[3].days), states[3])
        assertEquals(states[3].copy(days = states[5].days), states[5], "nor does a cancel")
        // A line that is not a turn makes no villager for a session this window never heard.
        assertEquals(states[5], states[6])
        assertEquals(setOf(CODEX), states.last().villagers.keys)
        assertEquals(
            Tokens(input = 900, output = 120, cacheRead = 400, cacheWrite = 0),
            states.last().days.getValue(CODEX).tokens,
        )
        assertEquals(0.03, states.last().days.getValue(CODEX).cost)
        assertEquals(turn.bin, states.last().bin, "and nothing after the turn ships anything")
    }

    /**
     * The poll #81 could not see (#94): a `GET /v1/responses/{id}` of a response that has already
     * finished answers with the whole object, usage and all, so no reading of the line's own
     * numbers tells it from the create. What tells them apart is the request's path, and the
     * Deriver now says so on the line: `generatedNothing`, absent on everything recorded before it.
     */
    @Test
    fun `a poll of a finished response carries the create's usage and is still no second turn`() {
        val states = replay("responses-poll.jsonl")
        val before = states[6]
        assertEquals(Activity.WALKING_TO_WELL, states[7].villager(CODEX).activity)
        assertEquals(listOf(CODEX), states[7].wellQueue)

        val farm = states[8]
        assertEquals(Activity.RETURNING, farm.villager(CODEX).activity)
        assertTrue(farm.villager(CODEX).inFlight.isEmpty(), "the exchange ended")
        assertEquals(before.copy(days = farm.days), farm, "and nothing else moved")
        // The money and the water, which is what a second turn would have doubled.
        assertEquals(ShippingBin(produce = 1, ledger = 0.03, unpriced = 0), farm.bin)
        assertEquals(120, farm.villager(CODEX).water)
        assertEquals(
            Tokens(input = 900, output = 120, cacheRead = 400, cacheWrite = 0),
            farm.days.getValue(CODEX).tokens,
        )
        assertEquals(0.03, farm.days.getValue(CODEX).cost)
    }

    /**
     * The fourth way a turn reports no usage and is a turn all the same: its client left before the
     * reader parsed any. No usage, a 200, no `end_turn` — everything a poll looks like — but the
     * stream was being answered, so the sky turns, the bucket it was filling carries nothing home,
     * and the tools it had named by then still work the fields.
     */
    @Test
    fun `a cut stream that parsed no usage is still the turn it was`() {
        val states = replay("stream-cut.jsonl")
        assertEquals(1, states[1].villager(CUT).spills, "the departure spilled its bucket")
        // The other turn finished in between and cleared the sky, so the lightning at the end is
        // this line's own rather than the departure's still standing.
        assertEquals(Weather.CLEAR, states[3].weather)
        assertEquals(90, states[3].villager(CUT).water, "the turn that did end carried its water")

        val farm = states.last()
        assertEquals(Weather.LIGHTNING, farm.weather)
        assertEquals(Growth.SEED, farm.crop("src/cut/Stream.kt")?.growth)
        val villager = farm.villager(CUT)
        assertEquals(90, villager.water, "the cut turn's own bucket spilled")
        assertEquals(Activity.RETURNING, villager.activity)
        assertTrue(villager.inFlight.isEmpty(), "both exchanges ended")
        assertTrue(farm.wellQueue.isEmpty())
    }

    /**
     * A dropped stream that the proxy resumed (#26). One provider call, and two
     * `exchange.completed` lines carrying the same usage: the original, whose client left, and the
     * resumed one, which made no call and was billed nothing. The farm must count the answer once —
     * two harvests or double tokens on the day's card would say the opposite of what resume is for.
     */
    @Test
    fun `a resumed answer is one harvest and one turn's tokens`() {
        val states = replay("resumed.jsonl")
        assertEquals(5, states.size)
        val farm = states.last()

        // One paid call, so one produce and the one cost the provider named.
        assertEquals(ShippingBin(produce = 1, ledger = 1.5, unpriced = 0), farm.bin)
        // And the day's card says what was billed, not twice what was billed.
        val day = farm.days.getValue(RESUME)
        assertEquals(
            Tokens(input = 2, output = 715, cacheRead = 0, cacheWrite = 106710),
            day.tokens,
        )
        assertEquals(1.5, day.cost)

        // The bucket the original was filling spilled, so only the resumed line carries water home.
        val villager = farm.villager(RESUME)
        assertEquals(715, villager.water, "the answer arrived once")
        assertEquals(1, villager.spills)
        assertEquals(Activity.RETURNING, villager.activity)
        assertTrue(villager.inFlight.isEmpty(), "both exchanges ended")
        // Lightning belongs to the drop; the resumed line that follows is a clear completion.
        assertEquals(Weather.LIGHTNING, states[3].weather)
        assertEquals(Weather.CLEAR, farm.weather)
    }

    /**
     * The Responses cursor's answer (#27), where #26's resume and #94's flag meet on one line
     * (#104). It says `resumed`, because it was served from the buffer and cost nothing, and it
     * says `generatedNothing`, because a cursor is a `GET` and not a create. The two say different
     * things — this cost nothing, this created nothing new — and only the second may silence the
     * farm. The create's own bucket spilled when its client left, which is why there was anything
     * to resume, so a cursor that carries nothing either is an answer reaching a client that the
     * farm never shows arriving.
     */
    @Test
    fun `a cursor-resumed answer carries the water the spilled create could not`() {
        val states = replay("responses-resumed.jsonl")
        assertEquals(5, states.size)
        // The create went on reading after its client left, so its line carries the whole answer:
        // the produce, the cost and the tools are all counted there, and the water spills there.
        val spilled = states[3]
        assertEquals(0, spilled.villager(CURSOR).water, "the bucket spilled when the client left")
        assertEquals(Weather.LIGHTNING, spilled.weather)
        assertEquals(Growth.SEED, spilled.crop("src/cursor/Tail.kt")?.growth)
        assertEquals(ShippingBin(produce = 1, ledger = 0.08, unpriced = 0), spilled.bin)

        val farm = states.last()
        val villager = farm.villager(CURSOR)
        assertEquals(240, villager.water, "the cursor's answer did reach a client")
        assertEquals(Weather.CLEAR, farm.weather, "the drop's lightning, not the delivery's")
        assertEquals(1, villager.spills)
        assertEquals(Activity.RETURNING, villager.activity)
        assertTrue(villager.inFlight.isEmpty(), "both exchanges ended")
        assertTrue(farm.wellQueue.isEmpty())
        // Still one paid call: the tail repeats the create's usage, cost and tools, and none of
        // the three may count a second time.
        assertEquals(ShippingBin(produce = 1, ledger = 0.08, unpriced = 0), farm.bin)
        assertEquals(Growth.SEED, farm.crop("src/cursor/Tail.kt")?.growth, "planted once")
        val day = farm.days.getValue(CURSOR)
        assertEquals(Tokens(input = 600, output = 240, cacheRead = 0, cacheWrite = 0), day.tokens)
        assertEquals(0.08, day.cost)
    }

    private companion object {
        const val CODEX = "sess-codex"
        const val CUT = "sess-cut"
        const val RESUME = "sess-resume"
        const val CURSOR = "sess-cursor"
    }
}

/**
 * The state after each line of a fixture, in order, as `docs/spec.md`'s seam describes it. Internal
 * rather than private because [SignalsTest] and [DayTest] replay the same way: one loader keeps the
 * three files reading the fixtures alike.
 */
internal fun replay(file: String): List<FarmState> =
    events(file).runningFold(FarmState()) { state, event -> reduce(state, event) }.drop(1)

internal fun events(file: String): List<JsonObject> =
    checkNotNull(FarmTest::class.java.getResourceAsStream("/farm/$file")) { file }
        .bufferedReader()
        .use { it.readLines() }
        .filter { it.isNotBlank() }
        .map { Json.parseToJsonElement(it).jsonObject }

internal fun FarmState.villager(id: String): Villager = villagers.getValue(id)

internal fun FarmState.crops(): List<Crop> = fields.values.flatMap { it.crops.values }

internal fun FarmState.crop(path: String): Crop? = crops().firstOrNull { it.label == path }
