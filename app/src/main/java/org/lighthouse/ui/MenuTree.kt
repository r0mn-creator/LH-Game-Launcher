package org.lighthouse.ui

import org.lighthouse.data.CatalogueSystem

/**
 * Builds the Settings menu as nested screens.
 *
 * Rebuilt from live state on every navigation, so a row always shows the truth
 * rather than a snapshot taken when the screen opened. Screens are addressed by
 * a path of ids, which keeps the navigation stack a plain list of strings.
 */
class MenuTree(
    private val state: SettingsState,
    private val actions: MenuActions,
) {
    /** Everything the menu can do. Kept in one place so the tree stays declarative. */
    interface MenuActions {
        fun import()
        fun setupFolders()
        fun rescan()
        fun chooseFolder(platformId: String)
        fun chooseApps()
        fun editIntent(platformId: String)
        fun cycleAspect(platformId: String)
        fun setEnabled(platformId: String, enabled: Boolean)
        fun remove(platformId: String)
        fun addSystem(system: CatalogueSystem)
        fun pickColorTheme(name: String?)
        fun openColorFolder()
    }

    fun nodeFor(path: List<String>): MenuNode = when {
        path.isEmpty() -> consoles()
        path[0] == "about" -> about()
        path[0] == "library" -> library()
        path[0] == "consoles" && path.size == 1 -> consoles()
        path[0] == "consoles" -> platform(path[1])
        path[0] == "add" -> addSystem()
        path[0] == "themes" && path.size == 1 -> themes()
        path[0] == "themes" -> colors()
        path[0] == "appearance" -> themes()
        path[0] == "problems" -> problems()
        else -> root()
    }

    private fun about() = MenuNode(
        id = "about", title = "About",
        items = listOf(
            MenuItem.Note("LightHouse 0.1.0"),
            MenuItem.Note("${state.gamesTotal} games", "${state.gamesPlayable} playable"),
            MenuItem.Note("${state.platforms.size} consoles",
                "${state.platforms.count { it.verified }} with a verified launch"),
            MenuItem.Note("Launch profiles and themes are plain JSON files in the app's " +
                "files directory. Edit or share them freely."),
        ),
    )

    private fun root() = MenuNode(
        id = "root",
        title = "Settings",
        subtitle = "${state.gamesPlayable} of ${state.gamesTotal} games playable",
        items = buildList {
            add(MenuItem.Submenu("library", "Library",
                "Import, folders and rescanning"))
            add(MenuItem.Submenu("consoles", "Consoles",
                "${state.platforms.size} added",
                badge = state.platforms.count { !it.verified }
                    .takeIf { it > 0 }?.let { "$it untested" }))
            add(MenuItem.Submenu("add", "Add a system",
                "Every console from 2000 onward"))
            add(MenuItem.Submenu("appearance", "Appearance",
                "Colours and fonts"))
            if (state.problems.isNotEmpty()) {
                add(MenuItem.Submenu("problems", "Problems",
                    "${state.problems.size} profile(s) need attention"))
            }
        },
    )

    private fun library() = MenuNode(
        id = "library", title = "Library",
        items = listOf(
            MenuItem.Action("Import from Beacon",
                "Reads an export from /sdcard/LightHouseImport/beacon") { actions.import() },
            MenuItem.Action("Set up folders",
                "Walks every system that still needs access") { actions.setupFolders() },
            MenuItem.Action("Rescan now",
                "Re-read every folder") { actions.rescan() },
        ),
    )

    private fun consoles() = MenuNode(
        id = "consoles", title = "Consoles",
        subtitle = "Open one to change its emulator, folder or artwork",
        items = state.platforms.map { p ->
            MenuItem.Submenu(
                id = p.id,
                label = p.name,
                detail = when {
                    p.problem != null -> p.problem
                    p.isAppShelf -> "${p.games} apps"
                    p.needsFolder -> "${p.games} games · needs folder access"
                    else -> "${p.playable} of ${p.games} playable"
                },
                badge = if (!p.verified) "untested" else null,
            )
        },
    )

    private fun platform(id: String): MenuNode {
        val p = state.platforms.firstOrNull { it.id == id }
            ?: return MenuNode(id, "Console", items = listOf(MenuItem.Note("Not found.")))
        return MenuNode(
            id = id,
            title = p.name,
            subtitle = if (p.verified) "Launch verified on this device"
                       else "Launch not yet proven — test it",
            items = buildList {
                if (p.isAppShelf) {
                    add(MenuItem.Action("Choose apps",
                        "${p.games} on the shelf") { actions.chooseApps() })
                } else {
                    add(MenuItem.Action(
                        if (p.needsFolder) "Choose folder" else "Change folder",
                        if (p.needsFolder) "No readable folder yet" else "${p.games} games found",
                    ) { actions.chooseFolder(id) })
                }
                add(MenuItem.Action("Launch intent",
                    "How a game is handed to the emulator") { actions.editIntent(id) })
                add(MenuItem.Choice("Box art shape",
                    "Tap to cycle through real case shapes",
                    value = p.aspectRatio) { actions.cycleAspect(id) })
                add(MenuItem.Toggle("Show on home", null, true, p.enabled) {
                    actions.setEnabled(id, it)
                })
                add(MenuItem.Note("Removing keeps imported games and artwork, " +
                    "so adding the system back does not mean importing again."))
                add(MenuItem.Action("Remove console", null, true, danger = true) {
                    actions.remove(id)
                })
            },
        )
    }

    private fun addSystem() = MenuNode(
        id = "add", title = "Add a system",
        subtitle = "Consoles with no emulator yet are listed too",
        items = buildList {
            add(MenuItem.Note("When an emulator appears for one of these, adding it is a " +
                "settings change rather than an app update."))
            state.catalogue.forEach { c ->
                add(MenuItem.Action(
                    label = "${c.system.name}  ·  ${c.system.year}",
                    detail = when {
                        c.alreadyAdded -> "Already added"
                        c.installedEmulator != null -> "${c.installedEmulator} is installed"
                        c.system.emulators.isEmpty() -> "No Android emulator exists yet"
                        else -> "No supported emulator installed"
                    },
                    enabled = !c.alreadyAdded,
                ) { actions.addSystem(c.system) })
            }
        },
    )

    private fun themes() = MenuNode(
        id = "themes", title = "Themes",
        items = listOf(
            MenuItem.Submenu("colors", "Colours",
                state.activeColorTheme,
                badge = state.colorProblems.size.takeIf { it > 0 }?.let { "$it broken" }),
            MenuItem.Note("Layout themes — changing where things sit on screen, not just " +
                "their colour — are planned for a later version."),
        ),
    )

    private fun colors() = MenuNode(
        id = "colors", title = "Colours",
        subtitle = "One file per theme in themes/colors — the file name is the theme name",
        items = buildList {
            state.colorThemes.forEach { name ->
                add(MenuItem.Action(name,
                    if (name == state.activeColorTheme) "Active" else null) {
                    actions.pickColorTheme(name)
                })
            }
            add(MenuItem.Action("Where these live", "Add your own by dropping a .theme file in") {
                actions.openColorFolder()
            })
            state.colorProblems.forEach { (f, why) ->
                add(MenuItem.Note(f, why))
            }
            add(MenuItem.Note("A theme is four lines: --main, --secondary, --accent and " +
                "--highlight. Everything else is derived, and text contrast follows --main."))
        },
    )

    private fun problems() = MenuNode(
        id = "problems", title = "Problems",
        subtitle = "Profiles that could not be loaded or validated",
        items = state.problems.map { (which, why) ->
            MenuItem.Note(which, why)
        }.ifEmpty { listOf(MenuItem.Note("Nothing to report.")) },
    )
}
