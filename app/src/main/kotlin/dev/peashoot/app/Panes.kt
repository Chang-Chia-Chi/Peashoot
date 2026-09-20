package dev.peashoot.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.peashoot.app.farm.FarmState
import dev.peashoot.app.farm.nameFor
import dev.peashoot.app.render.Hit
import java.util.Locale

// The click-through panes: a villager's exchange timeline and a crop's touch history, beside the
// farm rather than over it, so that the thing that was clicked stays visible while it is read.

/** Wide enough for a timeline row's numbers, narrow enough to leave the farm the window. */
private val PANE_WIDTH = 380.dp

/** How tall the body viewer is allowed to grow before it scrolls inside its own box. */
private val BODY_HEIGHT = 260.dp

private val PANE_LIFT = 4.dp

private const val SMALL_TEXT = 12

/**
 * What was clicked, or nothing at all. Block bodies throughout, for the reason `Main.kt`'s comment
 * gives: without type resolution the Compose rules cannot tell what an expression-bodied composable
 * returns.
 */
@Composable
fun DetailPane(farm: FarmState, panes: PaneModel, modifier: Modifier = Modifier) {
    val hit = panes.selected ?: return
    Surface(modifier = modifier.width(PANE_WIDTH).fillMaxHeight(), elevation = PANE_LIFT) {
        Column(Modifier.padding(all = 12.dp)) {
            PaneHeader(title(farm, hit, farm.labelsHidden), panes::dismiss)
            panes.note?.let { Text(it, fontSize = SMALL_TEXT.sp) }
            Divider(Modifier.padding(vertical = 6.dp))
            when (hit) {
                is Hit.OnVillager -> VillagerTimeline(panes, farm.labelsHidden, Modifier.weight(1f))
                is Hit.OnCrop -> CropHistory(farm, panes, Modifier.weight(1f))
            }
            // Under the list rather than inside it: a `LazyListScope` block is not a composable,
            // and the one body being looked at is the pane's business and not a row's.
            //
            // Gated here as well as on the button that opens it. Gating the button alone left an
            // open body on screen when the toggle went off — paths and prompts in full, with the
            // badge gone, which is the screenshot the toggle exists to prevent.
            if (!farm.labelsHidden) panes.body?.let { BodyViewer(it, panes::hideBody) }
        }
    }
}

@Composable
private fun PaneHeader(title: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.subtitle1, modifier = Modifier.weight(1f))
        TextButton(onClick = onClose) { Text("close") }
    }
}

/**
 * The session's exchanges as the endpoint gave them, newest first. A row says what the turn used,
 * cost and took whether or not this window heard its line, since #85 put those on the summary; the
 * model it answered as and the paths its tools named are still the feed's alone, so a turn from
 * before this window connected blanks those two and nothing else.
 */
@Composable
private fun VillagerTimeline(panes: PaneModel, hidden: Boolean, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxWidth()) {
        items(panes.rows, key = { it.id }) { row ->
            TimelineRow(row, hidden, onBody = { panes.showBody(row.id) })
        }
    }
}

@Composable
private fun TimelineRow(
    row: ExchangeRow,
    hidden: Boolean,
    onBody: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("${row.at}  ${row.agent ?: "main"}", fontSize = SMALL_TEXT.sp)
        Text(row.model ?: "model not heard", fontSize = SMALL_TEXT.sp)
        Text(row.usage ?: "usage not heard", fontSize = SMALL_TEXT.sp)
        Text(spend(row), fontSize = SMALL_TEXT.sp)
        Text(flags(row), fontSize = SMALL_TEXT.sp)
        // Every path in a pane goes through `pathLabel`, which is what obeys the toggle.
        row.paths.forEach { Text(pathLabel(it, hidden), fontSize = SMALL_TEXT.sp) }
        // A body is nothing but paths and prompts, so it is not openable while paths are hidden.
        if (!hidden) TextButton(onClick = onBody) { Text("body") }
        Divider()
    }
}

/**
 * Which agents touched this file and when, from `GET /touches?path=` rather than from the farm.
 *
 * The reducer's own touches are only what this window heard, since `AppModel.watch` opens the feed
 * with no backfill; the proxy's event table is the whole record, and #85 gave it an endpoint to be
 * asked with. So a file touched before this window opened has its history here, which is what #22
 * asked for and could not have while this was the feed's to remember.
 *
 * The villager is named from the farm where it is a villager the farm knows, and from [nameFor]
 * otherwise, which is every turn from before this window connected.
 */
@Composable
private fun CropHistory(farm: FarmState, panes: PaneModel, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxWidth()) {
        items(panes.touches, key = { it.eventId }) { touch ->
            Text(
                touchText(touch, farm.villagers[touch.villager]?.name ?: nameFor(touch.villager)),
                fontFamily = FontFamily.Monospace,
                fontSize = SMALL_TEXT.sp,
            )
        }
    }
}

/** The one body being looked at, scrollable and never logged. */
@Composable
private fun BodyViewer(text: String, onHide: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        TextButton(onClick = onHide) { Text("hide body") }
        Text(
            text,
            modifier = Modifier.heightIn(max = BODY_HEIGHT).verticalScroll(rememberScrollState()),
            fontFamily = FontFamily.Monospace,
            fontSize = SMALL_TEXT.sp,
        )
    }
}

/** A villager is named, never pathed; a crop is only ever named through [pathLabel]. */
private fun title(farm: FarmState, hit: Hit, hidden: Boolean): String =
    when (hit) {
        is Hit.OnVillager -> farm.villagers[hit.id]?.name ?: nameFor(hit.id)
        is Hit.OnCrop -> pathLabel(hit.path, hidden)
    }

/**
 * Four decimal places and not the window's two: a turn costing a fifth of a cent is a real number
 * here, where the shipping bin's two are a day's takings added up.
 *
 * This is the third `Locale.ROOT` money format in the app, after `Main.kt`'s `money` and
 * `render.binLine`, and deliberately so: it is one line, the three disagree on precision on
 * purpose, and `dev.peashoot.app.render` is not somewhere the window reaches into for a formatter —
 * the argument `money`'s own KDoc already makes. Do not "fix" this by sharing it; the thing that
 * must never vary is [Locale.ROOT], because a ledger with a comma for a decimal point is a bug on
 * half the machines that will ever run this.
 */
private fun spend(row: ExchangeRow): String {
    val cost = row.costUsd?.let { String.format(Locale.ROOT, "\$%.4f", it) } ?: "cost not heard"
    val latency = row.latencyMs?.let { "$it ms" } ?: "latency not heard"
    return "$cost, $latency"
}

private fun flags(row: ExchangeRow): String {
    val flags =
        listOfNotNull(
            "replay hit".takeIf { row.replayHit },
            "resumed".takeIf { row.resumed },
            "client gone".takeIf { row.clientDisconnected },
            row.status?.let { "status $it" },
        )
    return if (flags.isEmpty()) "no flags" else flags.joinToString(", ")
}
