package dev.peashoot.proxy

import dev.peashoot.core.Exchange
import dev.peashoot.core.Frame
import dev.peashoot.core.Responses
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseQueryString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter

/**
 * The Responses resume cursor, read off the request and applied to a buffered answer (#27).
 *
 * `GET /v1/responses/{id}?stream=true&starting_after=N` asks for the events of response `{id}`
 * whose `sequence_number` is greater than N. OpenAI's own schema is the whole of the contract: the
 * parameter is an `integer` described as "The sequence number of the event after which to start
 * streaming", with no documented minimum, no documented behaviour past the last event of a finished
 * response, and — for `GET /v1/responses/{id}` — no 400 declared at all (`openai/openai-openapi`
 * `openapi.yaml`, read 2026-09-20). So the exclusive reading is the documented one and everything
 * else below is this proxy's own choice, said out loud rather than guessed at silently.
 *
 * Why this is not on `Surface`. Only this surface has sequence numbers and only this file asks for
 * them, so a `Surface.cursor…` would be one method with one implementation and two no-ops. It is
 * not in `core` either: it reads an [Exchange]'s path and answers with a [Refusal], and the
 * ArchUnit rule keeps the server out of `core`.
 */

/** Where a response is created; everything under `$RESPONSE_PATH/` is one response's own. */
private const val RESPONSE_PATH = "/v1/responses"
private const val RESPONSE_PREFIX = "$RESPONSE_PATH/"

private const val STARTING_AFTER = "starting_after"
private const val STREAM = "stream"

/**
 * The cursor of a client that named no `starting_after`: every event of the response, since the
 * first one ever sent is still after this. The API documents no minimum and our own frames make no
 * assumption about where a provider starts counting — OpenAI's examples begin at 1 and the fixtures
 * here begin at 0 — so "before anything" is one below the lowest number a non-negative cursor can
 * name, and not a number any event could carry.
 */
private const val FROM_THE_START = -1

/**
 * Whether this exchange is the create whose own stream is the whole of one response, which is the
 * only kind that may ever answer a cursor.
 *
 * This is not belt and braces. Resume taps what it *serves* as well as what it relays — #26's "a
 * laptop that sleeps twice" — so a cursor's own answer is buffered too, and that answer is a
 * filtered tail carrying the same response id as the original, on `response.completed` among
 * others. Without this the tail would offer itself to the next cursor as though it were the whole
 * response, and a cursor asking from further back would be served `sequence_number > max(N, M)`:
 * every event between the two numbers silently gone, under a 200 and a clean end. A get-by-id and a
 * cancel answer with the bare response object and would be stamped with its id for the same reason;
 * neither holds a stream at all.
 */
internal fun Exchange.createsResponse(): Boolean =
    request.method == "POST" && request.path.substringBefore('?') == RESPONSE_PATH

/**
 * The response this request asks for the events of, or null when it is not a cursor at all.
 *
 * Four things must hold, and each rules out a request that looks similar. A `GET`, because a create
 * is a `POST` and is matched by fingerprint instead. The Responses surface, since no other has this
 * parameter. A single path segment after `/v1/responses/`, which is what keeps `POST
 * /v1/responses/{id}/cancel` out — a cancel is an instruction to the provider and can never be
 * answered from a buffer. And `stream=true`, because without it the API answers one JSON response
 * object and our buffer holds SSE frames; handing a client that asked for a document a stream of
 * events is the same mismatch #26 refuses on `stream` for a re-issued POST.
 *
 * Ktor's query parser decides two details of that last test: parameter *names* are matched without
 * regard to case, so `?STREAM=true` is a cursor, while the value is compared exactly, so
 * `?stream=TRUE` is not. The id is taken from the path as written and never percent-decoded, so an
 * encoded id simply fails to match a buffered one and the request is relayed — a miss, never a
 * wrong answer.
 */
internal fun Exchange.cursorId(): String? {
    val path = request.path.substringBefore('?')
    if (request.method != "GET" || surface != Responses || !path.startsWith(RESPONSE_PREFIX)) {
        return null
    }
    val id = path.removePrefix(RESPONSE_PREFIX)
    return id.takeIf { it.isNotEmpty() && '/' !in it && query()[STREAM] == "true" }
}

/**
 * Which event this cursor asks from: [FROM_THE_START] when it names none, and null when it names
 * something that is no sequence number — a word, a fraction, or a negative, none of which can point
 * at a place in a stream. Null is what the caller answers [badCursor] to.
 *
 * A whole number too large for an `Int` is *not* one of those. It is a perfectly well-formed cursor
 * that simply sits past the end of any response, so it is clamped rather than refused, and the
 * client gets the empty, properly ended stream that any other out-of-range cursor gets. Refusing it
 * would have contradicted that rule for no reason a client could see.
 *
 * Two shapes the query parser decides rather than this function, both harmless and neither obvious:
 * a repeated `starting_after` takes the first, and a bare `?starting_after` with no `=` at all is
 * read as *absent* where `?starting_after=` with an empty value is read as present and refused.
 */
internal fun Exchange.startingAfter(): Int? {
    val raw = query()[STARTING_AFTER] ?: return FROM_THE_START
    return raw.toLongOrNull()?.takeIf { it >= 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
}

/**
 * How a cursor that named no sequence number is refused: the proxy's own error shape, 400, the same
 * one `bad_content_type` and `upstream_unreachable` use, and never a provider's — a client's retry
 * logic must not mistake us for one. OpenAI documents no 400 for this endpoint and no validation of
 * the parameter, so there is nothing here to imitate even if we wanted to. Refusing is better than
 * relaying: the value can never name a place in any stream, whoever answers it.
 */
internal fun Exchange.badCursor(): Refusal =
    Refusal(
        HttpStatusCode.BadRequest.value,
        "bad_starting_after",
        mapOf("detail" to query()[STARTING_AFTER].orEmpty()),
    )

/**
 * Only the frames of a buffered answer that come after sequence number [sequence].
 *
 * A frame with no readable `sequence_number` is kept. Every streamed Responses event carries one —
 * it is `required` on every event schema in OpenAI's own spec — so the frames that reach this
 * without one are the ones the grammar did not produce: above all the unterminated tail of a cut
 * stream, which [dev.peashoot.core.FrameParser.end] hands over as a frame of its own and which sits
 * after every numbered frame there is. Dropping those would delete provider bytes on the say-so of
 * a field that is simply absent; keeping them can at worst repeat bytes the client already had, and
 * only for a frame no numbered filter could have placed either way. The proxy's own keep-alive
 * comments never reach here at all: the client writer adds those, downstream of the buffer. A
 * comment the *provider* sent does reach here, parses to no JSON, and is kept wherever it sat, so a
 * cursor at 500 re-emits the heartbeats from before it at the head of its stream — which is the
 * "repeat bytes" case above, and costs nothing, since an SSE client ignores comment lines.
 *
 * ponytail: every frame is parsed as JSON again here, once per cursor, where the Deriver's reader
 * has already parsed the same text for the same response. A cursor over a thousand-event answer is
 * a thousand small parses, which is microseconds against a stream that took seconds to arrive, and
 * it keeps [dev.peashoot.core.Frame] what it is — bytes nothing has to understand. Upgrade: the
 * buffer remembering each frame's number as it appends, if a cursor is ever on a hot path.
 */
internal fun Flow<Frame>.after(sequence: Int): Flow<Frame> = filter { frame ->
    val number = Responses.sequenceNumber(frame)
    number == null || number > sequence
}

/** The request's query string as parameters; empty for a path that carries none. */
private fun Exchange.query() = parseQueryString(request.path.substringAfter('?', ""))
