package org.lighthouse

import android.app.Application
import org.lighthouse.data.ProfileStore
import org.lighthouse.theme.ThemeStore

class LightHouseApp : Application() {

    lateinit var profiles: ProfileStore
        private set
    lateinit var themes: ThemeStore
        private set

    override fun onCreate() {
        super.onCreate()
        profiles = ProfileStore(this)
        themes = ThemeStore(this)
        // Extract bundled presets on first run and after an update. Neither call
        // ever overwrites an existing file, so a user's edited Xbox profile or
        // hand-tuned theme survives every update.
        profiles.installBundled()
        themes.installBundled()
    }

    companion object {
        lateinit var instance: LightHouseApp
            private set
    }

    init {
        instance = this
    }
}
