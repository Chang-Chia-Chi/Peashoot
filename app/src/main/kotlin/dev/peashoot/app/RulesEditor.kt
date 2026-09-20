package dev.peashoot.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.peashoot.app.farm.scalar
import dev.peashoot.core.text
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull

// The rules editor (#23): the rule set as text, what a test said about it, and whether it may be
// saved yet. The rule file is data meant to be read and edited — `core.Rules`' own KDoc says so —
// so the editor is a text box over its JSON and not a form over three lists, which would be this
// window's opinion of a file the proxy validates.

/** Enough of a fingerprint to tell two apart at a glance, and not enough to retype by mistake. */
private const val FINGERPRINT_DIGITS = 12

/** How many collisions or splits are listed before the rest are counted instead. */
private const val LISTED_GROUPS = 20

/** What `POST /rules/test` said: the lines to show, and whether any is a reason not to save. */
internal data class RuleTest(val lines: List<String>, val risky: Boolean)

/**
 * The rule set as the proxy serves it, the draft over it, and the test between the two.
 *
 * Test-before-save, read as narrowly as the wording allows and no more: save is not *offered* for a
 * draft the proxy has not tested since its last edit — [mayTry] is false and the button is drawn
 * disabled — and a test that found a collision or a split has to be confirmed before it saves,
 * since a collision is a replay that will answer the wrong request and the user should have to say
 * that is what they meant. A test that found neither saves on the first press: making every save
 * two-handed would teach the hand to press twice and cost the warning its meaning.
 *
 * The confirmation is [confirm], a box to tick, and not a second press of the same button. A second
 * press is what the first cut of this did, and a review was right that a held Enter on a focused
 * button presses it twice before the warning beside it has been read — so the confirmation was
 * defeated by key repeat, which is the one thing it exists to survive. A tick cannot be produced by
 * repeating the press it is guarding.
 *
 * [saveBlocked] is where both halves of that live, as one pure function, so the rule is asserted
 * rather than read off the shape of a screen.
 *
 * [onSaved] is what a save changes outside this panel: an export's redaction runs under the rules
 * the proxy holds when it runs, so a preview taken under the old ones is no longer a preview.
 */
internal class RulesModel(private val onSaved: () -> Unit) : Panel() {
    /** The editor's text: whatever is in the box, valid JSON or not. */
    var draft by mutableStateOf("")
        private set

    /** What `GET /rules` last served, which [revert] goes back to. */
    var saved by mutableStateOf<String?>(null)
        private set

    /** The draft text a test was run against, or null for a draft nothing has tested. */
    var tested by mutableStateOf<String?>(null)
        private set

    /** What that test said, line by line. */
    val outcome = mutableStateListOf<String>()

    /** Whether that test found a collision or a split, which is what asks to be confirmed. */
    var risky by mutableStateOf(false)
        private set

    /** Whether the box beside a risky draft's save button has been ticked. */
    var confirming by mutableStateOf(false)
        private set

    /** Whether the save button is offered at all: only a draft as it was tested may be sent. */
    val mayTry: Boolean
        get() = tested != null && draft == tested

    /**
     * Why the next press will not save, or null when it will. One line, shown beside the button.
     */
    val blocked: String?
        get() = saveBlocked(draft, tested, risky, confirming)

    /** The rule set the proxy is matching on, which is also the draft's starting point. */
    fun load() =
        start("asking the proxy for its rules") { client ->
            client
                .send(HttpMethod.Get, "/rules")
                .fold(
                    { answer ->
                        saved = pretty(answer)
                        draft = saved.orEmpty()
                        forget()
                        note = null
                    },
                    { note = whyNot("read the rules", it) },
                )
        }

    /** A keystroke. A test is about the text it was run against, so an edit retires it. */
    fun edit(text: String) {
        draft = text
        if (text != tested) forget()
    }

    /** The draft back to what the proxy serves, which is also throwing the test away. */
    fun revert() {
        draft = saved.orEmpty()
        forget()
        note = if (saved == null) "nothing has been read from the proxy yet" else null
    }

    /**
     * What this draft would do to the proxy's newest live recordings, saving nothing. `lastN` is
     * left off, so the endpoint's own default decides how many — a number this window has no better
     * opinion about than the proxy that holds the rows.
     */
    fun test() =
        start("testing the draft against the proxy's recordings") { client ->
            // Captured before the call and sent as it was typed: a draft that is not JSON is the
            // proxy's to refuse in the parser's own words. The offsets in that refusal are against
            // this wrapper rather than the draft alone, which is the price of the endpoint taking
            // the rule set as a field; `PUT /rules` takes the text itself and has no such shift.
            val asked = draft
            client
                .send(HttpMethod.Post, "/rules/test", """{"rules":$asked}""")
                .fold(
                    { answer ->
                        val read = testLines(answer)
                        outcome.clear()
                        outcome.addAll(read?.lines.orEmpty())
                        // An answer that cannot be read is not a clean test: leaving [tested] null
                        // is what keeps save unoffered, rather than letting an unreadable answer
                        // through as though nothing would collide.
                        tested = if (read == null) null else asked
                        risky = read?.risky == true
                        confirming = false
                        note =
                            if (read == null) "the proxy answered a test this window cannot read"
                            else null
                    },
                    {
                        note = whyNot("test the draft", it)
                        forget()
                    },
                )
        }

    /** The box beside a risky draft's save button, which is the second, deliberate say-so. */
    fun confirm(yes: Boolean) {
        confirming = yes
    }

    /**
     * The draft, saved. A draft nothing has tested never reaches here from the window, because the
     * button is not offered — and is refused here anyway, since a guard on a screen is not a rule.
     */
    fun save() {
        val why = saveBlocked(draft, tested, risky, confirming)
        if (why != null) {
            note = why
            return
        }
        // Captured before the call, as [test] captures it and for the same reason: the lambda is
        // dispatched rather than run here, so a key already on its way to the box would otherwise
        // be what gets written.
        val asked = draft
        start("saving the rules") { client ->
            client
                .send(HttpMethod.Put, "/rules", asked)
                .fold(
                    { answer ->
                        saved = pretty(answer)
                        draft = saved.orEmpty()
                        forget()
                        note = "the proxy is matching on these rules now"
                        onSaved()
                    },
                    { note = whyNot("save the rules", it) },
                )
        }
    }

    /** Everything a test left behind, gone: what it said, what it was of, and its confirmation. */
    private fun forget() {
        outcome.clear()
        tested = null
        risky = false
        confirming = false
    }
}

/**
 * Why this draft may not be saved yet, or null when the next press should save it. Three states and
 * not two: a draft nothing has tested is not offered at all, a tested draft with a collision or a
 * split in it wants the box ticked first, and anything else goes on the first press.
 */
internal fun saveBlocked(
    draft: String,
    tested: String?,
    risky: Boolean,
    confirming: Boolean,
): String? =
    when {
        draft != tested -> "test this draft before saving it"
        risky && !confirming -> "that test found collisions or splits; tick the box to save it"
        else -> null
    }

/**
 * A `{tested, collisions, splits}` answer as lines, and whether it is a reason not to save. Null
 * for an answer this window cannot read, which must never be shown as a clean test: "nothing would
 * collide" is the one wrong thing to say about an answer nobody parsed.
 *
 * A collision is exchanges that would share a fingerprint and do not now — a replay answering the
 * wrong request — and a split is exchanges that share one now and would not, which is a replay that
 * stops hitting. Both are the proxy's own words for them; this only writes them down.
 */
internal fun testLines(body: String): RuleTest? = runCatching {
    val answer = Json.parseToJsonElement(body) as JsonObject
    // A count and not a string: `text()` answers null for a number, and a test that says how many
    // recordings it ran against is the one number on the answer worth reading.
    val counted = checkNotNull(answer.scalar("tested") { intOrNull })
    val collisions = (answer.getValue("collisions") as JsonArray).filterIsInstance<JsonObject>()
    val splits = (answer.getValue("splits") as JsonArray).filterIsInstance<JsonObject>()
    val lines = buildList {
        add("$counted recordings tested")
        if (collisions.isEmpty() && splits.isEmpty()) {
            add("nothing would collide and nothing would split")
        }
        addAll(
            collisions.take(LISTED_GROUPS).map {
                "collision ${short(it["fingerprint"].text())}: ${ids(it["exchangeIds"])}"
            }
        )
        more("collision", collisions.size)?.let(::add)
        addAll(
            splits.take(LISTED_GROUPS).map { split ->
                val groups = (split["groups"] as? JsonArray).orEmpty()
                "split ${short(split["fingerprint"].text())}: " +
                    groups.joinToString(" | ") { ids(it) }
            }
        )
        more("split", splits.size)?.let(::add)
    }
    RuleTest(lines, collisions.isNotEmpty() || splits.isNotEmpty())
}
    .getOrNull()

/** The exchange ids in one group, however the answer spelled them. */
private fun ids(group: JsonElement?): String =
    (group as? JsonArray).orEmpty().mapNotNull { it.text() }.joinToString(", ")

/** A list that simply ends looks like a complete list, and this one is cut at [LISTED_GROUPS]. */
private fun more(what: String, size: Int): String? =
    "… and ${size - LISTED_GROUPS} more $what groups".takeIf { size > LISTED_GROUPS }

private fun short(fingerprint: String?): String =
    fingerprint?.take(FINGERPRINT_DIGITS) ?: "no fingerprint given"
