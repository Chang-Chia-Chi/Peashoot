package dev.peashoot.app.render

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MILLI = 1_000_000L
private const val SMOOTH_MS = 16L
private const val SLOW_MS = 40L
private const val STALL_MS = 400L

/** Longer than the meter's whole window: a laptop that woke up, not a farm that got slower. */
private const val PAUSE_MS = 3_000L

/**
 * The deliberate trigger `docs/research/canvas-frame-rate.md` §10 asked for: the degrade cannot be
 * reached at realistic counts on the development machine, so synthetic frame times are what test
 * it.
 */
class FrameMeterTest {
    @Test
    fun `a run of slow frames degrades`() {
        val meter = FrameMeter()
        assertFalse(meter.frame(SLOW_MS * MILLI), "one frame is not a verdict")
        repeat(60) { meter.frame(SLOW_MS * MILLI) }
        assertTrue(meter.degraded)
    }

    @Test
    fun `a run of smooth frames recovers`() {
        val meter = FrameMeter()
        repeat(60) { meter.frame(SLOW_MS * MILLI) }
        assertTrue(meter.degraded)
        repeat(120) { meter.frame(SMOOTH_MS * MILLI) }
        assertFalse(meter.degraded)
    }

    @Test
    fun `a pause is not a frame rate`() {
        val meter = FrameMeter()
        repeat(60) { meter.frame(SMOOTH_MS * MILLI) }
        assertFalse(meter.frame(PAUSE_MS * MILLI), "a window dragged or a laptop resumed")
        repeat(5) { meter.frame(SMOOTH_MS * MILLI) }
        assertFalse(meter.degraded, "and the villagers do not teleport for the seconds after")
    }

    @Test
    fun `one slow frame among smooth ones changes nothing either way`() {
        val meter = FrameMeter()
        repeat(120) { meter.frame(SMOOTH_MS * MILLI) }
        assertFalse(meter.frame(STALL_MS * MILLI), "a single stall is not a degrade")
        repeat(4) { meter.frame(SMOOTH_MS * MILLI) }
        assertFalse(meter.degraded)
    }
}
