package org.lighthouse.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Loads platform profiles from disk.
 *
 * Bundled presets ship in assets/platforms/ and are extracted once into a
 * user-writable directory. A user file with the same id WINS, and extraction
 * never overwrites an existing file - so an app update can ship a corrected
 * Xbox profile without destroying one the user has tuned. Same pattern
 * Xenia-AE already uses for its bundled patches and LUTs.
 */
class ProfileStore(private val context: Context) {

    data class Loaded(
        val profiles: List<PlatformProfile>,
        /** id (or filename) -> why it was rejected. Surfaced in Settings. */
        val problems: Map<String, String>,
    )

    private val json = Json {
        ignoreUnknownKeys = false   // a typo'd key is a bug the user should see
        isLenient = true
        prettyPrint = true
    }

    val dir: File get() = File(context.getExternalFilesDir(null), "platforms")

    /** Copy bundled presets in, without ever clobbering the user's own files. */
    fun installBundled() {
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "could not create $dir")
            return
        }
        // Existing ids, not just existing filenames. A bundled preset and an
        // imported profile can describe the same system under different file
        // names (gamecube.json vs gc.json, both id "gc"), which produced a
        // duplicate platform in the UI - one working, one broken.
        val existingIds = load().profiles.map { it.id }.toSet()

        val names = runCatching { context.assets.list("platforms") }.getOrNull().orEmpty()
        for (n in names) {
            if (!n.endsWith(".json")) continue
            val out = File(dir, n)
            if (out.exists()) continue          // user's copy wins, always
            val bundledId = runCatching {
                json.decodeFromString<PlatformProfile>(
                    context.assets.open("platforms/$n").bufferedReader().use { it.readText() }
                ).id
            }.getOrNull()
            if (bundledId != null && bundledId in existingIds) continue
            runCatching {
                context.assets.open("platforms/$n").use { i ->
                    out.outputStream().use { o -> i.copyTo(o) }
                }
            }.onFailure { Log.e(TAG, "extract $n failed", it) }
        }
    }

    fun load(): Loaded {
        val profiles = mutableListOf<PlatformProfile>()
        val problems = linkedMapOf<String, String>()

        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            .orEmpty()

        for (f in files) {
            // Report, never skip silently. A profile that quietly vanishes
            // looks identical to one that was never written.
            val parsed = runCatching { json.decodeFromString<PlatformProfile>(f.readText()) }
            val p = parsed.getOrNull()
            if (p == null) {
                val e = parsed.exceptionOrNull()
                problems[f.name] = "could not be read: ${e?.message ?: e?.let { it::class.simpleName }}"
                continue
            }
            when (val c = p.validate()) {
                is ProfileCheck.Ok -> profiles += p
                is ProfileCheck.Invalid -> problems[p.id.ifBlank { f.name }] = c.reason
            }
        }

        // Two files can still claim the same id (a hand-copied profile, say).
        // Keep the first and SAY SO - silently dropping one would leave the user
        // editing a file that has no effect.
        val seen = mutableSetOf<String>()
        val unique = mutableListOf<PlatformProfile>()
        for (p in profiles) {
            if (!seen.add(p.id)) {
                problems[p.id] = "duplicate id — a second profile with this id was ignored"
                continue
            }
            unique += p
        }
        return Loaded(unique.sortedBy { it.order }, problems)
    }

    /**
     * @return null on success, or a human-readable reason.
     *
     * A launcher must not die because one file will not write. This actually
     * happened: a profile copied onto the device by another tool was owned by a
     * different uid, and the uncaught EACCES took the whole app down mid-edit.
     */
    fun save(p: PlatformProfile): String? {
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            File(dir, "${p.id}.json")
                .writeText(json.encodeToString(PlatformProfile.serializer(), p))
            null
        }.getOrElse { e ->
            Log.e(TAG, "could not save ${p.id}", e)
            when (e) {
                is java.io.FileNotFoundException ->
                    "${p.id}.json is not writable — it may be owned by another app or tool"
                else -> "Could not save ${p.id}: ${e.message ?: e::class.simpleName}"
            }
        }
    }

    fun delete(id: String): Boolean =
        runCatching { File(dir, "$id.json").delete() }.getOrDefault(false)

    private companion object { const val TAG = "LH.ProfileStore" }
}
