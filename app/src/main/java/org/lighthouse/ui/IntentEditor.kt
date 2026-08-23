package org.lighthouse.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.lighthouse.data.ExtraSpec
import org.lighthouse.data.LaunchSpec
import org.lighthouse.theme.LocalTheme

/**
 * Editing how a platform launches a game.
 *
 * This is the feature Beacon lacks. Beacon has a `launch_command` column but
 * never exposes it for custom platforms and ignores it if written directly, so
 * an emulator outside its hard-coded registry can never be launched. Here every
 * field is editable and the result is saved as ordinary profile data.
 */

/** Everything the editor needs that it cannot work out itself. */
data class IntentEditorState(
    val platformId: String,
    val platformName: String,
    val spec: LaunchSpec,
    val verified: Boolean,
    /** Title of the game Test will use, or null if the platform has none yet. */
    val testGame: String?,
    val preview: String,
    /** Exported activities of the chosen package. */
    val activities: List<String>,
    val installedApps: List<Pair<String, String>>,
)

private val ACTION_PRESETS = listOf(
    "android.intent.action.VIEW",
    "android.intent.action.MAIN",
)
private val FLAG_PRESETS = listOf(
    "GRANT_READ_URI_PERMISSION",
    "NEW_TASK",
    "CLEAR_TOP",
    "CLEAR_TASK",
    "SINGLE_TOP",
    "NO_ANIMATION",
)
private val ROM_MODES = listOf(
    LaunchSpec.ROM_DATA_URI to "Data URI — the game's content:// URI",
    LaunchSpec.ROM_EXTRA to "Extra — the URI goes in a named extra",
    LaunchSpec.ROM_NONE to "None — identified by extras only (e.g. a Steam id)",
)

@Composable
fun IntentEditorScreen(
    state: IntentEditorState,
    onChange: (LaunchSpec) -> Unit,
    onPickPackage: (String) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val theme = LocalTheme.current
    var pickingApp by remember { mutableStateOf(false) }

    if (pickingApp) {
        AppChooser(state.installedApps) { pkg ->
            pickingApp = false
            if (pkg != null) onPickPackage(pkg)
        }
        return
    }

    val spec = state.spec
    Column(
        Modifier
            .fillMaxSize()
            .background(theme.background)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Launch intent", color = theme.textPrimary, fontSize = 21.sp,
                    fontWeight = FontWeight.Bold)
                Text(state.platformName, color = theme.textSecondary, fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            if (state.verified) {
                Text("verified", color = theme.accent, fontSize = 13.sp)
                Spacer(Modifier.width(14.dp))
            }
            EPill("Save", onSave)
            Spacer(Modifier.width(10.dp))
            EPill("Back", onBack)
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 40.dp),
        ) {
            item { EHeader("Emulator") }
            item {
                val pkg = spec.component?.substringBefore('/').orEmpty()
                ERow("Package", pkg.ifBlank { "not set — tap to choose" }) { pickingApp = true }
            }
            item {
                val cls = spec.component?.substringAfter('/', "").orEmpty()
                EChoice(
                    label = "Activity",
                    current = cls.ifBlank { "(let Android choose)" },
                    // A bare package is often the right answer: with VIEW + a
                    // content URI Android picks whichever activity declares a
                    // matching filter. Pinning a class is only better when the
                    // app has no filter at all - which is exactly Dolphin.
                    options = listOf("(let Android choose)") + state.activities,
                ) { chosen ->
                    val p = spec.component?.substringBefore('/').orEmpty()
                    onChange(spec.copy(
                        component = if (chosen.startsWith("(")) p else "$p/$chosen"
                    ))
                }
            }

            item { EHeader("Intent") }
            item {
                EChoice("Action", spec.action, ACTION_PRESETS + listOf("custom…")) { a ->
                    if (a != "custom…") onChange(spec.copy(action = a))
                }
            }
            item {
                EText("Action (custom)", spec.action) { onChange(spec.copy(action = it)) }
            }
            item {
                EText("MIME type (optional)", spec.type.orEmpty()) {
                    onChange(spec.copy(type = it.ifBlank { null }))
                }
            }
            item { EHeader("How the game is passed") }
            items(ROM_MODES) { (mode, desc) ->
                ERadio(desc, spec.romMode == mode) { onChange(spec.copy(romMode = mode)) }
            }
            if (spec.romMode == LaunchSpec.ROM_EXTRA) {
                item {
                    EText("Extra that receives the game", spec.romExtra.orEmpty()) {
                        onChange(spec.copy(romExtra = it.ifBlank { null }))
                    }
                }
            }

            item { EHeader("Flags") }
            item {
                Text(
                    "GRANT_READ_URI_PERMISSION is on by default for a reason: without it " +
                        "an emulator can start, look completely alive, and never load the game.",
                    color = theme.textSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(FLAG_PRESETS) { f ->
                ERadio(f, f in spec.flags, square = true) {
                    onChange(spec.copy(
                        flags = if (f in spec.flags) spec.flags - f else spec.flags + f
                    ))
                }
            }

            item { EHeader("Extras") }
            item {
                Text(
                    "{file}, {id} and {title} are replaced when the game launches.",
                    color = theme.textSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            itemsIndexedCompat(spec.extras) { i, e ->
                ExtraRow(
                    e,
                    onChange = { updated ->
                        onChange(spec.copy(extras = spec.extras.toMutableList()
                            .also { it[i] = updated }))
                    },
                    onRemove = {
                        onChange(spec.copy(extras = spec.extras.filterIndexed { j, _ -> j != i }))
                    },
                )
            }
            item {
                EPill("Add extra") {
                    onChange(spec.copy(
                        extras = spec.extras + ExtraSpec("key", "string", "{file}")
                    ))
                }
            }

            item { EHeader("Test") }
            item {
                Text(
                    state.testGame?.let { "Launches \"$it\" with the settings above." }
                        ?: "No game available to test with — add a folder first.",
                    color = theme.textSecondary, fontSize = 13.sp,
                )
            }
            item {
                Row(Modifier.padding(top = 10.dp)) {
                    if (state.testGame != null) EPill("Test launch", onTest)
                }
            }
            item { EHeader("Resulting intent") }
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(theme.surface)
                        .padding(12.dp)
                ) {
                    Text(
                        state.preview,
                        color = theme.textSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

/**
 * Asked AFTER a test launch, when the user comes back.
 *
 * Deliberately a question, not an inference. X1-BOX spawns its emulator process
 * and sits on "Please insert an Xbox disc" when the intent is wrong, which is
 * indistinguishable from success if you only check that something started.
 */
@Composable
fun VerifyDialog(gameTitle: String, onAnswer: (Boolean) -> Unit) {
    val theme = LocalTheme.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.6f)
                .clip(RoundedCornerShape(14.dp))
                .background(theme.surface)
                .padding(24.dp)
        ) {
            Text("Did the game actually load?", color = theme.textPrimary,
                fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "\"$gameTitle\" should have started playing. If the emulator only " +
                    "opened to its own menu, that is a No.",
                color = theme.textSecondary, fontSize = 13.sp,
            )
            Spacer(Modifier.height(20.dp))
            Row {
                EPill("Yes, it played") { onAnswer(true) }
                Spacer(Modifier.width(12.dp))
                EPill("No") { onAnswer(false) }
            }
        }
    }
}

// ---- small pieces -----------------------------------------------------------

@Composable
private fun EHeader(t: String) {
    val theme = LocalTheme.current
    Text(t.uppercase(), color = theme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}

@Composable
private fun ERow(label: String, value: String, onClick: () -> Unit) {
    val theme = LocalTheme.current
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp)
    ) {
        Text(label, color = theme.textSecondary, fontSize = 12.sp)
        Text(value, color = theme.textPrimary, fontSize = 15.sp)
    }
}

@Composable
private fun EText(label: String, value: String, onValue: (String) -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, color = theme.textSecondary, fontSize = 12.sp)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = TextStyle(color = theme.textPrimary, fontSize = 15.sp),
            cursorBrush = SolidColor(theme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(theme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun EChoice(
    label: String,
    current: String,
    options: List<String>,
    onPick: (String) -> Unit,
) {
    val theme = LocalTheme.current
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, color = theme.textSecondary, fontSize = 12.sp)
        Text(current, color = theme.textPrimary, fontSize = 15.sp,
            modifier = Modifier.clickable { open = !open }.padding(vertical = 3.dp))
        if (open) {
            options.forEach { o ->
                Text(
                    o,
                    color = if (o == current) theme.primary else theme.textSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { open = false; onPick(o) }
                        .padding(vertical = 7.dp, horizontal = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ERadio(label: String, on: Boolean, square: Boolean = false, onToggle: () -> Unit) {
    val theme = LocalTheme.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(if (square) 4.dp else 9.dp))
                .background(if (on) theme.primary else Color.Transparent)
                .border(1.dp, if (on) theme.primary else theme.outline,
                    RoundedCornerShape(if (square) 4.dp else 9.dp))
        )
        Spacer(Modifier.width(12.dp))
        Text(label, color = theme.textPrimary, fontSize = 14.sp)
    }
}

@Composable
private fun ExtraRow(e: ExtraSpec, onChange: (ExtraSpec) -> Unit, onRemove: () -> Unit) {
    val theme = LocalTheme.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(theme.surface)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { EText("Key", e.key) { onChange(e.copy(key = it)) } }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(120.dp)) {
                EChoice("Type", e.type, listOf("string", "int", "long", "bool")) {
                    onChange(e.copy(type = it))
                }
            }
        }
        EText("Value", e.value) { onChange(e.copy(value = it)) }
        Row { EPill("Remove", onRemove) }
    }
}

@Composable
private fun EPill(label: String, onClick: () -> Unit) {
    val theme = LocalTheme.current
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, theme.primary.copy(alpha = 0.6f), RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(label, color = theme.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AppChooser(apps: List<Pair<String, String>>, onPick: (String?) -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxSize().background(theme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Choose emulator", color = theme.textPrimary, fontSize = 20.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            EPill("Cancel") { onPick(null) }
        }
        LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 40.dp)) {
            items(apps) { (pkg, label) ->
                Column(
                    Modifier.fillMaxWidth().clickable { onPick(pkg) }.padding(vertical = 10.dp)
                ) {
                    Text(label, color = theme.textPrimary, fontSize = 15.sp)
                    Text(pkg, color = theme.textSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

/** itemsIndexed for LazyListScope, kept local to avoid an import clash. */
private inline fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexedCompat(
    list: List<T>,
    crossinline row: @Composable (Int, T) -> Unit,
) = items(list.size) { i -> row(i, list[i]) }

/** Exported activities of a package, for the activity chooser. */
fun exportedActivities(context: Context, pkg: String): List<String> = runCatching {
    context.packageManager
        .getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
        .activities.orEmpty()
        .filter { it.exported }
        .map { it.name.removePrefix(pkg) }
        .sorted()
}.getOrDefault(emptyList())
