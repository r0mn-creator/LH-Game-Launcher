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
import androidx.compose.runtime.*
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
    private var showSettings by mutableStateOf(false)
    private var showAppPicker by mutableStateOf(false)
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
                if (showSettings) {
                    SettingsScreen(
                        state = settingsState(),
                        onBack = { showSettings = false },
                        onImport = ::runImport,
                        onSetupFolders = { showSettings = false; startSetup() },
                        onRescan = ::reload,
                        onTogglePlatform = ::togglePlatform,
                        onChooseFolder = { showSettings = false; chooseFolder(it) },
                        onAddSystem = ::addSystem,
                        onRemovePlatform = ::removePlatform,
                        onPickTheme = ::pickTheme,
                        onChooseApps = { showAppPicker = true },
                        onToggleApp = ::toggleApp,
                        onCloseAppPicker = { showAppPicker = false; reload() },
                        onCycleAspect = ::cycleAspect,
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
        reload()
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
            Nav.LEFT, Nav.RIGHT, Nav.UP, Nav.DOWN ->
                cursor = cursor.move(nav, page?.games?.size ?: 0)
            Nav.LAUNCH -> {
                val g = page?.games?.getOrNull(cursor.index)
                if (page != null && g != null) launch(page, g)
                else if (page != null && page.problem?.contains("no roots") == true) {
                    chooseFolder(page.profile.id)
                }
            }
            Nav.BACK -> when {
                showAppPicker -> { showAppPicker = false; reload() }
                showSettings -> showSettings = false
                else -> Unit          // a launcher has nowhere to go back to
            }
            Nav.MENU -> showSettings = !showSettings
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
            appPicker = if (!showAppPicker) null else {
                val chosen = appShelfProfile()?.source?.packages.orEmpty().toSet()
                org.lighthouse.provider.InstalledAppsProvider.installedApps(this)
                    .map { (pkg, label) -> AppChoice(pkg, label, pkg in chosen) }
                    // Added ones first so the shelf is easy to review.
                    .sortedWith(compareByDescending<AppChoice> { it.chosen }
                        .thenBy { it.label.lowercase() })
            },
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
        app.profiles.save(p.copy(aspectRatio = next))
        reload()
    }

    private fun toggleApp(pkg: String, add: Boolean) {
        val p = appShelfProfile() ?: run {
            toast("Add the Android system first")
            return
        }
        val next = if (add) (p.source.packages + pkg).distinct()
                   else p.source.packages - pkg
        app.profiles.save(p.copy(source = p.source.copy(packages = next)))
    }

    private fun togglePlatform(id: String, enabled: Boolean) {
        val p = loadProfileById(id) ?: return
        app.profiles.save(p.copy(enabled = enabled))
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
        app.profiles.save(app.catalogue.profileFor(system, emu, order))
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
}
