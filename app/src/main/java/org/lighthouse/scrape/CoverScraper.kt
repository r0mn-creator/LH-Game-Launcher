// Copyright 2026 r0mn-creator
// SPDX-License-Identifier: Apache-2.0

package org.lighthouse.scrape

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Box art from libretro's thumbnail server.
 *
 * Chosen over IGDB / TheGamesDB / SteamGridDB because it needs **no API key and
 * no account**: those all ship a secret in the APK, which is both a licensing
 * problem and something that breaks the day the key is revoked. Of the sources
 * worth having, this is the one an open project can actually ship.
 *
 *     https://thumbnails.libretro.com/<Platform>/Named_Boxarts/<Game>.png
 *
 * Our platform names already match libretro's directory names, because the
 * catalogue follows libretro-database naming - so there is no mapping table to
 * maintain, only a normalised lookup for the few that differ ("Sega Dreamcast"
 * vs "Sega - Dreamcast").
 */
object CoverScraper {

    private const val BASE = "https://thumbnails.libretro.com"
    private const val TAG = "LH.Scraper"

    data class Target(
        val key: String,
        val platformId: String,
        val platformName: String,
        val title: String,
    )

    data class Found(val key: String, val file: File)

    data class Report(
        val found: List<Found>,
        /** title -> why nothing was fetched. */
        val unmatched: List<String>,
        val notes: List<String>,
    ) {
        fun summary(): String = when {
            found.isEmpty() && unmatched.isEmpty() -> "Every game already has art"
            found.isEmpty() -> "No art found for any of the ${unmatched.size}"
            unmatched.isEmpty() -> "Found art for all ${found.size}"
            else -> "Found art for ${found.size}; no match for ${unmatched.size}"
        }
    }

    /**
     * Builds where they exist and nowhere else: a demo, kiosk or prototype dump
     * carries the right *name* and the wrong *box*, and it wins on length in the
     * near-match pass. Dropping them up front is cheaper than out-ranking them.
     */
    private val BUILD_VARIANT =
        Regex("""\((Demo|Kiosk|Beta|Proto|Sample|Program|Test|Debug)[^)]*\)|Trial Version""",
            RegexOption.IGNORE_CASE)

    private val TAG_GROUP = Regex("""\s*[\(\[{][^)\]}]*[)\]}]""")
    private val SET_INDEX = Regex("""^\d{3,4}\s*-\s*""")
    private val EXTENSION = Regex("""\.[A-Za-z0-9]{1,4}$""")
    private val NOISE = Regex("""\b(the|and|gba|gbc|nes|snes|n64)\b""", RegexOption.IGNORE_CASE)

    /**
     * Titles compared with region tags, set indices, articles and punctuation
     * gone. Deliberately lossy: this library has "Zelda - the Minish Cap GBA"
     * where the dump is "Legend of Zelda, The - The Minish Cap (USA)".
     */
    fun norm(s: String): String = s
        .replace(EXTENSION, "")
        .replace(SET_INDEX, "")
        .replace(TAG_GROUP, "")
        .replace("#", " ")
        .replace(NOISE, " ")
        .lowercase()
        .replace(Regex("""[^a-z0-9]+"""), "")

    /** USA first, then World/Europe/Japan; a Virtual Console re-release last. */
    private fun rank(name: String): Int {
        val region = when {
            name.contains("(USA") -> 0
            name.contains("(World") -> 1
            name.contains("(Europe") -> 2
            name.contains("(Japan") -> 3
            else -> 4
        }
        return region * 2 + if (name.contains("Virtual Console")) 1 else 0
    }

    // ---- fetching -----------------------------------------------------------

    private fun get(url: String): String? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "LightHouse")
            if (responseCode != 200) {
                Log.w(TAG, "$responseCode for $url"); disconnect(); return null
            }
            inputStream.bufferedReader().use { it.readText() }.also { disconnect() }
        }
    }.onFailure { Log.w(TAG, "fetch failed: $url", it) }.getOrNull()

    private fun download(url: String, dest: File): Boolean = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "LightHouse")
            if (responseCode != 200) { disconnect(); return false }
            dest.parentFile?.mkdirs()
            // Straight to a temp file, then rename: a half-written cover that
            // Coil later fails to decode looks exactly like a scraper bug.
            val tmp = File(dest.parentFile, dest.name + ".part")
            inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            disconnect()
            tmp.renameTo(dest)
        }
    }.onFailure { Log.w(TAG, "download failed: $url", it) }.getOrDefault(false)

    private val HREF = Regex("href=\"([^\"]+)\"")

    private fun links(html: String): List<String> =
        HREF.findAll(html).map { decode(it.groupValues[1]) }.toList()

    private fun decode(s: String): String = runCatching {
        java.net.URLDecoder.decode(s.replace("&amp;", "&"), "UTF-8")
    }.getOrDefault(s)

    private fun encode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---- the pass -----------------------------------------------------------

    /**
     * @param mediaDir covers are written under `<platformId>` then `scraped`
     * @param onProgress called with a human-readable step, for the UI
     */
    fun scrape(
        targets: List<Target>,
        mediaDir: File,
        onProgress: (String) -> Unit = {},
    ): Report {
        if (targets.isEmpty()) return Report(emptyList(), emptyList(), emptyList())

        val found = mutableListOf<Found>()
        val unmatched = mutableListOf<String>()
        val notes = mutableListOf<String>()

        onProgress("Reading the libretro platform list…")
        val root = get("$BASE/")
        if (root == null) {
            return Report(emptyList(), targets.map { it.title },
                listOf("Could not reach thumbnails.libretro.com. Check the network."))
        }
        val dirs = links(root).filter { it.endsWith("/") }
            .map { it.trimEnd('/') }
            .filter { it.isNotBlank() && !it.startsWith(".") && !it.startsWith("http") }
        val dirByNorm = dirs.associateBy { norm(it) }

        for ((platformId, group) in targets.groupBy { it.platformId }) {
            val platformName = group.first().platformName
            val dir = dirs.firstOrNull { it.equals(platformName, true) }
                ?: dirByNorm[norm(platformName)]
            if (dir == null) {
                notes += "$platformName has no thumbnail set on libretro."
                unmatched += group.map { it.title }
                continue
            }

            onProgress("$platformName: reading the box art index…")
            val index = get("$BASE/${encode(dir)}/Named_Boxarts/")
            if (index == null) {
                notes += "$platformName: could not read its box art index."
                unmatched += group.map { it.title }
                continue
            }

            val files = links(index)
                .filter { it.endsWith(".png", true) && !it.contains('/') }
                .filterNot { BUILD_VARIANT.containsMatchIn(it) }
            val table = files.groupBy { norm(it) }
                .mapValues { (_, v) -> v.sortedBy { rank(it) } }
            val keys = table.keys.toList()

            var done = 0
            for (t in group) {
                done++
                onProgress("$platformName: ${t.title} ($done of ${group.size})")
                val q = norm(t.title)
                val pick = table[q]?.firstOrNull() ?: nearMatch(q, keys, table)
                if (pick == null) { unmatched += t.title; continue }

                val dest = File(mediaDir, "$platformId/scraped/${safe(t.title)}.png")
                val url = "$BASE/${encode(dir)}/Named_Boxarts/${encode(pick)}"
                if (dest.isFile && dest.length() > 0) {
                    found += Found(t.key, dest)
                } else if (download(url, dest)) {
                    found += Found(t.key, dest)
                } else {
                    unmatched += t.title
                }
            }
        }
        return Report(found, unmatched, notes)
    }

    /**
     * Only ever matches a query that sits INSIDE a longer official name, never
     * the reverse. That direction matters: with it reversed, "Pokemon - Fire
     * Red" matched a file simply called "Pokemon" and the shelf would have shown
     * the wrong game's box with no way to tell. Wrong art is worse than none.
     */
    private fun nearMatch(
        q: String,
        keys: List<String>,
        table: Map<String, List<String>>,
    ): String? {
        if (q.length < 6) return null
        val hits = keys.filter { it.contains(q) }
        if (hits.isEmpty()) return null
        val best = hits.minWithOrNull(
            compareBy({ it.length }, { rank(table.getValue(it).first()) })
        ) ?: return null
        return table[best]?.firstOrNull()
    }

    private fun safe(s: String): String =
        s.replace(Regex("""[^A-Za-z0-9 ._-]"""), "_").take(120).trim()
}
