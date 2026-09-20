package dev.peashoot.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * A Messages request split at the one place a re-issue after a cut stream differs from the request
 * it re-issues (ADR 0002, `docs/adr/0002-resume-continuation-match.md`). Claude Code 2.1.278 was
 * measured re-issuing a mid-stream drop with everything byte-equal but the content of the last
 * `user` message, where it appends a newline to the last block and one new text block after it
 * (`docs/research/claude-code-stream-drop-retry.md`).
 *
 * The stem is the normalized request without that content. It is kept as its fingerprint alone,
 * which is the trust an equal replay fingerprint already asks for, because an exchange waiting to
 * be resumed would otherwise pin a whole request, system prompt and tool list included, for the
 * length of the window. The blocks are kept and compared exactly: the hash finds a candidate and
 * never decides a match.
 *
 * Nothing here reads the wording of the appended block: it is a client constant, and a match that
 * quoted it would stop working at the client's next release.
 */
class Continuable
private constructor(private val stem: String, private val blocks: List<JsonObject>) {
    /**
     * Whether [reissue] is this request sent again after its stream was cut: the same stem, and
     * these blocks a prefix of its blocks, where its copy of the last of them may carry more
     * trailing whitespace in `text` and every block after it is a text block. A changed earlier
     * block, a removed one, or an appended image or tool result is a different request.
     *
     * Only the last block gets the whitespace allowance, because that is the only place the client
     * was seen to add any; a turn whose last block is a tool result was never measured.
     */
    fun continuedBy(reissue: Continuable): Boolean {
        if (blocks.isEmpty() || reissue.blocks.size < blocks.size || stem != reissue.stem) {
            return false
        }
        val kept = reissue.blocks.take(blocks.size)
        val appended = reissue.blocks.drop(blocks.size)
        return kept.dropLast(1) == blocks.dropLast(1) &&
            kept.last().padsOut(blocks.last()) &&
            appended.all { it["type"].text() == TEXT }
    }

    companion object {
        private const val TEXT = "text"
        private const val CONTENT = "content"
        private const val MESSAGES = "messages"

        /**
         * The split of a [Rules.normalized] request, or null when it has none: a body that is not
         * JSON, no `user` message, or a last one whose content is a plain string or holds anything
         * but objects. Such a request resumes on an equal fingerprint only, since no client was
         * measured extending one.
         */
        fun of(normalized: JsonObject): Continuable? {
            val body = normalized[NORMALIZED_BODY] as? JsonObject
            val messages = (body?.get(MESSAGES) as? JsonArray).orEmpty()
            val index = messages.indexOfLast { (it as? JsonObject)?.get("role").text() == "user" }
            val message = messages.getOrNull(index) as? JsonObject
            val content = message?.get(CONTENT) as? JsonArray
            val blocks = content?.filterIsInstance<JsonObject>()
            if (message == null || blocks == null || blocks.size != content.size) return null
            val without = messages.mapIndexed { i, each ->
                if (i == index) JsonObject(message - CONTENT) else each
            }
            val stem = JsonObject(body.orEmpty() + (MESSAGES to JsonArray(without)))
            return Continuable(
                fingerprintOf(JsonObject(normalized + (NORMALIZED_BODY to stem))),
                blocks,
            )
        }

        /** This block is [original], or [original] with nothing but whitespace after its text. */
        private fun JsonObject.padsOut(original: JsonObject): Boolean {
            val mine = this[TEXT].text()
            val theirs = original[TEXT].text()
            val padding =
                if (mine != null && theirs != null && mine.startsWith(theirs)) {
                    mine.substring(theirs.length)
                } else null
            return this == original ||
                (padding?.isBlank() == true && this - TEXT == original - TEXT)
        }
    }
}
