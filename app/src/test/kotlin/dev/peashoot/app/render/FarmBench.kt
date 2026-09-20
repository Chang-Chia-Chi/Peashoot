package dev.peashoot.app.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.peashoot.app.farm.Activity
import dev.peashoot.app.farm.FarmState
import dev.peashoot.app.farm.events
import dev.peashoot.app.farm.reduce
import java.awt.GraphicsEnvironment
import java.io.File

// The bench for issue #19's second acceptance criterion: does the real renderer, at 200 entities,
// still make the numbers `docs/research/canvas-frame-rate.md` measured? It lives in the test source
// set so it never ships, and it is a `main` and not a test because it opens a window: CI is
// headless. Run it with `./gradlew :app:benchFarm -Pbench.out=… [-Pbench.renderapi=SOFTWARE]`.
//
// Measured exactly as the spike measured: 1280x800 dp non-resizable window, warm up 3 s, measure
// 10 s of `withFrameNanos` deltas, average fps over the summed measured seconds, order statistics
// over the sorted deltas.

private const val WARMUP_NANOS = 3_000_000_000L
private const val MEASURE_NANOS = 10_000_000_000L
private const val MIN_MEASURED_FRAMES = 5
private const val NANOS_A_MILLI = 1_000_000.0

/** Villagers turn round every few seconds, so the animator is walking them for most of the run. */
private const val CYCLE_NANOS = 4_000_000_000L

private const val BENCH_VILLAGERS = 40
private const val BENCH_FIELDS = 14
private const val BENCH_LAST_FIELD_CROPS = 4
private const val BENCH_CROPS = (BENCH_FIELDS - 1) * PLOT_CAPACITY + BENCH_LAST_FIELD_CROPS

/** The spike's canvas, so a still is drawn at the size the numbers were taken at. */
private const val STILL_WIDTH = 1583
private const val STILL_HEIGHT = 954
private const val STILL_DENSITY = 1.25f
private const val ONE_FRAME_NANOS = 16_000_000L

private const val WINDOW_WIDTH_DP = 1280
private const val WINDOW_HEIGHT_DP = 800

/** The badge is a filled rectangle and the text on it. */
private const val BADGE_CALLS = 2

/** The well is a roof and a base. */
private const val WELL_CALLS = 2

/** The activities that put something over a villager's head: waiting its turn, and resting. */
private val MOODY = setOf(Activity.WALKING_TO_WELL, Activity.RESTING)

fun main() {
    val out = File(System.getProperty("bench.out", "."))
    out.mkdirs()
    stills(out)
    val label = System.getProperty("bench.label", "run")
    application {
        Window(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(size = DpSize(WINDOW_WIDTH_DP.dp, WINDOW_HEIGHT_DP.dp)),
            resizable = false,
            title = "farm-bench $label",
        ) {
            Bench(::exitApplication, label, out)
        }
    }
}

/**
 * 200 entities: 40 villagers and 160 crops, which is the count the spike's headline number is for.
 */
private fun benchFarm(atWell: Boolean): FarmState =
    syntheticFarm(
        fields = BENCH_FIELDS,
        crops = PLOT_CAPACITY,
        lastFieldCrops = BENCH_LAST_FIELD_CROPS,
        villagers = BENCH_VILLAGERS,
        atWell = atWell,
    )

/**
 * One frame of four scenes, off-screen, so the farm can be looked at without a window: the bench
 * farm plain, the same with paths on, a small farm replayed from the reducer's own fixtures, and a
 * farm with more directories than there are plots, which is the only way to see the "+N fields"
 * marker. Rendered twice each, because the first frame is the one the animator's effect starts on.
 */
private fun stills(out: File) {
    still(File(out, "farm-plain.png"), benchFarm(atWell = true))
    still(File(out, "farm-labels.png"), benchFarm(atWell = true).copy(labelsHidden = false))
    still(File(out, "farm-replay.png"), replayedFarm().copy(labelsHidden = false))
    still(
        File(out, "farm-crowded.png"),
        syntheticFarm(fields = PLOTS + 3, crops = PLOT_CAPACITY, villagers = 6, atWell = true)
            .copy(labelsHidden = false),
    )
}

private fun still(file: File, farm: FarmState) {
    val scene =
        ImageComposeScene(STILL_WIDTH, STILL_HEIGHT, Density(STILL_DENSITY)) {
            FarmCanvas(farm, Modifier.fillMaxSize())
        }
    try {
        scene.render(0L)
        val image = scene.render(ONE_FRAME_NANOS)
        file.writeBytes(checkNotNull(image.encodeToData()) { "no PNG for $file" }.bytes)
        println("still $file, ${drawCalls(farm, IntSize(STILL_WIDTH, STILL_HEIGHT))} draw calls")
    } finally {
        scene.close()
    }
}

@Composable
private fun FrameWindowScope.Bench(onDone: () -> Unit, label: String, out: File) {
    val walking = remember { benchFarm(atWell = true) }
    val home = remember { benchFarm(atWell = false) }
    val measured = remember { Measured() }
    val density = LocalDensity.current.density
    var farm by remember { mutableStateOf(walking) }
    // The effect runs once and closes the window at the end, so it holds the latest callback
    // rather than the one it started with.
    val finish by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) {
        val samples = measure { atWell -> farm = if (atWell) walking else home }
        val report = report(label, window.renderApi.name, measured.canvas, density, samples)
        println(report)
        File(out, "farm-bench-$label.json").writeText(report)
        finish()
    }
    FarmCanvas(
        farm,
        Modifier.fillMaxSize().onSizeChanged { size -> measured.canvas = size },
    )
}

/**
 * The canvas's pixel size, out of the composition: a plain holder, so writing it recomposes
 * nothing.
 */
private class Measured {
    var canvas: IntSize = IntSize.Zero
}

/**
 * Warm up, then record every frame's delta for ten seconds, turning the villagers round as it goes.
 */
private suspend fun measure(turn: (Boolean) -> Unit): List<Long> {
    val samples = mutableListOf<Long>()
    var last = 0L
    var since = 0L
    var turned = 0L
    var measuring = false
    var atWell = true
    while (true) {
        var done = false
        withFrameNanos { now ->
            val delta = if (last == 0L) 0L else now - last
            last = now
            if (since == 0L) since = now
            if (now - turned >= CYCLE_NANOS) {
                turned = now
                atWell = !atWell
                turn(atWell)
            }
            when {
                !measuring && now - since >= WARMUP_NANOS -> {
                    measuring = true
                    since = now
                }
                measuring -> {
                    if (delta > 0) samples.add(delta)
                    done = now - since >= MEASURE_NANOS && samples.size >= MIN_MEASURED_FRAMES
                }
            }
        }
        if (done) return samples
    }
}

/**
 * What one frame of this farm draws, worked out from the same layout the canvas draws from. The
 * renderer itself counts nothing: a product that carries a counter for a bench is a product bent
 * for its bench. Keep in step with `FarmCanvas.drawFarm`.
 */
private fun drawCalls(farm: FarmState, canvas: IntSize): Int {
    val layout = farmLayout(farm)
    val ground = (canvas.width / TILE_PX + 1) * (canvas.height / TILE_PX + 1)
    val furrows = layout.plots.size * PLOT_ROWS * PLOT_COLUMNS
    val crops = layout.plots.sumOf { it.crops.size }
    // A villager is a sprite and a name; names are not paths, so they are always drawn. One at the
    // well carries a mood over its head as well, once it has arrived. A crop part-way through
    // growing costs nothing extra — the pop is the same `drawImage` with a bigger destination —
    // and an inspection costs one `drawRect` for the half second it lasts, which no steady state
    // of this bench holds.
    val villagers = farm.villagers.size * 2 + farm.villagers.values.count { it.activity in MOODY }
    val labels = if (farm.labelsHidden) 0 else crops + layout.plots.size + BADGE_CALLS
    val marker = if (layout.hiddenFields > 0) 1 else 0
    return ground + furrows + crops + WELL_CALLS + villagers + labels + marker
}

/** The reducer's own fixtures, so one still shows a farm the app would really have drawn. */
private fun replayedFarm(): FarmState =
    listOf("one-turn.jsonl", "sub-agents.jsonl", "paths.jsonl")
        .flatMap { events(it) }
        .fold(FarmState()) { state, event -> reduce(state, event) }

private fun report(
    label: String,
    renderApi: String,
    canvas: IntSize,
    density: Float,
    samples: List<Long>,
): String {
    val sorted = samples.sorted()
    val seconds = sorted.sum() / NANOS_A_SECOND
    val ms = { nanos: Long -> nanos / NANOS_A_MILLI }
    val skiko = runCatching {
        val version = Class.forName("org.jetbrains.skiko.Version")
        version.getMethod("getSkiko").invoke(version.getField("INSTANCE").get(null)) as String
    }
        .getOrElse { "unknown (${it.javaClass.simpleName})" }
    val refresh = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .displayMode
            .refreshRate
            .toString()
    }
        .getOrElse { "unknown" }
    val env =
        """"renderApi":"$renderApi","skiko":"$skiko",""" +
            """"compose":"${System.getProperty("bench.compose.version")}",""" +
            """"kotlin":"${KotlinVersion.CURRENT}","java":"${System.getProperty("java.version")}",""" +
            """"javaVendor":"${System.getProperty("java.vendor")}",""" +
            """"skikoRenderApiProp":"${System.getProperty("skiko.renderApi")}",""" +
            """"skikoRenderApiEnv":"${System.getenv("SKIKO_RENDER_API")}",""" +
            """"vsyncProp":"${System.getProperty("skiko.vsync.enabled")}",""" +
            """"refreshRateHz":"$refresh","canvasPx":"${canvas.width}x${canvas.height}",""" +
            """"density":$density,""" +
            """"drawCallsPerFrame":${drawCalls(benchFarm(atWell = true), canvas)},""" +
            """"villagers":$BENCH_VILLAGERS,"crops":$BENCH_CROPS"""
    return "{\"label\":\"$label\",\"env\":{$env}," +
        """"frames":${sorted.size},"seconds":${"%.3f".format(seconds)},""" +
        """"avgFps":${"%.2f".format(if (seconds > 0) sorted.size / seconds else 0.0)},""" +
        """"medianMs":${"%.3f".format(ms(sorted[sorted.size / 2]))},""" +
        """"p99Ms":${"%.3f".format(ms(sorted[(sorted.size * 99 / 100).coerceAtMost(sorted.size - 1)]))},""" +
        """"worstMs":${"%.3f".format(ms(sorted.last()))}}""" +
        "\n"
}
