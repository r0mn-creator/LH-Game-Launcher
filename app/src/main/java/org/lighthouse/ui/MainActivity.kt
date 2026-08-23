package org.lighthouse.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.lighthouse.LightHouseApp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import java.io.File
import org.lighthouse.core.DisplayGame
import org.lighthouse.core.LaunchIntentBuilder
import org.lighthouse.core.LibraryMerge
import org.lighthouse.data.PlatformProfile
import org.lighthouse.provider.Discovery
import org.lighthouse.provider.GameEntry
import org.lighthouse.provider.Providers
import org.lighthouse.theme.LocalTheme
import org.lighthouse.theme.ResolvedTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = LightHouseApp.instance
            var theme by remember { mutableStateOf(app.themes.active()) }
            CompositionLocalProvider(LocalTheme provides theme) {
                HomeScreen()
            }
        }
    }
}

private data class Section(
    val profile: PlatformProfile,
    val discovery: Discovery,
    val games: List<DisplayGame>,
)

@Composable
private fun HomeScreen() {
    val ctx = LocalContext.current
    val theme = LocalTheme.current
    val app = LightHouseApp.instance

    var sections by remember { mutableStateOf<List<Section>>(emptyList()) }
    var problems by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        val loaded = app.profiles.load()
        problems = loaded.problems
        sections = loaded.profiles
            .filter { it.enabled }
            .map { p ->
                val d = Providers.discover(ctx, p)
                Section(p, d, LibraryMerge.merge(d.games, app.library.forPlatform(p.id)))
            }
    }

    // A profile rejected for having no folder is not a dead end: offer the
    // picker right where the problem is reported.
    var pickFor by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val tree = result.data?.data
        val id = pickFor
        if (tree != null && id != null) {
            val all = app.profiles.load()
            val prof = all.profiles.firstOrNull { it.id == id }
                ?: rejectedProfile(app, id)
            if (prof != null) {
                if (FolderPicker.accept(ctx, app.profiles, prof, tree) != null) reload++
                else toast(ctx, "Could not keep access to that folder")
            }
        }
        pickFor = null
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(theme.background)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "LightHouse",
                color = theme.textPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Import from Beacon",
                color = theme.primary,
                fontSize = 14.sp,
                modifier = Modifier
                    .border(1.dp, theme.outline, RoundedCornerShape(8.dp))
                    .clickable {
                        val r = ImportSource.run(ctx)
                        // Always report what happened - matched, added, skipped -
                        // rather than silently merging hundreds of rows.
                        toast(ctx, r.summary())
                        r.notes.take(3).forEach { n -> toast(ctx, n) }
                        reload++
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(16.dp))

        // A profile that failed to load is shown, never silently dropped: an
        // invisible platform looks identical to one that was never created.
        if (problems.isNotEmpty()) {
            problems.forEach { (which, why) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    Text("⚠ $which — $why", color = theme.error, fontSize = 13.sp)
                    if (why.contains("no roots")) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Choose folder…",
                            color = theme.primary,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .border(1.dp, theme.outline, RoundedCornerShape(6.dp))
                                .clickable { pickFor = which; picker.launch(FolderPicker.intent()) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(22.dp)) {
            items(sections) { section -> PlatformRow(section) }
        }
    }
}

@Composable
private fun PlatformRow(section: Section) {
    val theme = LocalTheme.current
    val ctx = LocalContext.current

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                section.profile.name,
                color = theme.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "${section.games.size}",
                color = theme.textSecondary,
                fontSize = 14.sp,
            )
            if (!section.profile.verified) {
                Spacer(Modifier.width(10.dp))
                // Honest by default: a preset read from source but never run on
                // a real game says so, rather than implying it works.
                Text("untested", color = theme.secondary, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))

        if (section.games.isEmpty()) {
            section.discovery.notes.forEach {
                Text(it, color = theme.textSecondary, fontSize = 13.sp)
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(section.games) { g ->
                    GameCard(g) {
                        val e = g.entry
                        if (e == null) {
                            // Imported but the file is not reachable yet. Say
                            // exactly why rather than doing nothing on tap.
                            toast(ctx, "\"${'$'}{g.title}\" needs its folder re-granted in LightHouse")
                        } else {
                            launch(ctx, section.profile, e, theme)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameCard(game: DisplayGame, onClick: () -> Unit) {
    val theme = LocalTheme.current
    Column(
        Modifier
            .width(120.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .size(width = 120.dp, height = 160.dp)
                .clip(RoundedCornerShape(theme.file.shape.cornerRadius.dp))
                .background(theme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val cover = game.coverPath?.let { File(it) }?.takeIf { it.isFile }
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = game.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    game.title.take(2).uppercase(),
                    color = theme.textSecondary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (!game.playable) {
                // Present but not launchable - visibly distinct, never a silent
                // no-op when tapped.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(theme.background.copy(alpha = 0.6f))
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            game.title,
            color = theme.textPrimary,
            fontSize = 12.sp,
            maxLines = 2,
        )
    }
}

/**
 * Launch a game. Two routes: a built intent from the profile, or - for the
 * shortcut passthrough - LauncherApps.startShortcut.
 *
 * Every failure is reported to the user with a reason. A launcher that fails
 * silently is exactly what this project exists to replace.
 */
private fun launch(ctx: Context, profile: PlatformProfile, game: GameEntry, theme: ResolvedTheme) {
    if (game.shortcutId != null && game.shortcutPackage != null) {
        val la = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        if (la == null) {
            toast(ctx, "Shortcuts unavailable on this device")
            return
        }
        runCatching {
            la.startShortcut(
                game.shortcutPackage, game.shortcutId, null, null, Process.myUserHandle()
            )
        }.onFailure {
            toast(ctx, "Could not start shortcut: ${it.message ?: "LightHouse must be the default launcher"}")
        }
        return
    }

    val target = LaunchIntentBuilder.Target(uri = game.uri, id = game.id, title = game.title)
    when (val r = LaunchIntentBuilder.build(profile.launch, target)) {
        is LaunchIntentBuilder.Result.Unbuildable -> toast(ctx, "Cannot launch: ${r.reason}")
        is LaunchIntentBuilder.Result.Ready ->
            runCatching { ctx.startActivity(r.intent) }
                .onFailure { toast(ctx, "Launch failed: ${it.message ?: it::class.simpleName}") }
    }
}

/**
 * A profile that failed validation is not in the loaded list, so recover it from
 * disk to attach a folder. Without this the "Choose folder" button could only
 * ever fix profiles that were already working.
 */
private fun rejectedProfile(app: LightHouseApp, id: String): PlatformProfile? =
    runCatching {
        val f = java.io.File(app.profiles.dir, if (id.endsWith(".json")) id else "$id.json")
        if (!f.exists()) null
        else kotlinx.serialization.json.Json { isLenient = true; ignoreUnknownKeys = true }
            .decodeFromString<PlatformProfile>(f.readText())
    }.getOrNull()

private fun toast(ctx: Context, msg: String) =
    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
