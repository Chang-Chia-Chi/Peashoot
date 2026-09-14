package dev.peashoot.core

/** Every provider quotes a rate per million tokens, so a cost divides by this once. */
private const val PER_MILLION = 1_000_000.0

/** USD per million tokens. Cache write is the 5-minute rate (1.25x input). */
data class Price(
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheWrite: Double,
)

/**
 * Bundled prices keyed by model-id prefix; the longest key that names the model id wins, where a
 * key names an id only up to a dash, so a new point release cannot inherit an older one's price.
 */
val DEFAULT_PRICES: Map<String, Price> =
    prices(
        listOf("claude-fable-5-1", "claude-mythos-5-1") to
            Price(input = 10.0, output = 50.0, cacheRead = 0.25, cacheWrite = 12.5),
        listOf("claude-fable-5", "claude-mythos-5") to
            Price(input = 10.0, output = 50.0, cacheRead = 1.0, cacheWrite = 12.5),
        listOf(
            "claude-opus-5",
            "claude-opus-4-8",
            "claude-opus-4-7",
            "claude-opus-4-6",
            "claude-opus-4-5",
        ) to Price(input = 5.0, output = 25.0, cacheRead = 0.5, cacheWrite = 6.25),
        listOf("claude-opus-4-1", "claude-opus-4-0", "claude-opus-4-20250514") to
            Price(input = 15.0, output = 75.0, cacheRead = 1.5, cacheWrite = 18.75),
        listOf("claude-sonnet-5") to
            Price(input = 2.0, output = 10.0, cacheRead = 0.2, cacheWrite = 2.5),
        listOf(
            "claude-sonnet-4-6",
            "claude-sonnet-4-5",
            "claude-sonnet-4-0",
            "claude-sonnet-4-20250514",
            "claude-3-7-sonnet",
            "claude-3-5-sonnet",
        ) to Price(input = 3.0, output = 15.0, cacheRead = 0.3, cacheWrite = 3.75),
        listOf("claude-haiku-4-5") to
            Price(input = 1.0, output = 5.0, cacheRead = 0.1, cacheWrite = 1.25),
        listOf("claude-3-5-haiku") to
            Price(input = 0.8, output = 4.0, cacheRead = 0.08, cacheWrite = 1.0),
        listOf("claude-3-haiku") to
            Price(input = 0.25, output = 1.25, cacheRead = 0.03, cacheWrite = 0.3),
        listOf("claude-3-opus") to
            Price(input = 15.0, output = 75.0, cacheRead = 1.5, cacheWrite = 18.75),
    )

/** Null when the model is unknown to [prices] or there is no usage. */
fun costUsd(model: String?, usage: Usage?, prices: Map<String, Price> = DEFAULT_PRICES): Double? {
    val prefix = model?.let { id ->
        prices.keys.filter { id.named(it) }.maxByOrNull(String::length)
    }
    if (prefix == null || usage == null) return null
    // ponytail: a 1-hour cache write costs 2x input, not 1.25x, and is billed here at the
    // 5-minute rate. Upgrade when the reader takes the `cache_creation` breakdown apart.
    val rate = prices.getValue(prefix)
    val dollars =
        usage.input * rate.input +
            usage.output * rate.output +
            usage.cacheRead * rate.cacheRead +
            usage.cacheWrite * rate.cacheWrite
    return dollars / PER_MILLION
}

/**
 * A key names a model when it is the id or a whole dash-separated head of it, so `claude-opus-4-1`
 * prices `claude-opus-4-1-20250805` and leaves `claude-opus-4-10` unpriced.
 */
private fun String.named(key: String): Boolean = this == key || startsWith("$key-")

private fun prices(vararg groups: Pair<List<String>, Price>): Map<String, Price> =
    groups.flatMap { (models, price) -> models.map { it to price } }.toMap()
