package org.lighthouse.ui

import org.lighthouse.data.CatalogueSystem

/**
 * State the Settings menu renders from. Gathered by the Activity and rebuilt on
 * every navigation so a row always shows the truth, not a stale snapshot.
 */
data class SettingsState(
    val platforms: List<PlatformRowState>,
    val catalogue: List<CatalogueRowState>,
    val colorThemes: List<String>,
    val activeColorTheme: String,
    val colorProblems: Map<String, String>,
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
