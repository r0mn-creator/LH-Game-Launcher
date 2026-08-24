// Copyright 2026 r0mn-creator
// SPDX-License-Identifier: Apache-2.0

package org.lighthouse.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The launcher's own config file.
 *
 * Plain `key = value`, same spirit as the colour themes: something a person can
 * open and edit. Settings that describe *what the launcher is set to* live here
 * rather than in SharedPreferences, so the whole configuration is visible,
 * portable and backup-able as ordinary files.
 *
 *     # LightHouse
 *     color_theme = Cyber-CRT
 */
class LauncherConfig(private val context: Context) {

    private val file: File get() = File(context.getExternalFilesDir(null), "lighthouse.conf")

    @Volatile private var cache: MutableMap<String, String>? = null

    private fun all(): MutableMap<String, String> {
        cache?.let { return it }
        val m = linkedMapOf<String, String>()
        runCatching {
            if (file.isFile) {
                for (raw in file.readLines()) {
                    val line = raw.substringBefore('#').trim()
                    if (line.isEmpty() || !line.contains('=')) continue
                    m[line.substringBefore('=').trim()] = line.substringAfter('=').trim()
                }
            }
        }.onFailure { Log.e(TAG, "could not read config", it) }
        cache = m
        return m
    }

    fun get(key: String): String? = all()[key]?.takeIf { it.isNotBlank() }

    /** @return null on success, or a reason. Never throws - see ProfileStore. */
    fun set(key: String, value: String?): String? {
        val m = all()
        if (value == null) m.remove(key) else m[key] = value
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(buildString {
                appendLine("# LightHouse configuration")
                appendLine("# Edit freely; one key = value per line.")
                m.forEach { (k, v) -> appendLine("$k = $v") }
            })
            null
        }.getOrElse {
            Log.e(TAG, "could not write config", it)
            "lighthouse.conf is not writable: ${it.message ?: it::class.simpleName}"
        }
    }

    var colorTheme: String?
        get() = get(KEY_COLOR_THEME)
        set(v) { set(KEY_COLOR_THEME, v) }

    /** False until first-run setup has been completed or skipped. */
    var setupComplete: Boolean
        get() = get(KEY_SETUP) == "true"
        set(v) { set(KEY_SETUP, if (v) "true" else null) }

    companion object {
        const val KEY_SETUP = "setup_complete"
        const val KEY_COLOR_THEME = "color_theme"
        private const val TAG = "LH.Config"
    }
}
