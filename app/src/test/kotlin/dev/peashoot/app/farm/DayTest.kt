package dev.peashoot.app.farm

import dev.peashoot.core.EDIT_TOOLS
import dev.peashoot.core.text
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

private const val HAZEL = "sess-hazel"
private const val IVY = "sess-ivy"

/** The window the app will pass, so the fixture's own gaps are read against a real one. */
private val IDLE_AFTER: Duration = Duration.ofMinutes(30)

/** Every instant here is spelled in UTC and read in UTC, so the tests pass in any zone. */
private val UTC: ZoneId = ZoneId.of("UTC")

/** Southern, and says so with its clocks: summer time in January, standard time in July. */
private val AUCKLAND: ZoneId = ZoneId.of("Pacific/Auckland")

/** Southern, and says nothing with its clocks: Queensland has kept one offset since 1992. */
private val BRISBANE: ZoneId = ZoneId.of("Australia/Brisbane")

/** Southern, and unplaceable: no daylight saving, and no area id that settles it either. */
private val JOHANNESBURG: ZoneId = ZoneId.of("Africa/Johannesburg")

/** On the equator, where there is no season to get right. */
private val NAIROBI: ZoneId = ZoneId.of("Africa/Nairobi")

/**
 * Thirty minutes to the second after the last thing `sess-hazel` said, which is the window exactly:
 * a day ends *at* the window and not only past it, and this is the instant that would tell `>=`
 * from `>`. `sess-ivy` was heard ten minutes before it, so its day goes on.
 */
private val AFTER_THE_WINDOW: Instant = Instant.parse("2026-09-19T10:30:10Z")

/**
 * The clock's half of the farm: `tick` is the only place time enters, so these tests hand it fixed
 * instants and never ask what time it is.
 */
class DayTest {
    @Test
    fun `a tick before the window ends nothing`() {
        val farm = replay("day.jsonl").last()
        val ticked = tick(farm, Instant.parse("2026-09-19T10:29:00Z"), IDLE_AFTER, UTC)
        assertTrue(ticked.pendingCards.isEmpty())
        // The season is the whole of what a tick before the window changes.
        assertEquals(farm.copy(season = ticked.season), ticked)
    }

    @Test
    fun `the card's values are the sums of that session's own lines`() {
        val farm = replay("day.jsonl").last()
        val card = tick(farm, AFTER_THE_WINDOW, IDLE_AFTER, UTC).pendingCards.single()
        val lines = linesOf(HAZEL)
        val tokens =
            Tokens(
                input = sum(lines, "input"),
                output = sum(lines, "output"),
                cacheRead = sum(lines, "cacheRead"),
                cacheWrite = sum(lines, "cacheWrite"),
            )
        assertEquals(HAZEL, card.session)
        assertEquals(farm.villager(HAZEL).name, card.villager)
        assertEquals(tokens, card.tokens)
        assertEquals(costOf(lines), card.cost)
        assertEquals(filesOf(lines).size, card.filesTouched)
        assertEquals(
            tokens.cacheRead.toDouble() / (tokens.input + tokens.cacheRead + tokens.cacheWrite),
            card.cacheHitRate,
        )
        // The helper spends its session's day, and the two spellings of one path are one file.
        assertEquals(2, card.filesTouched)
        assertTrue(card.cost > 0.0, "the fixture has costs to add up")
        assertTrue(card.tokens.cacheRead > 0, "the fixture has cache reads to divide")
    }

    @Test
    fun `ending a day retires the session's villagers and frees the one stranded at the well`() {
        val farm = replay("day.jsonl").last()
        assertEquals(setOf(HAZEL, "$HAZEL/agent-one", IVY), farm.villagers.keys)
        assertEquals(listOf(HAZEL), farm.wellQueue, "a started whose completed never came")
        val ended = tick(farm, AFTER_THE_WINDOW, IDLE_AFTER, UTC)
        // The main villager and its helper go; the session heard ten minutes ago stays.
        assertEquals(setOf(IVY), ended.villagers.keys)
        assertTrue(ended.wellQueue.isEmpty())
        assertEquals(setOf(IVY), ended.days.keys)
        // Crops are the map of the repository, not of a session.
        assertEquals(farm.fields, ended.fields)
    }

    @Test
    fun `a session heard again after its day ended starts a new one`() {
        val farm = replay("day.jsonl").last()
        val ended = tick(farm, AFTER_THE_WINDOW, IDLE_AFTER, UTC)
        val again = reduce(ended, events("day.jsonl").first())
        assertEquals(farm.villager(HAZEL).name, again.villager(HAZEL).name, "the name is a hash")
        assertEquals(0, again.villager(HAZEL).water)
        assertEquals(Tokens(), again.days.getValue(HAZEL).tokens)
        assertEquals(1, again.pendingCards.size, "the card waits for the window to take it")
    }

    @Test
    fun `a dismissed card is the oldest one, and only it`() {
        val farm = replay("day.jsonl").last()
        val one = tick(farm, AFTER_THE_WINDOW, IDLE_AFTER, UTC)
        // The second session goes quiet ten minutes later, so a later tick leaves a second card
        // behind the first: the window shows them one at a time and in the order they ended.
        val two = tick(one, AFTER_THE_WINDOW.plus(IDLE_AFTER), IDLE_AFTER, UTC)
        assertEquals(listOf(HAZEL, IVY), two.pendingCards.map { it.session })
        assertEquals(listOf(IVY), two.dismissed().pendingCards.map { it.session })
        assertTrue(two.dismissed().dismissed().pendingCards.isEmpty())
        // Taking a card away is the whole of what it does: nothing else about the farm moves.
        assertEquals(two.copy(pendingCards = two.pendingCards.drop(1)), two.dismissed())
    }

    @Test
    fun `dismissing a farm with no card to dismiss changes nothing`() {
        assertEquals(FarmState(), FarmState().dismissed())
    }

    @Test
    fun `the season is the calendar month, in the zone the caller reads its clock in`() {
        val noons = listOf("2026-01-15", "2026-04-15", "2026-07-15", "2026-10-15")
        assertEquals(
            listOf(Season.WINTER, Season.SPRING, Season.SUMMER, Season.AUTUMN),
            noons.map {
                tick(FarmState(), Instant.parse("${it}T12:00:00Z"), IDLE_AFTER, UTC).season
            },
        )
        // The zone is the caller's: one instant, two months, two seasons.
        val december = Instant.parse("2026-12-01T00:30:00Z")
        assertEquals(Season.WINTER, tick(FarmState(), december, IDLE_AFTER, UTC).season)
        assertEquals(
            Season.AUTUMN,
            tick(FarmState(), december, IDLE_AFTER, ZoneId.of("America/New_York")).season,
        )
    }

    @Test
    fun `south of the equator the same instant is the opposite season`() {
        val january = Instant.parse("2026-01-15T12:00:00Z")
        val july = Instant.parse("2026-07-15T12:00:00Z")
        fun seasonIn(now: Instant, zone: ZoneId) = tick(FarmState(), now, IDLE_AFTER, zone).season
        // One instant, two hemispheres, opposite seasons — both ways round.
        assertEquals(Season.WINTER, seasonIn(january, UTC))
        assertEquals(Season.SUMMER, seasonIn(january, AUCKLAND))
        assertEquals(Season.SUMMER, seasonIn(july, UTC))
        assertEquals(Season.WINTER, seasonIn(july, AUCKLAND))
        // A southern zone whose clocks never move is placed by its area id instead.
        assertEquals(Season.SUMMER, seasonIn(january, BRISBANE))
        // And one that neither says: the northern calendar, which is what the farm always did.
        // Written down because it is the method's known ceiling and not an accident.
        assertEquals(Season.WINTER, seasonIn(january, JOHANNESBURG))
        assertEquals(Season.WINTER, seasonIn(january, NAIROBI))
    }
}

/** One session's own lines out of the fixture, to be added up here rather than trusted. */
private fun linesOf(session: String): List<JsonObject> =
    events("day.jsonl").filter { it["session"].text() == session }

private fun sum(lines: List<JsonObject>, kind: String): Int = lines.sumOf { line ->
    ((line["usage"] as? JsonObject)?.get(kind) as? JsonPrimitive)?.intOrNull ?: 0
}

private fun costOf(lines: List<JsonObject>): Double = lines.sumOf {
    (it["costUsd"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
}

private fun filesOf(lines: List<JsonObject>): Set<String> =
    lines
        .flatMap { (it["tools"] as? JsonArray ?: emptyList<JsonElement>()) }
        .filterIsInstance<JsonObject>()
        .filter { it["name"].text() == "Read" || it["name"].text() in EDIT_TOOLS }
        .mapNotNull { it["path"].text()?.replace('\\', '/') }
        .toSet()
