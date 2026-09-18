package dev.peashoot.proxy

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.tomlj.TomlInvalidTypeException

/**
 * What a control call sends, and what to do when it cannot be used. The writing endpoints validate
 * by running the code that reads the same thing at start, so what they must turn into a problem
 * object is what that code throws: a failed `check` or `require`, and tomlj's own refusal of a
 * value of the wrong type. Anything else is still ours, and still a 500.
 */
internal inline fun <T> refusing(block: () -> T): T = runCatching(block).getOrElse { refuse(it) }

internal fun refuse(failure: Throwable): Nothing =
    when (failure) {
        // A cancelled call is nobody's mistake, and CancellationException is an
        // IllegalStateException.
        is CancellationException -> throw failure
        is IllegalStateException,
        is IllegalArgumentException,
        is TomlInvalidTypeException -> badRequest(failure.message ?: "the request cannot be used")
        else -> throw failure
    }

/** The request body as the JSON object every writing endpoint but `PUT /rules` takes. */
internal suspend fun ApplicationCall.bodyObject(): JsonObject =
    refusing { Json.parseToJsonElement(receiveText()) } as? JsonObject
        ?: badRequest("the body must be a JSON object")

/**
 * How many rows a call may ask for, the one bound `limit` and `lastN` share: a page of exchanges
 * and a rule test are the same read, and a caller that learns one learns the other.
 */
internal fun bounded(name: String, asked: String?): Int =
    asked?.let { raw ->
        raw.toIntOrNull()?.takeIf { it in 1..MAX_EXCHANGES_LIMIT }
            ?: badRequest("$name must be 1 to $MAX_EXCHANGES_LIMIT, not $raw")
    } ?: DEFAULT_EXCHANGES_LIMIT
