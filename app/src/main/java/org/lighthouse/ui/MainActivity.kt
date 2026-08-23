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
        } else {
            reload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        immersive()
        setContent {
            val theme by remember { mutableStateOf(app.themes.active()) }
            CompositionLocalProvider(LocalTheme provides theme) {
                HomeScreen(
                    pages = pages,
                    systemIndex = systemIndex,
                    cursor = cursor,
                    onChooseFolder = ::chooseFolder,
                    onImport = ::runImport,
                    onLaunch = ::launch,
                )
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
            Nav.BACK -> Unit          // a launcher has nowhere to go back to
            Nav.MENU -> toast("Settings are not built yet")
            Nav.SEARCH -> toast("Search is not built yet")
        }
    }

    // ---- actions ------------------------------------------------------------

    private fun chooseFolder(platformId: String) {
        pendingFolderFor = platformId
        folderPicker.launch(FolderPicker.intent())
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
