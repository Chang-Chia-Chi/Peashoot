package dev.peashoot.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.peashoot.core.Mode

// The control plane's window half (#23): thin and dumb throughout. Every decision — whether a draft
// may be saved, whether an export may be written, what a refusal says — is in the models beside
// this, where it is asserted against a real proxy without a window. Block bodies, for the reason
// `Main.kt`'s comment gives.
//
// ponytail: this tab is outside the show-paths toggle, deliberately and not by oversight. That
// toggle keeps *repository* paths out of a picture of the farm — the paths an agent's tools named,
// which came in over the wire — and what is written here is the proxy's own file system: where it
// wrote a cassette, and `dumpFrames` and `cassetteFile` inside the config it serves. A cassette
// path masked to `file 1a2b3c` would not tell the person who just exported it where to find their
// file, which is the one thing that line is for. It does mean this tab is not screenshot-safe the
// way the farm is, since those paths name a home directory. Upgrade: a toggle of its own if the
// control plane ever ends up in a screenshot, or the farm's if the two ever have to mean one thing.

/** Wide enough for the longest route name this proxy has, which is `default`. */
private val NAME_WIDTH = 120.dp

/** How tall the rule editor stands before it scrolls inside itself. */
private val EDITOR_HEIGHT = 220.dp

private const val SMALL = 12

/** The modes a route can be put in, in the order `core.Mode` declares them. */
private val MODES = Mode.entries.map { it.spelling }

/** Monospace, because everything typed or shown here is JSON someone has to read. */
private val MONO = TextStyle(fontFamily = FontFamily.Monospace, fontSize = SMALL.sp)

/**
 * The control plane: the routes and their modes, the rules editor, the export dialog, and the
 * config the proxy is running. One scrolling column rather than four tabs of its own — they are
 * read together, and a switch that has to be hunted for is a switch nobody uses.
 */
@Composable
internal fun ControlScreen(model: ControlPlaneModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        RoutesSection(model)
        Divider(Modifier.padding(vertical = 8.dp))
        RulesSection(model.rules)
        Divider(Modifier.padding(vertical = 8.dp))
        ExportSection(model.export)
        Divider(Modifier.padding(vertical = 8.dp))
        ConfigSection(model)
    }
}

/**
 * A panel's heading: what it is, what it is doing, and — for the two panels that read something
 * from the proxy — the button that reads it again. The note is the panel's own line, what is in
 * flight or why the last call did not work, and is shown whatever it says, because a panel that
 * hides its failures is a panel that lies. [onReload] is null where there is nothing to re-read:
 * the export panel's only read is its preview, which has a button of its own further down, and two
 * controls doing one thing under two names is worse than one.
 */
@Composable
private fun SectionHead(
    title: String,
    panel: Panel,
    onReload: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.subtitle1, modifier = Modifier.weight(1f))
            onReload?.let { TextButton(onClick = it, enabled = !panel.busy) { Text("reload") } }
        }
        panel.note?.let { Text(it, fontSize = SMALL.sp) }
    }
}

/** Every route the proxy holds, and the switch that changes what one of them does. */
@Composable
private fun RoutesSection(model: ControlPlaneModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        // No placeholder under an empty table: the note above says which of the three empties this
        // is — nothing read yet, a proxy with no routes, or an answer nobody could read — and a
        // fourth sentence here could only disagree with it.
        SectionHead("routes", model, model::reload)
        model.routes.forEach { route ->
            RouteControls(
                route,
                model.busy,
                onMode = { mode, strict -> model.setMode(route.name, mode, strict) },
            )
        }
    }
}

/**
 * One route: its name, a button per mode with the one it is in already spent, and the strict flag,
 * which is sent as another switch of the same route rather than as a call of its own.
 */
@Composable
private fun RouteControls(
    route: RouteRow,
    busy: Boolean,
    onMode: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(route.name, modifier = Modifier.width(NAME_WIDTH), fontFamily = FontFamily.Monospace)
        MODES.forEach { mode ->
            TextButton(
                onClick = { onMode(mode, route.strict) },
                enabled = !busy && mode != route.mode,
            ) {
                Text(if (mode == route.mode) "[$mode]" else mode)
            }
        }
        Checkbox(
            checked = route.strict,
            onCheckedChange = { onMode(route.mode, it) },
            enabled = !busy,
        )
        Text("strict", fontSize = SMALL.sp)
        route.cassette?.let { Text("  cassette $it", fontSize = SMALL.sp) }
    }
}

/**
 * The rule set as text, with test, save and revert. Save is drawn disabled until the draft in the
 * box is the draft the proxy tested, and [RulesModel.blocked] says so in words beside it, so the
 * button being out is never a mystery. A test that found collisions or splits puts a box beside the
 * button, which has to be ticked before that button will do anything — a tick and not a second
 * press, for the reason [RulesModel] gives.
 */
@Composable
private fun RulesSection(rules: RulesModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        SectionHead("matching rules", rules, rules::load)
        OutlinedTextField(
            value = rules.draft,
            onValueChange = rules::edit,
            modifier = Modifier.fillMaxWidth().height(EDITOR_HEIGHT),
            enabled = !rules.busy,
            textStyle = MONO,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = rules::test, enabled = !rules.busy) { Text("test") }
            Button(
                onClick = rules::save,
                modifier = Modifier.padding(horizontal = 8.dp),
                enabled = !rules.busy && rules.mayTry && rules.blocked == null,
            ) {
                Text("save")
            }
            TextButton(onClick = rules::revert, enabled = !rules.busy) { Text("revert") }
            if (rules.risky) {
                Checkbox(
                    checked = rules.confirming,
                    onCheckedChange = rules::confirm,
                    enabled = !rules.busy,
                )
            }
            rules.blocked?.let { Text(it, fontSize = SMALL.sp) }
        }
        rules.outcome.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = SMALL.sp) }
    }
}

/**
 * What to export, what redaction would strip, and then the file. The export button is out until a
 * preview has been shown for exactly what is in the two fields.
 */
@Composable
private fun ExportSection(export: ExportModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        SectionHead("cassette export", export, onReload = null)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = export.selection.name,
                onValueChange = { export.select(it, export.selection.session) },
                enabled = !export.busy,
                label = { Text("cassette name") },
                textStyle = MONO,
            )
            OutlinedTextField(
                value = export.selection.session,
                onValueChange = { export.select(export.selection.name, it) },
                modifier = Modifier.padding(start = 8.dp),
                enabled = !export.busy,
                label = { Text("session (all if blank)") },
                textStyle = MONO,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = export::dryRun, enabled = !export.busy) { Text("preview") }
            Button(
                onClick = export::write,
                modifier = Modifier.padding(horizontal = 8.dp),
                enabled = !export.busy && export.mayWrite,
            ) {
                Text("export")
            }
            if (!export.mayWrite) Text("preview this export first", fontSize = SMALL.sp)
        }
        export.preview.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = SMALL.sp) }
        export.written?.let { Text("wrote $it", fontSize = SMALL.sp) }
    }
}

/**
 * The config as the proxy runs it, read-only. There is no secret in it by design — the token is not
 * a config value and `secretHeaders` is names only — so nothing is filtered out of it here; and a
 * general config editor is not what #23 asks for, where the route modes above are.
 */
@Composable
private fun ConfigSection(model: ControlPlaneModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text("config", style = MaterialTheme.typography.subtitle1)
        Text(
            model.config ?: "no config read yet",
            fontFamily = FontFamily.Monospace,
            fontSize = SMALL.sp,
        )
    }
}
