package dev.peashoot.app

import dev.peashoot.app.farm.FarmState
import dev.peashoot.app.render.FarmScene
import dev.peashoot.app.render.PLOT_CAPACITY
import dev.peashoot.app.render.syntheticFarm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** 200 entities: 40 villagers and 160 crops, the count the spike's headline number is taken at. */
private const val STALL_FIELDS = 14
private const val STALL_LAST_FIELD_CROPS = 4
private const val STALL_VILLAGERS = 40

/** A frame at 165 Hz, which is faster than any display this has been measured on. */
private const val FRAME_NANOS = 6_000_000L

/** How many frames the villagers walk one way before they turn round and walk back. */
private const val TURN_FRAMES = 30L

/** Enough lines that one being swallowed by a busy renderer would show. */
private const val LINES = 20

/**
 * Issue #20's fourth criterion: heavy animation must not stall the control client. The renderer and
 * the feed share one thread by design — `AppModel` says so on [AppModel.tickFarm], and a Compose
 * Desktop frame callback runs on the same UI thread the collector does — so the only thing keeping
 * the feed moving is that a frame hands the thread back when it is done.
 *
 * What this proves, exactly: that [FarmScene.advance]'s per-frame work over a 200-entity farm hands
 * the thread back, and that frames really ran while a real feed from a real proxy delivered every
 * one of its lines. A frame loop that blocked — on a lock, on the disk, on a `runBlocking` of its
 * own — fails it. What it does not prove is that *drawing* is cheap: nothing here loads Skia, and
 * no test in this repo does, because a headless CI box is a poor place to bet on a GPU. The draw
 * cost is bounded instead by the bench in `docs/research/canvas-frame-rate.md` §12, and a live
 * window is the check by eye.
 *
 * `runBlocking` by way of [withTestProxy], like its neighbours: every wait here is on a socket,
 * where a virtual clock would fire the timeouts before the bytes came.
 */
class FarmStallTest {
    @Test
    fun `the feed keeps flowing while the farm animates two hundred entities`() =
        withTestProxy { proxy ->
            val model = AppModel(home = proxy.home, port = proxy.port)
            val scene = FarmScene()
            val atWell = heavyFarm(atWell = true)
            val atHome = heavyFarm(atWell = false)
            // Written by the drawing loop and read by the test, which is safe for the one reason
            // everything else in this window is: both are on the thread `runBlocking` holds.
            var frames = 0L
            coroutineScope {
                val watching = launch { model.watch() }
                until { model.status.startsWith("connected") }
                val drawing = launch {
                    while (true) {
                        scene.advance(
                            if (frames / TURN_FRAMES % 2 == 0L) atWell else atHome,
                            FRAME_NANOS,
                        )
                        frames++
                        // `withFrameNanos` gives the thread back between frames, and so does this.
                        // A renderer that did not would stop the feed's collector dead.
                        yield()
                    }
                }
                val before = frames
                repeat(LINES) { proxy.emit("exchange.started") }
                until { model.lines.size >= LINES }
                assertEquals(LINES, model.lines.size, "the feed lost a line to the animation")
                assertTrue(
                    frames > before,
                    "not one frame ran while the lines were arriving, at $frames",
                )
                // And the farm was really moving while all that arrived, rather than settled.
                val was = scene.frame.positions
                assertTrue(was.isNotEmpty(), "the renderer placed nobody")
                until { scene.frame.positions.any { (id, spot) -> was[id] != spot } }
                drawing.cancel()
                watching.cancel()
            }
        }
}

private fun heavyFarm(atWell: Boolean): FarmState =
    syntheticFarm(
        fields = STALL_FIELDS,
        crops = PLOT_CAPACITY,
        lastFieldCrops = STALL_LAST_FIELD_CROPS,
        villagers = STALL_VILLAGERS,
        atWell = atWell,
    )
