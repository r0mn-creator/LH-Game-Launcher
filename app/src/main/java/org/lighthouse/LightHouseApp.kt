package org.lighthouse

import android.app.Application
import org.lighthouse.data.CatalogueStore
import org.lighthouse.data.LauncherConfig
import org.lighthouse.data.LibraryStore
import org.lighthouse.data.ProfileStore
import org.lighthouse.theme.ColorThemeStore
import org.lighthouse.theme.ColorThemes
import org.lighthouse.theme.ResolvedTheme
import org.lighthouse.theme.ThemeStore

class LightHouseApp : Application() {

    lateinit var profiles: ProfileStore
        private set
    lateinit var themes: ThemeStore
        private set
    lateinit var library: LibraryStore
        private set
    lateinit var catalogue: CatalogueStore
        private set
    lateinit var colors: ColorThemeStore
        private set
    lateinit var config: LauncherConfig
        private set

    override fun onCreate() {
        super.onCreate()
        profiles = ProfileStore(this)
        themes = ThemeStore(this)
        library = LibraryStore(this)
        catalogue = CatalogueStore(this)
        colors = ColorThemeStore(this)
        config = LauncherConfig(this)
        // Extract bundled presets on first run and after an update. Neither call
        // ever overwrites an existing file, so a user's edited Xbox profile or
        // hand-tuned theme survives every update.
        profiles.installBundled()
        themes.installBundled()
        colors.installBundled()
    }

    /** The active palette, from the colour theme named in lighthouse.conf. */
    fun activeColors(): ResolvedTheme {
        val want = config.colorTheme ?: return ColorThemes.resolve(ColorThemes.DEFAULT)
        val t = colors.load().themes.firstOrNull { it.name == want }
            ?: ColorThemes.DEFAULT      // a deleted or renamed theme falls back
        return ColorThemes.resolve(t)
    }

    companion object {
        lateinit var instance: LightHouseApp
            private set
    }

    init {
        instance = this
    }
}
