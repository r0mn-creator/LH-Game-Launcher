package org.lighthouse.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persisted metadata for a game.
 *
 * Discovery finds *files*; it cannot know a publisher or a cover. So the
 * library is a separate, durable store that discovery results are merged
 * against. A record can also exist with no matching file yet (imported before
 * the folder was granted), which is why nothing here assumes the file is
 * currently reachable.
 */
@Serializable
data class GameRecord(
    /** Stable identity: the game's URI for file-backed, or "platform:id". */
    val key: String,
    val platformId: String,
    val title: String,
    /** Original file/document URI, when file-backed. */
    val uri: String? = null,
    /** Library id for id-backed platforms. */
    val libraryId: String? = null,
    @SerialName("cover") val coverPath: String? = null,
    @SerialName("screenshot") val screenshotPath: String? = null,
    val publisher: String? = null,
    val developer: String? = null,
    val genres: String? = null,
    @SerialName("release_date") val releaseDate: Long? = null,
    val favourite: Boolean = false,
    @SerialName("last_played") val lastPlayed: Long? = null,
) {
    /**
     * Filename without extension or bracketed region tags, lowercased.
     * Used to re-link an imported record to a scanned file when the URI does
     * not match - which is the normal case, because a Beacon document URI was
     * granted to Beacon, not to us.
     */
    val matchName: String
        get() = title.lowercase()
            .replace(Regex("""\s*[\(\[][^)\]]*[\)\]]"""), "")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
}

/**
 * The library on disk. One JSON file, loaded once and kept in memory - 557
 * games is tiny, and a database would buy nothing here.
 */
class LibraryStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    private val file: File get() = File(context.getExternalFilesDir(null), "library.json")
    val mediaDir: File get() = File(context.getExternalFilesDir(null), "media")

    @Volatile
    private var cache: MutableList<GameRecord>? = null

    fun all(): List<GameRecord> {
        cache?.let { return it }
        val loaded = runCatching {
            if (!file.exists()) mutableListOf()
            else json.decodeFromString<List<GameRecord>>(file.readText()).toMutableList()
        }.getOrElse {
            Log.e(TAG, "library unreadable, starting empty", it)
            mutableListOf()
        }
        cache = loaded
        return loaded
    }

    fun forPlatform(platformId: String): List<GameRecord> =
        all().filter { it.platformId == platformId }

    fun replaceAll(records: List<GameRecord>) {
        cache = records.toMutableList()
        persist()
    }

    /** Add or update by key. */
    fun put(records: List<GameRecord>) {
        val list = all().toMutableList()
        for (r in records) {
            val i = list.indexOfFirst { it.key == r.key }
            if (i >= 0) list[i] = r else list += r
        }
        cache = list
        persist()
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(GameRecord.serializer()), all()))
        }.onFailure { Log.e(TAG, "could not save library", it) }
    }

    private companion object { const val TAG = "LH.Library" }
}
