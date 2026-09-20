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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

// The cassette export dialog (#23): pick what to export, see what redaction would strip, and only
// then write it. The preview is `POST /cassettes/export?dryRun=true`, which is the export itself
// with the file left unwritten, so what is previewed is what is written and not a second guess at
// it. Nothing here is ever logged: a preview's `matched` is the secret a redaction rule exists to
// strip, masked by the proxy and still not something to put in a log file.

/** How many redaction hits are shown before the rest are counted instead. */
private const val LISTED_HITS = 20

/** What an export is of: the name it is written under, and the session it is narrowed to. */
internal data class Selection(val name: String, val session: String)

/** An export answer as a screen shows it, and where it says it wrote; null path for a dry run. */
internal data class ExportAnswer(val lines: List<String>, val path: String?)

/**
 * What would be exported, what redaction would strip out of it, and then the file.
 *
 * The preview is of one selection and no other: change the name or the session and [previewed] no
 * longer matches, [mayWrite] is false, and the export button goes out. That is the whole of
 * "preview first, then write" — a preview of some other selection is not a preview of this one, and
 * the button being lit is the only claim the window makes about what is about to be written.
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
     * Whether the export button is offered: a previewed selection, still the one in the fields, and
     * a name to write it under. A blank name is the proxy's to refuse, but there is no sense in
     * sending it.
     */
    val mayWrite: Boolean
        get() = previewed == selection && selection.name.isNotBlank()

    /** A keystroke in either field. A preview is of one selection, so changing it retires one. */
    fun select(name: String, session: String) {
        val wanted = Selection(name, session)
        if (wanted == selection) return
        selection = wanted
        previewed = null
        preview.clear()
        written = null
        note = null
    }

    /** The export, with the file left unwritten: what would go in it and what would be stripped. */
    fun dryRun() {
        // Captured before the call, so an answer is attributed to the selection it was asked for.
        // Nothing can change it under a call in flight today — a keystroke and a fold are both on
        // the window's thread and [Panel] allows one call at a time — but the fold reading a field
        // the user is typing in would be a bug waiting for the day either changes.
        val of = selection
        start("asking what an export would strip") { client ->
            client
                .send(HttpMethod.Post, "/cassettes/export?dryRun=true", bodyOf(of))
                .fold(
                    { answer ->
                        val read = exportLines(answer)
                        preview.clear()
                        preview.addAll(read?.lines.orEmpty())
                        // Unreadable is not previewed: the export button stays out rather than
                        // lighting up over a preview nobody could show.
                        previewed = if (read == null) null else of
                        note =
                            if (read == null) "the proxy answered a preview this window cannot read"
                            else null
                    },
                    { note = whyNot("preview the export", it) },
                )
        }
    }

    /** The cassette itself, refused outright for a selection no preview has been shown for. */
    fun write() {
        if (!mayWrite) {
            note = "preview this export before writing it"
            return
        }
        val of = selection
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
                                else -> null
                            }
                    },
                    { note = whyNot("write ${of.name}", it) },
                )
        }
    }
}

/**
 * An export answer as lines: how much went in, how much was stripped, and each hit as the proxy
 * masked it. Null for an answer this window cannot read, which must not be shown as an export with
 * nothing to strip.
 *
 * `where` is a pointer into the record — "request body", "response frame 3" — and never a path out
 * of the repository, so nothing here goes through [pathLabel]: there is no path in a preview for
 * the show-paths toggle to hide. `matched` is already masked where it was made, which is where it
 * had to be: half of a short match at most, and a length.
 */
internal fun exportLines(body: String): ExportAnswer? = runCatching {
    val answer = Json.parseToJsonElement(body) as JsonObject
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
        path?.let { add("written to $it") }
    }
    ExportAnswer(lines, path)
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
