package dev.peashoot.app.render

/** About a second of frames: long enough that one stall cannot decide, short enough to react. */
private const val WINDOW_NANOS = NANOS_A_SECOND.toLong()

/** Fewer frames than this is not yet a frame rate; it is the first moments of a window opening. */
private const val FEW_FRAMES = 5

/** Degrade below the design's 30 fps, and come back only above 35. */
private const val DEGRADE_FPS = 30.0
private const val RECOVER_FPS = 35.0

/**
 * Whether the farm is drawing too slowly to walk villagers about. Fed one frame's delta at a time
 * and answering over a rolling window, with the gap between [DEGRADE_FPS] and [RECOVER_FPS] there
 * so that a farm sitting exactly on the threshold does not flap between walking and teleporting
 * every few frames — which would look far worse than either.
 *
 * `docs/research/canvas-frame-rate.md` §10 measured that this machine never reaches 30 fps at
 * realistic counts, so [FrameMeterTest]'s synthetic frame times are the deliberate trigger it asked
 * for: without one the degrade would ship untested.
 *
 * That same section suggests watching the median frame *time* instead, because average fps is
 * pinned at the display's refresh and so gives no early warning as load grows. Weighed and
 * declined: that caution is about noticing load while the frame rate is still at the cap, and this
 * is a different question. The threshold is 30 fps, far below any refresh rate, where the average
 * over a second is not pinned to anything and is the design's own wording — "below 30 fps,
 * villagers teleport". A median frame time would answer the same question in less obvious units.
 */
internal class FrameMeter {
    private val deltas = ArrayDeque<Long>()
    private var window = 0L

    var degraded: Boolean = false
        private set

    /**
     * A delta longer than the whole window is a pause — a dragged window, a laptop resumed, a
     * breakpoint — and not a frame rate: it is dropped rather than clamped, because clamping would
     * put a one-second "frame" in the window and teleport every villager for the seconds it takes
     * to fall out again. The cost of dropping it is that a farm genuinely drawing slower than one
     * frame a second never degrades; nothing the degrade does would save that farm anyway.
     */
    fun frame(deltaNanos: Long): Boolean {
        if (deltaNanos !in 1..WINDOW_NANOS) return degraded
        deltas.addLast(deltaNanos)
        window += deltaNanos
        while (deltas.size > FEW_FRAMES && window > WINDOW_NANOS) {
            window -= deltas.removeFirst()
        }
        if (deltas.size >= FEW_FRAMES && window > 0) {
            val fps = deltas.size * NANOS_A_SECOND / window
            degraded = if (degraded) fps < RECOVER_FPS else fps < DEGRADE_FPS
        }
        return degraded
    }
}
