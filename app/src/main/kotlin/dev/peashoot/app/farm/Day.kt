package dev.peashoot.app.farm

import dev.peashoot.core.text
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What one session has run up since its day began. The day belongs to the session and not to the
 * villager: a helper spends its parent's, because the card a person reads is about the conversation
 * they had, sub-agents and all.
 *
 * ponytail: a day whose lines never carried a readable `ts` has no [lastHeard], so it never ends.
 * The proxy stamps every line from its own clock, so that is a line no Deriver writes. Upgrade: age
 * such a day from the tick that first saw it, if a feed ever carries one.
 */
data class Day(
    val tokens: Tokens = Tokens(),
    /** Only the costs a line named: an unpriced turn is counted in the bin, not guessed at here. */
    val cost: Double = 0.0,
    /**
     * The paths its tools touched, normalised; a set, because a card counts files and not calls.
     */
    val files: Set<String> = emptySet(),
    val lastHeard: Instant? = null,
)

/** Tokens by kind, as the provider reported them. */
data class Tokens(
    val input: Int = 0,
    val output: Int = 0,
    val cacheRead: Int = 0,
    val cacheWrite: Int = 0,
)

/** What a session's day came to, waiting for the window to show it and [dismissed] to take it. */
data class EndOfDayCard(
    val session: String,
    /** The villager's name and not its id: a card is read by a person. */
    val villager: String,
    val tokens: Tokens,
    val cost: Double,
    val filesTouched: Int,
    /**
     * `cacheRead / (input + cacheRead + cacheWrite)`: the share of everything the day read that
     * came from the cache. 0.0 when that denominator is 0, because a day that read nothing has no
     * hit rate rather than an undefined one.
     */
    val cacheHitRate: Double,
)

/**
 * The farm after a reading of the clock: the season the month names, and the day ended for every
 * session quiet for [idleAfter]. This is the only place time enters the farm — [reduce] reads
 * nothing but the line it is handed — so a recorded feed still replays to the same farm twice, and
 * a test hands this fixed instants rather than owning a clock.
 *
 * ponytail: quiet is measured from a session's last *line*, and a stream writes none while it runs.
 * A single turn streaming for longer than [idleAfter] is indistinguishable here from one whose
 * proxy died, so its day ends mid-flight and its `completed` line begins a new one, leaving a card
 * too many. No turn streams for thirty minutes today. Upgrade: a heartbeat line from the Deriver
 * while a stream is open, if one ever does.
 */
fun tick(state: FarmState, now: Instant, idleAfter: Duration, zone: ZoneId): FarmState {
    val quiet =
        state.days.filterValues { day ->
            day.lastHeard?.let { Duration.between(it, now) >= idleAfter } == true
        }
    return quiet.keys.fold(state.copy(season = seasonOf(now, zone)), ::endDay)
}

/**
 * One session's day ends: a card for what it did, and its villagers retire — the main one and its
 * helpers, out of the well queue as well. The queue is as much the point as the card. A villager
 * whose proxy was killed between its `started` and the `completed` that never came stands at the
 * well for the life of the window, and only a clock can say that turn is not coming back. The crops
 * stay: they are the map of the repository, not of a session.
 */
private fun endDay(state: FarmState, session: String): FarmState {
    val day = state.days[session] ?: return state
    val leaving = state.villagers.values.filter { it.session == session }.map { it.id }.toSet()
    val card =
        EndOfDayCard(
            session = session,
            // Not looked up: a session's villager is named by this same rule, so the card carries
            // the right name even for a day only a helper was ever heard in.
            villager = nameFor(session),
            tokens = day.tokens,
            cost = day.cost,
            filesTouched = day.files.size,
            cacheHitRate = day.tokens.cacheHitRate(),
        )
    return state.copy(
        villagers = state.villagers - leaving,
        wellQueue = state.wellQueue - leaving,
        days = state.days - session,
        pendingCards = state.pendingCards + card,
    )
}

/**
 * The farm with the card the window is showing taken away. The oldest, because [endDay] appends and
 * the window shows the front of the queue: cards are read one at a time and in the order the days
 * ended, so which one is on screen is never in doubt and the call needs no argument.
 *
 * Pure, like everything else here, and the only thing in the farm a click leads to — which is why
 * it lives beside the card rather than in the window: what a day came to is the farm's, and a
 * button is not.
 */
fun FarmState.dismissed(): FarmState = copy(pendingCards = pendingCards.drop(1))

/** The session's day with one more of its lines in it; a session heard first begins a new one. */
internal fun Map<String, Day>.heard(session: String, event: JsonObject): Map<String, Day> =
    plus(session to (this[session] ?: Day()).after(event))

private fun Day.after(event: JsonObject): Day =
    Day(
        tokens = tokens.add(event),
        cost = cost + (event.scalar("costUsd") { doubleOrNull } ?: 0.0),
        files = files + tools(event).mapNotNull(::touchedPath),
        // A line whose `ts` is not a time this can read counts for everything else and leaves the
        // last-heard time alone: one strange line must not end a day early, nor hold one open.
        lastHeard = heardAt(event) ?: lastHeard,
    )

/** A line that reported no usage — a `started`, a failure, a 429 — adds no tokens to the day. */
private fun Tokens.add(event: JsonObject): Tokens {
    val usage = usage(event) ?: return this
    fun kind(name: String) = usage.scalar(name) { intOrNull } ?: 0
    return Tokens(
        input = input + kind("input"),
        output = output + kind("output"),
        cacheRead = cacheRead + kind("cacheRead"),
        cacheWrite = cacheWrite + kind("cacheWrite"),
    )
}

private fun Tokens.cacheHitRate(): Double {
    val read = input + cacheRead + cacheWrite
    return if (read == 0) 0.0 else cacheRead.toDouble() / read
}

private fun heardAt(event: JsonObject): Instant? =
    event["ts"].text()?.let {
        try {
            Instant.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    }

/**
 * The season the calendar is in, read in the zone the caller reads its clock in — northern months,
 * turned half a year where [southern] can place the zone below the equator.
 */
private fun seasonOf(now: Instant, zone: ZoneId): Season {
    val local = now.atZone(zone)
    val month = if (southern(zone, local.year)) local.month.plus(HALF_A_YEAR) else local.month
    return when (month) {
        Month.DECEMBER,
        Month.JANUARY,
        Month.FEBRUARY -> Season.WINTER
        Month.MARCH,
        Month.APRIL,
        Month.MAY -> Season.SPRING
        Month.JUNE,
        Month.JULY,
        Month.AUGUST -> Season.SUMMER
        else -> Season.AUTUMN
    }
}

/** Half a year: what turns the northern months into the ones a southern zone is living in. */
private const val HALF_A_YEAR = 6L

/** Mid-month, which is far enough from any transition that the offset there is the month's own. */
private const val MID_MONTH = 15

/**
 * Whether the zone is south of the equator, as far as a zone id can say — which is not far, so this
 * is a guess with a stated reach and not a fact about where anyone is.
 *
 * Two things a zone id tells you. Its clocks: a zone keeping summer time in January and standard
 * time in July is keeping it in the months the north calls winter, and only a southern zone does
 * that. And its area, for the two areas that hold nothing else — every zone under `Australia/` and
 * `Antarctica/` is southern whatever its clocks do, which is what places Brisbane and Perth.
 *
 * What it cannot place is a zone that keeps one offset all year and sits in neither area:
 * Johannesburg, São Paulo, Buenos Aires, Jakarta, Lima. Those read as northern, which is what the
 * whole farm did before this — no worse than it was, and better for everywhere the clocks or the
 * area settle. The equator needs no answer of its own: a zone on it has no season to get wrong, and
 * never moves its clocks, so it is never called southern. A latitude is not in a zone id, and
 * nothing here pretends to one.
 *
 * ponytail: no gazetteer. A table of the eighty-odd southern zone ids would place them all and
 * would then be a geography to keep true against tzdb, for a tint on the sky. Upgrade: that table,
 * or the hemisphere key #95 chose not to add, if anyone ever says the sky is wrong.
 */
private fun southern(zone: ZoneId, year: Int): Boolean =
    zone.id.startsWith("Australia/") ||
        zone.id.startsWith("Antarctica/") ||
        (zone.rules.isDaylightSavings(noon(year, Month.JANUARY, zone)) &&
            !zone.rules.isDaylightSavings(noon(year, Month.JULY, zone)))

private fun noon(year: Int, month: Month, zone: ZoneId): Instant =
    ZonedDateTime.of(LocalDate.of(year, month, MID_MONTH), LocalTime.NOON, zone).toInstant()
