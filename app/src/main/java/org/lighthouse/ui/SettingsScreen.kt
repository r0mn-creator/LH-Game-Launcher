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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.lighthouse.data.CatalogueSystem
import org.lighthouse.theme.LocalTheme

/**
 * State the Settings menu renders from. Gathered by the Activity and rebuilt on
 * every navigation so a row always shows the truth, not a stale snapshot.
 */
data class SettingsState(
    val platforms: List<PlatformRowState>,
    val catalogue: List<CatalogueRowState>,
    val themes: List<String>,
    val activeTheme: String?,
    val gamesTotal: Int,
    val gamesPlayable: Int,
    val problems: Map<String, String>,
)

data class PlatformRowState(
    val id: String,
    val name: String,
    val games: Int,
    val playable: Int,
    val verified: Boolean,
    val needsFolder: Boolean,
    val enabled: Boolean,
    val problem: String? = null,
    /** installed_apps platform: a curated shelf rather than a scanned folder. */
    val isAppShelf: Boolean = false,
    val aspectRatio: String = "3:4",
)

data class CatalogueRowState(
    val system: CatalogueSystem,
    val alreadyAdded: Boolean,
    val installedEmulator: String?,
)

/** One installed app in the Android-shelf picker. */
data class AppChoice(val pkg: String, val label: String, val chosen: Boolean)

/**
 * Picking which installed apps appear on the Android shelf.
 *
 * Android has no ROM folder, so this list IS the section - see
 * InstalledAppsProvider.
 */
@Composable
fun AppPickerScreen(
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
            Modifier.fillMaxWidth().padding(start = 30.dp, end = 30.dp, top = 22.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Choose apps", color = theme.textPrimary, fontSize = 26.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(14.dp))
            Text("${apps.count { it.chosen }} on the shelf",
                color = theme.textSecondary, fontSize = 13.sp)
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 30.dp, end = 30.dp, bottom = 20.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(apps) { a ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onToggle(a.pkg, !a.chosen) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label, color = theme.textPrimary, fontSize = 15.sp)
                        Text(a.pkg, color = theme.textSecondary, fontSize = 11.sp)
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, theme.primary.copy(alpha = 0.55f),
                                RoundedCornerShape(6.dp))
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(if (a.chosen) "Added" else "Add", color = theme.primary,
                            fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 30.dp, vertical = 12.dp),
        ) {
            PadHint("B", "Done", onClose)
        }
    }
}
