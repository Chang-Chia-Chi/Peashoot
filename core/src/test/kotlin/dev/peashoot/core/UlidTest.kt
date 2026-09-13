package dev.peashoot.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UlidTest {
    @Test
    fun `encodes the millisecond as the first ten chars, like the spec's example`() {
        // 01ARYZ6S41TSV4RRFFQ69G5FAV is the ULID spec's own example, minted at 1469918176385.
        val id = ulid(nowMillis = 1_469_918_176_385, random = Random(1))
        assertEquals(26, id.length)
        assertEquals("01ARYZ6S41", id.take(10))
        assertTrue(id.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" }, id)
    }

    @Test
    fun `sorts by time, so later exchanges have greater ids`() {
        val earlier = ulid(nowMillis = 1_000, random = Random(7))
        val later = ulid(nowMillis = 1_001, random = Random(7))
        assertTrue(earlier < later, "$earlier should sort before $later")
    }
}
