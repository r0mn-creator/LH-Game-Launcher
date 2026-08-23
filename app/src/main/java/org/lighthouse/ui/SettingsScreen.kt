package org.lighthouse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.lighthouse.data.CatalogueSystem
import org.lighthouse.theme.LocalTheme

/** What Settings needs to render, gathered by the Activity. */
data class SettingsState(
    val platforms: List<PlatformRowState>,
    val catalogue: List<CatalogueRowState>,
    val themes: List<String>,
    val activeTheme: String?,
    val gamesTotal: Int,
    val gamesPlayable: Int,
    val problems: Map<String, String>,
    /** Non-null while the Android app picker is open. */
    val appPicker: List<AppChoice>? = null,
)

/** One installed app in the Android-section picker. */
data class AppChoice(val pkg: String, val label: String, val chosen: Boolean)

data class PlatformRowState(
    val id: String,
    val name: String,
    val games: Int,
    val playable: Int,
    val verified: Boolean,
    val needsFolder: Boolean,
    val enabled: Boolean,
    val problem: String? = null,
    /** installed_apps platform: curated shelf rather than a scanned folder. */
    val isAppShelf: Boolean = false,
    val aspectRatio: String = "3:4",
)

data class CatalogueRowState(
    val system: CatalogueSystem,
    val alreadyAdded: Boolean,
    val installedEmulator: String?,
)

@Composable
fun SettingsScreen(
    state: SettingsState,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onSetupFolders: () -> Unit,
    onRescan: () -> Unit,
    onTogglePlatform: (String, Boolean) -> Unit,
    onChooseFolder: (String) -> Unit,
    onAddSystem: (CatalogueSystem) -> Unit,
    onRemovePlatform: (String) -> Unit,
    onPickTheme: (String?) -> Unit,
    onChooseApps: () -> Unit,
    onToggleApp: (String, Boolean) -> Unit,
    onCloseAppPicker: () -> Unit,
    onCycleAspect: (String) -> Unit,
) {
    if (state.appPicker != null) {
        AppPicker(state.appPicker, onToggleApp, onCloseAppPicker)
        return
    }
    val theme = LocalTheme.current
    Column(
        Modifier
            .fillMaxSize()
            .background(theme.background)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Settings", color = theme.textPrimary, fontSize = 24.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "${state.gamesPlayable} of ${state.gamesTotal} games playable",
                color = if (state.gamesPlayable < state.gamesTotal) theme.error
                        else theme.textSecondary,
                fontSize = 14.sp,
            )
            Spacer(Modifier.width(18.dp))
            Hint2("B", "Back", onBack)
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { SectionHeader("Library") }
            item {
                ActionRow("Import from Beacon",
                    "Reads an export placed in /sdcard/LightHouseImport/beacon", onImport)
            }
            item {
                ActionRow("Set up folders",
                    "Walks every system that still needs folder access", onSetupFolders)
            }
            item { ActionRow("Rescan library", "Re-read every folder now", onRescan) }

            item { SectionHeader("Platforms") }
            items(state.platforms) { p ->
                PlatformRow(p, onTogglePlatform, onChooseFolder, onRemovePlatform,
                    onChooseApps, onCycleAspect)
            }

            item { SectionHeader("Add a system") }
            item {
                Text(
                    "Systems with no emulator yet are listed so that when one appears, " +
                        "adding it is a settings change rather than an app update.",
                    color = theme.textSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp, top = 2.dp),
                )
            }
            items(state.catalogue) { c -> CatalogueRow(c, onAddSystem) }

            item { SectionHeader("Appearance") }
            item {
                Text(
                    "Colours and fonts only for now. Layout themes are planned for v2.",
                    color = theme.textSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp, top = 2.dp),
                )
            }
            items(state.themes) { t ->
                ActionRow(
                    t,
                    if (t == state.activeTheme) "Active" else null,
                    { onPickTheme(t) },
                    highlight = t == state.activeTheme,
                )
            }
            item { ActionRow("Reset to defaults", "Use the built-in theme", { onPickTheme(null) }) }

            if (state.problems.isNotEmpty()) {
                item { SectionHeader("Problems") }
                items(state.problems.entries.toList()) { (which, why) ->
                    Text("$which — $why", color = theme.error, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    val theme = LocalTheme.current
    Text(
        text.uppercase(),
        color = theme.primary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    highlight: Boolean = false,
) {
    val theme = LocalTheme.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlight) theme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Text(title, color = theme.textPrimary, fontSize = 15.sp)
        subtitle?.let {
            Text(it, color = theme.textSecondary, fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun PlatformRow(
    p: PlatformRowState,
    onToggle: (String, Boolean) -> Unit,
    onChooseFolder: (String) -> Unit,
    onRemove: (String) -> Unit,
    onChooseApps: () -> Unit,
    onCycleAspect: (String) -> Unit,
) {
    val theme = LocalTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, color = theme.textPrimary, fontSize = 15.sp)
                if (!p.verified) {
                    Spacer(Modifier.width(8.dp))
                    // Honest: a profile whose launch has never been proven on a
                    // real game says so. Dolphin's looked fine and launched
                    // nothing.
                    Text("untested", color = theme.secondary, fontSize = 11.sp)
                }
            }
            val detail = when {
                p.problem != null -> p.problem
                p.isAppShelf -> "${p.games} apps added"
                p.needsFolder -> "${p.games} games · needs folder access"
                else -> "${p.playable} of ${p.games} playable"
            }
            Text(detail,
                color = if (p.problem != null || (p.needsFolder && !p.isAppShelf)) theme.error
                        else theme.textSecondary,
                fontSize = 12.sp)
        }
        // Box art ratio is per-system data, not a constant: DVD keepcases are
        // 3:4, handheld card cases 3:5, jewel cases 1:1, DS/3DS boxes 8:7 and
        // storefront capsules 2:3. Tap to cycle - the value is always visible.
        Pill(p.aspectRatio) { onCycleAspect(p.id) }
        Spacer(Modifier.width(8.dp))
        if (p.isAppShelf) {
            // Android has no ROM folder - it is a curated shelf of installed
            // apps, so the affordance is a picker, not a folder chooser.
            Pill("Choose apps") { onChooseApps() }
            Spacer(Modifier.width(8.dp))
        } else if (p.needsFolder || p.problem != null) {
            Pill("Folder") { onChooseFolder(p.id) }
            Spacer(Modifier.width(8.dp))
        }
        Pill(if (p.enabled) "On" else "Off") { onToggle(p.id, !p.enabled) }
        Spacer(Modifier.width(8.dp))
        Pill("Remove", danger = true) { onRemove(p.id) }
    }
}

@Composable
private fun CatalogueRow(c: CatalogueRowState, onAdd: (CatalogueSystem) -> Unit) {
    val theme = LocalTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${c.system.name}  ·  ${c.system.year}",
                color = if (c.alreadyAdded) theme.textSecondary else theme.textPrimary,
                fontSize = 14.sp)
            Text(
                when {
                    c.alreadyAdded -> "Already added"
                    c.installedEmulator != null -> "${c.installedEmulator} is installed"
                    c.system.emulators.isEmpty() -> "No Android emulator exists yet"
                    else -> "No supported emulator installed"
                },
                color = theme.textSecondary, fontSize = 12.sp,
            )
        }
        if (!c.alreadyAdded) Pill("Add") { onAdd(c.system) }
    }
}

@Composable
private fun Pill(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val tint = if (danger) theme.error else theme.primary
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Hint2(button: String, label: String, onClick: () -> Unit) {
    val theme = LocalTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(theme.textPrimary.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(button, color = theme.background, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(7.dp))
        Text(label, color = theme.textPrimary, fontSize = 14.sp)
    }
}


@Composable
private fun AppPicker(
    apps: List<AppChoice>,
    onToggle: (String, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val theme = LocalTheme.current
    Column(
        Modifier
            .fillMaxSize()
            .background(theme.background)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Choose apps", color = theme.textPrimary, fontSize = 22.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(14.dp))
            Text("${apps.count { it.chosen }} added", color = theme.textSecondary, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Hint2("B", "Done", onClose)
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 40.dp)
        ) {
            items(apps) { a ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(a.pkg, !a.chosen) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label, color = theme.textPrimary, fontSize = 15.sp)
                        Text(a.pkg, color = theme.textSecondary, fontSize = 11.sp)
                    }
                    Pill(if (a.chosen) "Added" else "Add", danger = false) {
                        onToggle(a.pkg, !a.chosen)
                    }
                }
            }
        }
    }
}
