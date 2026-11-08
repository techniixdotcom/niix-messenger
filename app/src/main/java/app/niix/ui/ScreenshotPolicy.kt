package app.niix.ui

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import app.niix.NiixApp
import app.niix.core.storage.SettingsStore

/**
 * Sets or clears screenshot and screen-recording protection to match the user's setting.
 *
 * Call it from onResume, not just onCreate: FLAG_SECURE sticks to a window, so a screen opened
 * before the setting changed would keep the old behaviour.
 *
 * The setting lives in the encrypted database, which is closed on the lock screen and doesn't exist
 * yet during setup. So the last value read is mirrored in a plain preference and used whenever the
 * database can't be read. All it reveals is whether screenshots are blocked.
 */
fun Activity.applyScreenshotPolicy() {
    val mirror = getSharedPreferences(SCREENSHOT_MIRROR_PREFS, Context.MODE_PRIVATE)
    val fromDatabase = runCatching {
        (application as NiixApp).container.storage.settings.getBool(SettingsStore.KEY_ALLOW_SCREENSHOTS, true)
    }.getOrNull()
    if (fromDatabase != null && mirror.getBoolean(KEY_ALLOW, true) != fromDatabase) {
        mirror.edit().putBoolean(KEY_ALLOW, fromDatabase).apply()
    }

    if (fromDatabase ?: mirror.getBoolean(KEY_ALLOW, true)) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }
}

internal const val SCREENSHOT_MIRROR_PREFS = "screen"
private const val KEY_ALLOW = "allow_screenshots"
