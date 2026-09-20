package dev.peashoot.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.peashoot.app.farm.scalar
import dev.peashoot.core.text
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

// The cassette export dialog (#23): pick what to export, see what redaction would strip, and only
// then write it. The preview is `POST /cassettes/export?dryRun=true`, which is the same export run
// with the file left unwritten, rather than a second guess at it. Nothing here is ever logged: a
// preview's `matched` is the secret a redaction rule exists to strip, masked by the proxy and still
// not something to put in a log file.

/**
 * How many redaction hits are shown before the rest are counted instead.
 *
 * ponytail: twenty, and the rest are a number — which is also how far the check comparing a write
 * against its preview can see, since it compares the lines. An export whose twenty-first hit
 * changed under a rule this window did not hear about would pass that check. Upgrade: a scrolling
 * list, and a comparison over the whole `preview` array rather than the lines drawn from it.
 */
private const val LISTED_HITS = 20

/**
 * What an export is of: the name it is written under, and the session it is narrowed to.
 *
 * ponytail: one session, where `POST /cassettes/export` takes `sessionIds` and `exchangeIds` and
 * narrows by both. One text field covers "this session" and "everything", which is what anyone
 * sharing a cassette asks for; picking several sessions, or a handful of exchanges out of one,
 * needs a list to pick from and is a dialog rather than a field. Upgrade: a picker over `GET
 * /sessions`, which the window already has a use for.
 */
internal data class Selection(val name: String, val session: String)

/**
 * An export answer as a screen shows it: the lines, where it says it wrote — null for a dry run —
 * and the two counts, which are what a write is checked against the preview by.
 */
internal data class ExportAnswer(
    val lines: List<String>,
    val path: String?,
    val exchanges: Int,
    val redactions: Int,
)

/**
 * What would be exported, what redaction would strip out of it, and then the file.
 *
 * The preview is of one selection and no other: change the name or the session and [previewed] no
 * longer matches, [mayWrite] is false, and the export button goes out. A preview of some other
 * selection is not a preview of this one.
 *
 * What the lit button claims, exactly: the proxy was asked this same question a moment ago and
 * answered *that*. It cannot claim more, and the KDoc used to. The export runs on the proxy against
 * the store and the rules as they are when it runs, so a rule saved in the editor above, or a turn
 * recorded in between, can make the file differ from what was shown. The first of those is why
 * [invalidate] exists — a save in the editor puts this preview out — and the second cannot be
 * closed from here at all, so a write that comes back with different counts says so rather than
 * letting the preview stand as a description of the file.
 *
 * The cassette is written where the proxy writes it, under the data directory's `cassettes/`, and
 * the path it answers with is what is shown. No file dialog: the file is the proxy's, on the
 * proxy's machine, and the CLI's `proxy export` writes the same one.
 */
internal class ExportModel : Panel() {
    /** What the two fields say, which is what an export would be of. */
    var selection by mutableStateOf(Selection("", ""))
        private set

    /** The redaction preview, line by line, for [previewed] and for nothing else. */
    val preview = mutableStateListOf<String>()

    /** The selection [preview] is of; null until a dry run has answered for the one in hand. */
    var previewed by mutableStateOf<Selection?>(null)
        private set

    /** Where the proxy says it wrote the cassette, once it has. */
    var written by mutableStateOf<String?>(null)
        private set

    /**
     * Whether a cassette of this name is already there, which an export would replace. Read from
     * `GET /cassettes` at the same time as the preview, because it is the same question — what is
     * about to happen to the disk — and asking it at the write would be asking too late.
     */
    var replaces by mutableStateOf(false)
        private set

    /** Whether the box beside a replacing export has been ticked. */
    var confirming by mutableStateOf(false)
        private set

    /** The whole of the last preview's answer, which a write is compared against. */
    private var shown: ExportAnswer? = null

    /**
     * Whether the export button is offered: a previewed selection, still the one in the fields, a
     * name to write it under, and — where that name is a cassette that already exists — the box
     * ticked. A blank name is the proxy's to refuse, but there is no sense in sending it.
     *
     * The proxy overwrites `cassettes/<name>.jsonl` without a word, so this window is the only
     * place that can say so before it happens. The tick is the same shape as the rules editor's,
     * and for the same reason: a thing that cannot be undone is worth saying twice.
     */
    val mayWrite: Boolean
        get() = previewed == selection && selection.name.isNotBlank() && (!replaces || confirming)

    /** A keystroke in either field. A preview is of one selection, so changing it retires one. */
    fun select(name: String, session: String) {
        val wanted = Selection(name, session)
        if (wanted == selection) return
        selection = wanted
        forget()
        note = null
    }

    /**
     * The preview put out by something other than a keystroke: a rule set saved in the editor
     * above, which changes what an export of the very same selection would strip out of it.
     */
    internal fun invalidate() {
        if (previewed == null) return
        forget()
        note = "the rules changed; preview this export again"
    }

    /** The box beside a replacing export, which is the say-so that the old file may go. */
    fun confirm(yes: Boolean) {
        confirming = yes
    }

    /** The export, with the file left unwritten: what would go in it and what would be stripped. */
    fun dryRun() {
        // Captured before the call, so an answer is attributed to the selection it was asked for.
        // Nothing can change it under a call in flight today — a keystroke and a fold are both on
        // the window's thread and [Panel] allows one call at a time — but the fold reading a field
        // the user is typing in would be a bug waiting for the day either changes.
        val of = selection
        // A fresh preview is not a description of the file already on disk, so the path a previous
        // export answered with goes with it rather than sitting under new lines it does not match.
        // The tick goes too: a say-so is about the preview that was on screen when it was given.
        written = null
        confirming = false
        start("asking what an export would strip") { client ->
            // Asked first, so that a preview and what it would land on are one answer. A listing
            // that cannot be read leaves [replaces] false and the export unguarded, which is what
            // the proxy would have done anyway; it is not worth refusing an export over.
            replaces =
                client
                    .send(HttpMethod.Get, "/cassettes")
                    .fold({ of.name in cassetteNames(it).orEmpty() }, { false })
            client
                .send(HttpMethod.Post, "/cassettes/export?dryRun=true", bodyOf(of))
                .fold(
                    { answer ->
                        val read = exportLines(answer)
                        preview.clear()
                        preview.addAll(read?.lines.orEmpty())
                        // Unreadable is not previewed: the export button stays out rather than
                        // lighting up over a preview nobody could show.
                        shown = read
                        previewed = if (read == null) null else of
                        note =
                            when {
                                read == null ->
                                    "the proxy answered a preview this window cannot read"
                                replaces -> "this replaces the cassette ${of.name} already there"
                                else -> null
                            }
                    },
                    { note = whyNot("preview the export", it) },
                )
        }
    }

    /**
     * The cassette itself, refused outright for a selection no preview has been shown for, and for
     * a name that is already a cassette until the box beside it says the old one may go.
     */
    fun write() {
        if (!mayWrite) {
            note =
                if (previewed == selection && replaces && !confirming) {
                    "tick the box to replace the cassette ${selection.name}"
                } else "preview this export before writing it"
            return
        }
        val of = selection
        val promised = shown
        start("writing ${of.name}") { client ->
            client
                .send(HttpMethod.Post, "/cassettes/export", bodyOf(of))
                .fold(
                    { answer ->
                        val read = exportLines(answer)
                        written = read?.path
                        note =
                            when {
                                read == null ->
                                    "the cassette was written, but the answer was not " +
                                        "one this window can read"
                                read.path == null -> "the proxy wrote no file it would name"
                                // The export ran again on the proxy, under whatever rules and
                                // recordings it has now. Saying so is the only thing this window
                                // can do about a file that is not what was previewed.
                                read.lines != promised?.lines ->
                                    "what was written is not what was previewed: it says " +
                                        "${read.exchanges} exchanges and ${read.redactions} " +
                                        "redactions. Preview it again to see what changed"
                                else -> null
                            }
                    },
                    { note = whyNot("write ${of.name}", it) },
                )
        }
    }

    /** Everything a preview left behind, gone, and with it the right to press export. */
    private fun forget() {
        previewed = null
        preview.clear()
        shown = null
        written = null
        replaces = false
        confirming = false
    }
}

/**
 * An export answer as lines: how much went in, how much was stripped, and each hit as the proxy
 * masked it. Null for an answer this window cannot read, which must not be shown as an export with
 * nothing to strip.
 *
 * The lines are what a write is compared against, so the path is *not* one of them — a dry run has
 * none and a write does, and a line the two can never share would make every write look changed.
 * That comparison sees the counts and the first [LISTED_HITS] things stripped, which is what the
 * lines hold: it catches a rule change that alters what is redacted near the top of the list or how
 * much of it there is, and it cannot catch one that alters only the twenty-first hit onward.
 *
 * `where` is a pointer into the record — "request body", "response frame 3", or a pointer built
 * from the body's own key names — and never a path out of the repository, so nothing here goes
 * through [pathLabel]. `matched` is already masked where it had to be, at the proxy: half of a
 * short match at most, and a length.
 */
internal fun exportLines(body: String): ExportAnswer? = runCatching {
    val answer = checkNotNull(jsonOf(body))
    val hits = (answer.getValue("preview") as JsonArray).filterIsInstance<JsonObject>()
    val path = answer["path"].text()
    // Counts and not strings: `text()` answers null for a number, and these two are what an export
    // is: how much went in, and how much of it was taken out.
    val exchanges = checkNotNull(answer.scalar("exchanges") { intOrNull })
    val redactions = checkNotNull(answer.scalar("redactions") { intOrNull })
    val lines = buildList {
        add("$exchanges exchanges, $redactions redactions")
        if (hits.isEmpty()) add("nothing in them matches a redaction rule")
        addAll(
            hits.take(LISTED_HITS).map {
                "${it["where"].text()}: ${it["matched"].text()} becomes ${it["becomes"].text()}"
            }
        )
        if (hits.size > LISTED_HITS) add("… and ${hits.size - LISTED_HITS} more")
    }
    ExportAnswer(lines, path, exchanges, redactions)
}
    .getOrNull()

/**
 * The cassette names the proxy already has, from `GET /cassettes`; null for a listing this window
 * cannot read. A name here is a file an export of that name would replace — the listing merges the
 * files in `cassettes/` with the names the store's rows are tagged with, so a name that was only
 * ever imported is in it too, and warning about that one is a warning too many rather than a
 * warning too few.
 */
internal fun cassetteNames(body: String): Set<String>? = runCatching {
    (checkNotNull(jsonOf(body)).getValue("cassettes") as JsonArray)
        .filterIsInstance<JsonObject>()
        .mapNotNull { it["name"].text() }
        .toSet()
}
    .getOrNull()

/** The body both calls send; the dry run differs only in the query the proxy reads. */
private fun bodyOf(selection: Selection): String = buildJsonObject {
    put("name", selection.name)
    // Left out entirely when blank, because an empty list would narrow the export to nothing
    // where saying nothing narrows it not at all.
    if (selection.session.isNotBlank()) {
        put("sessionIds", buildJsonArray { add(JsonPrimitive(selection.session)) })
    }
}
    .toString()
