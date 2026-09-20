package dev.peashoot.app.farm

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

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
        assertEquals("2026-09-19T09:00:12Z", states.last().crop("src/main/App.kt")?.lastTouched)
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
        // The last line of that file carries no `ts` at all: the crops keep the one they had
        // rather than being stamped blank, and everything else about them still happens.
        assertEquals("2026-09-19T12:00:20Z", last.crop("src/main/App.kt")?.lastTouched)
        assertEquals("2026-09-19T12:00:20Z", last.crop("docs/plan.md")?.lastTouched)
        assertEquals(1, last.crop("docs/plan.md")?.inspections)
        // A crop that line planted has no timestamp to keep, and says so rather than saying "".
        assertEquals(Growth.SEED, last.crop("docs/new.md")?.growth)
        assertNull(last.crop("docs/new.md")?.lastTouched)
        assertEquals(810, last.villager(DELTA).water)
    }

    @Test
    fun `a crop keeps who touched it and when, in the order it happened`() {
        val last = replay("touches.jsonl").last()
        val crop = checkNotNull(last.crop("src/main/Shared.kt"))
        // Issue #22's second criterion: the agents and the times, not just the last of them. The
        // helper's Read is the same crop as the main thread's Write because the spellings
        // normalise.
        assertEquals(
            listOf(
                Touch("sess-touch", "2026-09-20T09:00:05Z", TouchKind.PLANTED),
                Touch("sess-touch/agent-two", "2026-09-20T09:00:11Z", TouchKind.INSPECTED),
                Touch("sess-touch", "2026-09-20T09:00:18Z", TouchKind.GROWN),
            ),
            crop.touches,
        )
        // A Grep names a path and touches nothing, so it leaves no crop and no touch.
        assertEquals(listOf("src/main/Shared.kt"), last.crops().map { it.label })
        assertEquals("2026-09-20T09:00:18Z", crop.lastTouched)
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
        // nothing about the farm changes but what the turn itself adds to the bin and to the day.
        assertEquals(states[5].copy(bin = states[6].bin, days = states[6].days), states[6])
        // Both turns ended; only the second reported usage with no cost, which is what unpriced
        // means. The first reported nothing at all, so there was nothing to price.
        assertEquals(ShippingBin(produce = 2, ledger = 0.0, unpriced = 1), states[6].bin)
        assertEquals(1, states.last().villagers.size)
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
