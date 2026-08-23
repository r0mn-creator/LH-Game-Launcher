package org.lighthouse.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lighthouse.LightHouseApp
import org.lighthouse.core.DisplayGame
import org.lighthouse.core.LaunchIntentBuilder
import org.lighthouse.core.LibraryMerge
import org.lighthouse.data.PlatformProfile
import org.lighthouse.provider.Providers
import org.lighthouse.theme.LocalTheme

/**
 * The launcher.
 *
 * Driven with a pad: bumpers page through systems, d-pad/left stick move the
 * cursor, A launches. Focus is an explicit index (see GamepadNav) rather than
 * Compose focus, so the selection behaves like a console UI instead of a
 * scrollable list.
 */
class MainActivity : ComponentActivity() {

    private var pages by mutableStateOf<List<SystemPage>>(emptyList())
    private var systemIndex by mutableIntStateOf(0)
    private var cursor by mutableStateOf(GridCursor())
    private var pendingFolderFor: String? = null
    /** Settings navigation stack: a path of node ids. Empty = root. */
    private var menuPath by mutableStateOf<List<String>>(emptyList())
    private var showSettings by mutableStateOf(false)
    private var showDrawer by mutableStateOf(false)
    /** One cursor per screen depth, so backing out restores where you were. */
    private var menuCursors by mutableStateOf<Map<String, Int>>(emptyMap())
    private var drawerCursor by mutableIntStateOf(0)
    private var menuForward by mutableStateOf(true)
    private var categoryIndex by mutableIntStateOf(0)
    private var pane by mutableStateOf(Pane.RAIL)
    private var showAppPicker by mutableStateOf(false)
    /** Platform whose launch intent is being edited, and the working copy. */
    private var editingId by mutableStateOf<String?>(null)
    private var editingSpec by mutableStateOf<org.lighthouse.data.LaunchSpec?>(null)
    /** Set while a test launch is out; answered when the user comes back. */
    private var verifyFor by mutableStateOf<Pair<String, String>?>(null)
    private var editorCursor by mutableIntStateOf(0)
    private var appPickerCursor by mutableIntStateOf(0)
    /** Open text prompt: title, hint, current value, and what to do with it. */
    private var prompt by mutableStateOf<Triple<String, String?, String>?>(null)
    private var promptApply: ((String) -> Unit)? = null
    private var editingPickPackage by mutableStateOf(false)
    private var pkgCursor by mutableIntStateOf(0)
    /**
     * Systems still waiting for a folder, walked one picker at a time.
     *
     * After a Beacon import every system needs its folder re-granted (a SAF
     * grant belongs to the app that asked for it), so making the user find each
     * one separately would be eleven trips through the UI for a single task.
     */
    private var setupQueue: List<String> = emptyList()
    private var setupTotal = 0

    private val app get() = LightHouseApp.instance

    /**
     * The editor's pending state has to OUTLIVE THE PROCESS.
     *
     * A test launch starts an emulator, and an emulator on a handheld will
     * happily evict the launcher. Coming back to find the editor gone - and the
     * verification question never asked - makes the feature unusable in exactly
     * the case it exists for. So the pending answer is written to prefs before
     * we leave, and picked up again in onCreate.
     */
    private val prefs get() = getSharedPreferences("lighthouse_ui", MODE_PRIVATE)

    private val folderPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val tree: Uri? = result.data?.data
        val id = pendingFolderFor
        pendingFolderFor = null
        if (tree == null || id == null) return@registerForActivityResult
        val profile = loadProfileById(id)
        if (profile == null) {
            toast("Could not find that platform")
            return@registerForActivityResult
        }
        if (FolderPicker.accept(this, app.profiles, profile, tree) == null) {
            toast("Could not keep access to that folder")
            setupQueue = emptyList()
        } else {
            advanceSetup(justGranted = profile)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        immersive()
        setContent {
            val theme by remember { mutableStateOf(app.themes.active()) }
            CompositionLocalProvider(LocalTheme provides theme) {
                val ed = editingId
                val vf = verifyFor
                if (vf != null) {
                    VerifyDialog(vf.second) { ok -> answerVerify(ok) }
                } else if (ed != null && editingPickPackage) {
                    val node = packageNode()
                    ConsoleMenuScreen(
                        node = node,
                        cursor = pkgCursor.coerceIn(0, (node.items.size - 1).coerceAtLeast(0)),
                        forward = true,
                        depth = 1,
                        onSelect = { pkgCursor = it },
                        onActivate = { i -> activate(node, i) },
                        onBack = { editingPickPackage = false },
                    )
                } else if (ed != null && editingSpec != null) {
                    val node = intentNode(ed, editingSpec!!)
                    Box(Modifier.fillMaxSize()) {
                        ConsoleMenuScreen(
                            node = node,
                            cursor = clampEditorCursor(node),
                            forward = true,
                            depth = 1,
                            onSelect = { editorCursor = it },
                            onActivate = { i -> activate(node, i) },
                            onBack = { editingId = null; editingSpec = null },
                        )
                        prompt?.let { (t, h, v) ->
                            TextPromptOverlay(t, h, v,
                                onDone = { promptApply?.invoke(it); prompt = null },
                                onCancel = { prompt = null })
                        }
                    }
                } else if (showAppPicker) {
                    val rows = appPickerRows()
                    AppPickerScreen(
                        apps = rows,
                        cursor = appPickerCursor.coerceIn(0, (rows.size - 1).coerceAtLeast(0)),
                        onSelect = { appPickerCursor = it },
                        onToggle = ::toggleApp,
                        onClose = { showAppPicker = false; reload() },
                    )
                } else if (showDrawer) {
                    AppDrawerScreen(
                        apps = drawerApps,
                        cursor = drawerCursor,
                        onSelect = { drawerCursor = it },
                        onLaunch = ::launchApp,
                        onBack = { showDrawer = false },
                    )
                } else if (showSettings) {
                    val st = settingsState()
                    val tree = MenuTree(st, menuActions)
                    val nodeNow = tree.nodeFor(currentPath())
                    SettingsShell(
                        categories = SETTINGS_CATEGORIES,
                        categoryIndex = categoryIndex,
                        node = nodeNow,
                        contentCursor = cursorFor(nodeNow),
                        pane = pane,
                        forward = menuForward,
                        subtitle = st.gamesPlayable.toString() + " of " +
                            st.gamesTotal.toString() + " games playable",
                        onPickCategory = { i -> categoryIndex = i; menuPath = emptyList() },
                        onFocusContent = { pane = Pane.CONTENT },
                        onSelectContent = { setCursor(nodeNow, it) },
                        onActivate = { activate(nodeNow, it) },
                        onBack = ::menuBack,
                    )
                } else {
                    HomeScreen(
                        pages = pages,
                        systemIndex = systemIndex,
                        cursor = cursor,
                        onChooseFolder = ::chooseFolder,
                        onImport = ::runImport,
                        onLaunch = ::launch,
                        onSelect = { i -> cursor = cursor.copy(index = i) },
                        onPrevSystem = { handle(Nav.PREV_SYSTEM) },
                        onNextSystem = { handle(Nav.NEXT_SYSTEM) },
                        onPickSystem = { i ->
                            systemIndex = i.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                            cursor = cursor.copy(index = 0)
                        },
                        onSettings = { showSettings = true },
                        onApps = { toast("Apps screen is not built yet") },
                    )
                }
            }
        }
        restorePending()
        reload()
    }

    private fun restorePending() {
        val id = prefs.getString(KEY_VERIFY_ID, null)
        val title = prefs.getString(KEY_VERIFY_TITLE, null)
        if (id != null && title != null) {
            verifyFor = id to title
            editingId = id
            editingSpec = loadProfileById(id)?.launch
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    /** Full screen: a launcher owns the display. */
    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---- data ---------------------------------------------------------------

    private fun reload() {
        lifecycleScope.launch {
            val built = withContext(Dispatchers.IO) { buildPages() }
            pages = built
            systemIndex = systemIndex.coerceIn(0, (built.size - 1).coerceAtLeast(0))
            cursor = cursor.copy(index = 0)
        }
    }

    /**
     * Every profile becomes a page, including broken ones. A platform that fails
     * validation is shown with its reason rather than disappearing - an absent
     * system looks identical to one that was never configured.
     */
    private fun buildPages(): List<SystemPage> {
        val loaded = app.profiles.load()
        val out = mutableListOf<SystemPage>()

        for (p in loaded.profiles.filter { it.enabled }) {
            val d = Providers.discover(this, p)
            val games = LibraryMerge.merge(d.games, app.library.forPlatform(p.id))
            out += SystemPage(p, games, d.notes)
        }
        for ((which, why) in loaded.problems) {
            val p = loadProfileById(which.removeSuffix(".json"))
            if (p != null) {
                // Imported games still belong to this system even while the
                // profile is unusable, so the page is never mysteriously empty.
                val games = LibraryMerge.merge(emptyList(), app.library.forPlatform(p.id))
                out += SystemPage(p, games, emptyList(), why)
            }
        }
        return out.sortedBy { it.profile.order }
    }

    private fun loadProfileById(id: String): PlatformProfile? = runCatching {
        val f = java.io.File(app.profiles.dir, if (id.endsWith(".json")) id else "$id.json")
        if (!f.isFile) null
        else kotlinx.serialization.json.Json { isLenient = true; ignoreUnknownKeys = true }
            .decodeFromString<PlatformProfile>(f.readText())
    }.getOrNull()

    // ---- input --------------------------------------------------------------

    /**
     * dispatchKeyEvent, not onKeyDown.
     *
     * Compose's focus system consumes the d-pad before an Activity's onKeyDown
     * ever runs, so with onKeyDown the bumpers worked and the d-pad silently did
     * nothing. Intercepting at dispatch keeps the cursor authoritative, which is
     * the whole point of driving selection by index.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val nav = GamepadNav.fromKey(event.keyCode)
            if (nav != null) { handle(nav); return true }
        } else if (event.action == KeyEvent.ACTION_UP) {
            // Swallow the matching UP so nothing downstream reacts to it.
            if (GamepadNav.fromKey(event.keyCode) != null) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val nav = GamepadNav.fromMotion(event)
        if (nav != null) { handle(nav); return true }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val nav = GamepadNav.fromMotion(event)
        if (nav != null) { handle(nav); return true }
        return super.onGenericMotionEvent(event)
    }

    private fun handle(nav: Nav) {
        val page = pages.getOrNull(systemIndex)
        when (nav) {
            Nav.PREV_SYSTEM -> if (pages.isNotEmpty()) {
                systemIndex = (systemIndex - 1 + pages.size) % pages.size
                cursor = cursor.copy(index = 0)
            }
            Nav.NEXT_SYSTEM -> if (pages.isNotEmpty()) {
                systemIndex = (systemIndex + 1) % pages.size
                cursor = cursor.copy(index = 0)
            }
            Nav.LEFT, Nav.RIGHT, Nav.UP, Nav.DOWN -> when {
                prompt != null -> Unit          // the keyboard owns the d-pad
                editingPickPackage -> {
                    val n = packageNode().items.size
                    if (n > 0) pkgCursor = when (nav) {
                        Nav.UP, Nav.LEFT -> (pkgCursor - 1).coerceAtLeast(0)
                        Nav.DOWN, Nav.RIGHT -> (pkgCursor + 1).coerceAtMost(n - 1)
                        else -> pkgCursor
                    }
                }
                editingId != null -> moveEditor(nav)
                showAppPicker -> {
                    val n = appPickerRows().size
                    if (n > 0) appPickerCursor = when (nav) {
                        Nav.UP, Nav.LEFT -> (appPickerCursor - 1).coerceAtLeast(0)
                        Nav.DOWN, Nav.RIGHT -> (appPickerCursor + 1).coerceAtMost(n - 1)
                        else -> appPickerCursor
                    }
                }
                showDrawer -> drawerCursor = GridCursor(drawerCursor, DRAWER_COLUMNS)
                    .move(nav, drawerApps.size).index
                showSettings -> moveMenu(nav)
                else -> cursor = cursor.move(nav, page?.games?.size ?: 0)
            }
            Nav.LAUNCH -> if (prompt != null) {
                Unit
            } else if (editingPickPackage) {
                val n = packageNode()
                activate(n, pkgCursor.coerceIn(0, (n.items.size - 1).coerceAtLeast(0)))
            } else if (editingId != null) {
                val n = intentNode(editingId!!, editingSpec!!)
                activate(n, clampEditorCursor(n))
            } else if (showAppPicker) {
                appPickerRows().getOrNull(appPickerCursor)?.let { toggleApp(it.pkg, !it.chosen) }
            } else if (showDrawer) {
                drawerApps.getOrNull(drawerCursor)?.let { launchApp(it.first) }
            } else if (showSettings) {
                if (pane == Pane.RAIL) {
                    pane = Pane.CONTENT
                } else {
                    val n = MenuTree(settingsState(), menuActions).nodeFor(currentPath())
                    activate(n, cursorFor(n))
                }
            } else {
                val g = page?.games?.getOrNull(cursor.index)
                if (page != null && g != null) launch(page, g)
                else if (page != null && page.problem?.contains("no roots") == true) {
                    chooseFolder(page.profile.id)
                }
            }
            Nav.BACK -> when {
                prompt != null -> prompt = null
                verifyFor != null -> Unit          // must be answered
                editingPickPackage -> editingPickPackage = false
                editingId != null -> { editingId = null; editingSpec = null }
                showAppPicker -> { showAppPicker = false; reload() }
                showDrawer -> showDrawer = false
                showSettings -> menuBack()
                // On the home screen B is the app drawer, as on Beacon.
                else -> openDrawer()
            }
            Nav.MENU -> if (showSettings) {
                showSettings = false
            } else {
                menuPath = emptyList(); menuForward = true
                pane = Pane.RAIL; categoryIndex = 0; showSettings = true
            }
            Nav.SEARCH -> toast("Search is not built yet")
        }
    }

    // ---- actions ------------------------------------------------------------

    /**
     * Start walking every system that has no folder yet.
     */
    private fun startSetup() {
        val needing = pages
            .filter { it.profile.source.provider == org.lighthouse.data.SourceSpec.FOLDER }
            .filter { FolderPicker.needsFolder(this, it.profile.source.roots) }
            .map { it.profile.id }
        if (needing.isEmpty()) {
            toast("Every system already has a folder")
            return
        }
        // Systems whose folder we already know go first: those are a single tap
        // because the picker can be opened straight at the folder.
        setupQueue = needing.sortedBy { id ->
            val roots = pages.firstOrNull { it.profile.id == id }?.profile?.source?.roots
            if (roots.isNullOrEmpty()) 1 else 0
        }
        setupTotal = needing.size
        android.util.Log.i("LH.Setup", "queue=" + setupQueue.joinToString())
        nextInSetup()
    }

    private fun nextInSetup() {
        val id = setupQueue.firstOrNull()
        if (id == null) {
            // Report against the library, not against the pickers: what matters
            // is how many games actually became playable, which is the thing the
            // name-matching in LibraryMerge is responsible for.
            reload()
            lifecycleScope.launch {
                kotlinx.coroutines.delay(400)
                val playable = pages.sumOf { p -> p.games.count { it.playable } }
                val total = pages.sumOf { it.games.size }
                toast("Setup done — $playable of $total games are now playable")
            }
            return
        }
        val name = pages.firstOrNull { it.profile.id == id }?.profile?.name ?: id
        val step = setupTotal - setupQueue.size + 1
        toast("Folder $step of $setupTotal — pick the folder for $name")
        chooseFolder(id)
    }

    private fun advanceSetup(justGranted: PlatformProfile) {
        if (setupQueue.isEmpty()) { reload(); return }
        setupQueue = setupQueue.drop(1)
        // Rebuild first so the next step sees the grant we just took.
        lifecycleScope.launch {
            val built = withContext(Dispatchers.IO) { buildPages() }
            pages = built
            nextInSetup()
        }
    }

    private fun chooseFolder(platformId: String) {
        pendingFolderFor = platformId
        // Open the picker at the folder we already know about, when we know one.
        val known = pages.firstOrNull { it.profile.id == platformId }
            ?.profile?.source?.roots?.firstOrNull()
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        folderPicker.launch(FolderPicker.intent(known))
    }

    private fun runImport() {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { ImportSource.run(this@MainActivity) }
            toast(r.summary())
            reload()
        }
    }

    private fun launch(page: SystemPage, game: DisplayGame) {
        val entry = game.entry
        if (entry == null) {
            toast("\"${game.title}\" needs its folder re-granted in LightHouse")
            return
        }
        if (entry.shortcutId != null && entry.shortcutPackage != null) {
            val la = getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            if (la == null) { toast("Shortcuts unavailable"); return }
            runCatching {
                la.startShortcut(
                    entry.shortcutPackage, entry.shortcutId, null, null, Process.myUserHandle()
                )
            }.onFailure {
                toast("Could not start shortcut — LightHouse must be the default launcher")
            }
            return
        }
        val target = LaunchIntentBuilder.Target(entry.uri, entry.id, game.title)
        when (val r = LaunchIntentBuilder.build(page.profile.launch, target)) {
            is LaunchIntentBuilder.Result.Unbuildable -> toast("Cannot launch: ${r.reason}")
            is LaunchIntentBuilder.Result.Ready ->
                runCatching { startActivity(r.intent) }
                    .onFailure { toast("Launch failed: ${it.message ?: it::class.simpleName}") }
        }
    }

    // ---- menu navigation ----------------------------------------------------

    private val drawerApps: List<Pair<String, String>>
        get() = org.lighthouse.provider.InstalledAppsProvider.installedApps(this)

    private fun openDrawer() { drawerCursor = 0; showDrawer = true }

    private fun launchApp(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) { toast("That app has no launcher entry"); return }
        runCatching { startActivity(i) }
            .onFailure { toast("Could not open: ${'$'}{it.message}") }
    }

    /** Category id, plus any drilled-into detail. */
    private fun currentPath(): List<String> =
        listOf(SETTINGS_CATEGORIES[categoryIndex].id) + menuPath

    private fun cursorFor(node: MenuNode): Int {
        val stored = menuCursors[node.id]
        val ok = node.selectable()
        if (ok.isEmpty()) return 0
        return stored?.takeIf { it in ok } ?: ok.first()
    }

    private fun setCursor(node: MenuNode, i: Int) {
        menuCursors = menuCursors + (node.id to i)
    }

    private fun moveMenu(nav: Nav) {
        val node = MenuTree(settingsState(), menuActions).nodeFor(currentPath())
        if (pane == Pane.RAIL) {
            when (nav) {
                Nav.UP -> categoryIndex = (categoryIndex - 1)
                    .coerceIn(0, SETTINGS_CATEGORIES.size - 1)
                Nav.DOWN -> categoryIndex = (categoryIndex + 1)
                    .coerceIn(0, SETTINGS_CATEGORIES.size - 1)
                // Right crosses into the content pane - the spatial move the
                // two-pane layout implies.
                Nav.RIGHT -> if (node.selectable().isNotEmpty()) pane = Pane.CONTENT
                else -> Unit
            }
            if (nav == Nav.UP || nav == Nav.DOWN) menuPath = emptyList()
            return
        }
        val ok = node.selectable()
        if (ok.isEmpty()) { pane = Pane.RAIL; return }
        val at = ok.indexOf(cursorFor(node)).coerceAtLeast(0)
        when (nav) {
            Nav.UP -> setCursor(node, ok[(at - 1).coerceAtLeast(0)])
            Nav.DOWN -> setCursor(node, ok[(at + 1).coerceAtMost(ok.size - 1)])
            // Left backs out: to the parent detail if we are in one, else to
            // the rail.
            Nav.LEFT -> menuBack()
            else -> Unit
        }
    }

    private fun activate(node: MenuNode, index: Int) {
        when (val item = node.items.getOrNull(index)) {
            is MenuItem.Submenu -> { menuForward = true; menuPath = menuPath + item.id }
            is MenuItem.Action -> if (item.enabled) item.run()
            is MenuItem.Toggle -> item.set(!item.on)
            is MenuItem.Choice -> item.cycle()
            else -> Unit
        }
    }

    private fun menuBack() {
        when {
            menuPath.isNotEmpty() -> { menuForward = false; menuPath = menuPath.dropLast(1) }
            pane == Pane.CONTENT -> pane = Pane.RAIL
            else -> showSettings = false
        }
    }

    private val menuActions = object : MenuTree.MenuActions {
        override fun import() = runImport()
        override fun setupFolders() { showSettings = false; startSetup() }
        override fun rescan() { reload(); toast("Rescanning…") }
        override fun chooseFolder(platformId: String) {
            showSettings = false
            this@MainActivity.chooseFolder(platformId)
        }
        override fun chooseApps() { showAppPicker = true }
        override fun editIntent(platformId: String) = startEditing(platformId)
        override fun cycleAspect(platformId: String) = this@MainActivity.cycleAspect(platformId)
        override fun setEnabled(platformId: String, enabled: Boolean) =
            togglePlatform(platformId, enabled)
        override fun remove(platformId: String) {
            removePlatform(platformId)
            menuBack()
        }
        override fun addSystem(system: org.lighthouse.data.CatalogueSystem) =
            this@MainActivity.addSystem(system)
        override fun pickTheme(id: String?) = this@MainActivity.pickTheme(id)
    }

    // ---- settings -----------------------------------------------------------

    private fun settingsState(): SettingsState {
        val loaded = app.profiles.load()
        val rows = pages.map { pg ->
            PlatformRowState(
                id = pg.profile.id,
                name = pg.profile.name,
                games = pg.games.size,
                playable = pg.games.count { it.playable },
                verified = pg.profile.verified,
                needsFolder = pg.profile.source.provider == org.lighthouse.data.SourceSpec.FOLDER &&
                    FolderPicker.needsFolder(this, pg.profile.source.roots),
                isAppShelf = pg.profile.source.provider ==
                    org.lighthouse.data.SourceSpec.INSTALLED_APPS,
                aspectRatio = pg.profile.aspectRatio,
                enabled = pg.profile.enabled,
                problem = pg.problem,
            )
        }
        val have = rows.map { it.id }.toSet()
        val cat = app.catalogue.load().systems
            .sortedBy { it.year }
            .map { sys ->
                CatalogueRowState(
                    system = sys,
                    alreadyAdded = sys.id in have,
                    installedEmulator = app.catalogue.installedFor(sys).firstOrNull()?.name,
                )
            }
        return SettingsState(
            platforms = rows,
            catalogue = cat,
            themes = app.themes.load().themes.map { it.id },
            activeTheme = app.themes.selectedId,
            gamesTotal = pages.sumOf { it.games.size },
            gamesPlayable = pages.sumOf { pg -> pg.games.count { it.playable } },
            problems = loaded.problems,
        )
    }

    /** The installed_apps platform (the Android shelf), if one exists. */
    private fun appShelfProfile(): PlatformProfile? =
        app.profiles.load().profiles
            .firstOrNull { it.source.provider == org.lighthouse.data.SourceSpec.INSTALLED_APPS }

    /** The ratios real box art actually comes in. */
    private val aspectPresets = listOf("3:4", "1:1", "8:7", "3:5", "2:3", "1:2")

    private fun cycleAspect(id: String) {
        val p = loadProfileById(id) ?: return
        val i = aspectPresets.indexOf(p.aspectRatio)
        val next = aspectPresets[(i + 1) % aspectPresets.size]
        app.profiles.save(p.copy(aspectRatio = next))?.let { toast(it) }
        reload()
    }

    // ---- intent editor ------------------------------------------------------

    private fun startEditing(id: String) {
        val p = loadProfileById(id) ?: return
        editingId = id
        editingSpec = p.launch
        showSettings = false
    }

    /** Choosing which installed app is this console's emulator. */
    private fun packageNode(): MenuNode = MenuNode(
        id = "pickpkg",
        title = "Choose emulator",
        subtitle = "Any installed app can be a console's player",
        items = org.lighthouse.provider.InstalledAppsProvider.installedApps(this).map { (pkg, label) ->
            MenuItem.Action(label, pkg) {
                // Keep only the package: a bare package lets Android resolve the
                // activity, which is right far more often than pinning a class.
                editingSpec = editingSpec?.copy(component = pkg)
                editingPickPackage = false
            }
        },
    )

    private fun clampEditorCursor(node: MenuNode): Int {
        val ok = node.selectable()
        if (ok.isEmpty()) return 0
        return if (editorCursor in ok) editorCursor else ok.first()
    }

    private fun moveEditor(nav: Nav) {
        val node = intentNode(editingId ?: return, editingSpec ?: return)
        val ok = node.selectable()
        if (ok.isEmpty()) return
        val at = ok.indexOf(clampEditorCursor(node)).coerceAtLeast(0)
        editorCursor = when (nav) {
            Nav.UP, Nav.LEFT -> ok[(at - 1).coerceAtLeast(0)]
            Nav.DOWN, Nav.RIGHT -> ok[(at + 1).coerceAtMost(ok.size - 1)]
            else -> ok[at]
        }
    }

    private fun askText(title: String, hint: String?, current: String, apply: (String) -> Unit) {
        promptApply = apply
        prompt = Triple(title, hint, current)
    }

    private fun intentNode(id: String, spec: org.lighthouse.data.LaunchSpec): MenuNode {
        val st = editorState(id, spec)
        val acts = st.activities
        return IntentMenu.node(
            platformName = st.platformName,
            spec = spec,
            verified = st.verified,
            testGame = st.testGame,
            preview = st.preview,
            a = object : IntentMenu.Actions {
                override fun pickPackage() { editingPickPackage = true }
                override fun promptText(field: IntentMenu.TextField, current: String) {
                    when (field) {
                        IntentMenu.TextField.ACTION -> askText("Action",
                            "e.g. android.intent.action.VIEW", current) {
                            editingSpec = editingSpec?.copy(action = it)
                        }
                        IntentMenu.TextField.MIME -> askText("MIME type",
                            "Leave blank for none", current) {
                            editingSpec = editingSpec?.copy(type = it.ifBlank { null })
                        }
                        IntentMenu.TextField.ROM_EXTRA -> askText("Extra name",
                            "The extra that receives the game's URI", current) {
                            editingSpec = editingSpec?.copy(romExtra = it.ifBlank { null })
                        }
                        else -> Unit
                    }
                }
                override fun cycleActivity() {
                    editingSpec = editingSpec?.let { IntentMenu.withActivity(it, acts) }
                }
                override fun cycleAction() {
                    editingSpec = editingSpec?.let {
                        it.copy(action = IntentMenu.cycled(IntentMenu.ACTIONS, it.action))
                    }
                }
                override fun cycleRomMode() {
                    editingSpec = editingSpec?.let {
                        it.copy(romMode = IntentMenu.cycled(IntentMenu.ROM_MODES, it.romMode))
                    }
                }
                override fun toggleFlag(flag: String) {
                    editingSpec = editingSpec?.let {
                        it.copy(flags = if (flag in it.flags) it.flags - flag else it.flags + flag)
                    }
                }
                override fun addExtra() {
                    editingSpec = editingSpec?.let {
                        it.copy(extras = it.extras +
                            org.lighthouse.data.ExtraSpec("key", "string", "{file}"))
                    }
                }
                override fun editExtra(index: Int, part: IntentMenu.ExtraPart) {
                    val e = editingSpec?.extras?.getOrNull(index) ?: return
                    when (part) {
                        IntentMenu.ExtraPart.KEY -> askText("Extra key", null, e.key) { v ->
                            editingSpec = editingSpec?.let {
                                IntentMenu.withExtraAt(it, index) { x -> x.copy(key = v) }
                            }
                        }
                        IntentMenu.ExtraPart.VALUE -> askText("Extra value",
                            "{file}, {id} and {title} are filled in at launch", e.value) { v ->
                            editingSpec = editingSpec?.let {
                                IntentMenu.withExtraAt(it, index) { x -> x.copy(value = v) }
                            }
                        }
                    }
                }
                override fun cycleExtraType(index: Int) {
                    val types = listOf("string", "int", "long", "bool")
                    editingSpec = editingSpec?.let {
                        IntentMenu.withExtraAt(it, index) { x ->
                            x.copy(type = IntentMenu.cycled(types, x.type))
                        }
                    }
                }
                override fun removeExtra(index: Int) {
                    editingSpec = editingSpec?.let {
                        it.copy(extras = it.extras.filterIndexed { j, _ -> j != index })
                    }
                }
                override fun test() = testLaunch()
                override fun save() = saveEditing()
            },
        )
    }

    /** Rows for the Android app-shelf picker. */
    private fun appPickerRows(): List<AppChoice> {
        val chosen = appShelfProfile()?.source?.packages.orEmpty().toSet()
        return org.lighthouse.provider.InstalledAppsProvider.installedApps(this)
            .map { (pkg, label) -> AppChoice(pkg, label, pkg in chosen) }
            .sortedWith(compareByDescending<AppChoice> { it.chosen }
                .thenBy { it.label.lowercase() })
    }

    private fun editorState(id: String, spec: org.lighthouse.data.LaunchSpec): IntentEditorState {
        val page = pages.firstOrNull { it.profile.id == id }
        val game = page?.games?.firstOrNull { it.playable }
        val pkg = spec.component?.substringBefore('/').orEmpty()
        val target = LaunchIntentBuilder.Target(
            game?.entry?.uri, game?.entry?.id, game?.title ?: "Example Game"
        )
        val preview = when (val r = LaunchIntentBuilder.build(spec, target)) {
            is LaunchIntentBuilder.Result.Ready -> LaunchIntentBuilder.describe(r.intent)
            is LaunchIntentBuilder.Result.Unbuildable -> "Cannot build: ${'$'}{r.reason}"
        }
        return IntentEditorState(
            platformId = id,
            platformName = page?.profile?.name ?: id,
            spec = spec,
            verified = loadProfileById(id)?.verified == true,
            testGame = game?.title,
            preview = preview,
            activities = if (pkg.isBlank()) emptyList() else exportedActivities(this, pkg),
            installedApps = org.lighthouse.provider.InstalledAppsProvider.installedApps(this),
        )
    }

    private fun saveEditing() {
        val id = editingId ?: return
        val spec = editingSpec ?: return
        val p = loadProfileById(id) ?: return
        // Editing the launch invalidates any previous verification: the thing
        // that was proven to work is not the thing about to run.
        val err = app.profiles.save(p.copy(launch = spec, verified = false))
        if (err != null) { toast(err); return }
        editingId = null; editingSpec = null
        reload()
        toast("Saved. Test it to mark it verified.")
    }

    private fun testLaunch() {
        val id = editingId ?: return
        val spec = editingSpec ?: return
        val page = pages.firstOrNull { it.profile.id == id } ?: return
        val game = page.games.firstOrNull { it.playable } ?: return
        val e = game.entry ?: return
        val target = LaunchIntentBuilder.Target(e.uri, e.id, game.title)
        when (val r = LaunchIntentBuilder.build(spec, target)) {
            is LaunchIntentBuilder.Result.Unbuildable -> toast("Cannot launch: ${'$'}{r.reason}")
            is LaunchIntentBuilder.Result.Ready -> {
                runCatching { startActivity(r.intent) }
                    .onSuccess {
                        verifyFor = id to game.title
                        // Persist BEFORE the emulator takes over: if this
                        // process is killed we still know what to ask.
                        prefs.edit()
                            .putString(KEY_VERIFY_ID, id)
                            .putString(KEY_VERIFY_TITLE, game.title)
                            .putString(KEY_VERIFY_SPEC, specJson(spec))
                            .apply()
                    }
                    .onFailure { toast("Launch failed: ${'$'}{it.message}") }
            }
        }
    }

    /**
     * The user's answer is the only thing that sets `verified`. Never infer it
     * from the app having started - a wrong intent can leave an emulator sitting
     * on its own menu looking perfectly healthy.
     */
    private fun answerVerify(loaded: Boolean) {
        val (id, _) = verifyFor ?: return
        verifyFor = null
        // The in-memory copy is gone if the process was killed while the
        // emulator ran, so fall back to what we persisted.
        val spec = editingSpec ?: prefs.getString(KEY_VERIFY_SPEC, null)?.let(::specFromJson)
        prefs.edit()
            .remove(KEY_VERIFY_ID).remove(KEY_VERIFY_TITLE).remove(KEY_VERIFY_SPEC)
            .apply()
        val p = loadProfileById(id) ?: return
        if (loaded && spec != null) {
            val err = app.profiles.save(p.copy(launch = spec, verified = true))
            if (err != null) { toast(err); return }
            editingId = null; editingSpec = null
            reload()
            toast("Verified — ${'$'}{p.name} launches correctly")
        } else {
            toast("Left unverified. Adjust the intent and test again.")
        }
    }

    private fun specJson(spec: org.lighthouse.data.LaunchSpec): String =
        kotlinx.serialization.json.Json.encodeToString(
            org.lighthouse.data.LaunchSpec.serializer(), spec)

    private fun specFromJson(s: String): org.lighthouse.data.LaunchSpec? = runCatching {
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
            .decodeFromString(org.lighthouse.data.LaunchSpec.serializer(), s)
    }.getOrNull()

    private fun toggleApp(pkg: String, add: Boolean) {
        val p = appShelfProfile() ?: run {
            toast("Add the Android system first")
            return
        }
        val next = if (add) (p.source.packages + pkg).distinct()
                   else p.source.packages - pkg
        app.profiles.save(p.copy(source = p.source.copy(packages = next)))?.let { toast(it) }
    }

    private fun togglePlatform(id: String, enabled: Boolean) {
        val p = loadProfileById(id) ?: return
        app.profiles.save(p.copy(enabled = enabled))?.let { toast(it) }
        reload()
    }

    private fun removePlatform(id: String) {
        // Only the profile goes; imported games and art stay in the library so
        // re-adding the system does not mean re-importing it.
        app.profiles.delete(id)
        reload()
        toast("Removed. Imported games and art are kept.")
    }

    private fun addSystem(system: org.lighthouse.data.CatalogueSystem) {
        val emu = app.catalogue.installedFor(system).firstOrNull()
        val order = (pages.maxOfOrNull { it.profile.order } ?: 0) + 1
        app.profiles.save(app.catalogue.profileFor(system, emu, order))?.let { toast(it) }
        reload()
        toast(
            if (emu == null) "Added ${'$'}{system.name} — set its emulator and folder in Settings"
            else "Added ${'$'}{system.name} using ${'$'}{emu.name}"
        )
    }

    private fun pickTheme(id: String?) {
        app.themes.selectedId = id
        toast(if (id == null) "Reset to the built-in theme" else "Theme: ${'$'}id")
        recreate()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        const val KEY_VERIFY_ID = "verify_id"
        const val KEY_VERIFY_TITLE = "verify_title"
        const val KEY_VERIFY_SPEC = "verify_spec"
    }
}
