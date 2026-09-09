package app.niix.ui

import android.app.Activity
import android.view.WindowManager
import app.niix.NiixApp
import app.niix.core.storage.SettingsStore

/**
 * Applies (or clears) screenshot and screen-recording protection according to the user's setting.
 *
 * Two things about this are deliberate:
 *
 * It must be called from `onResume`, not only `onCreate`. Android caches FLAG_SECURE per window,
 * so a screen created while screenshots were allowed keeps allowing them for its whole lifetime.
 * Turning the setting off in Settings and returning to a screen that was already open therefore
 * left that screen unprotected -- which is exactly the case where someone believes they've
 * enabled protection and haven't.
 *
 * It clears the flag as well as setting it. Applying it one way only would mean the setting could
 * be turned on but never off without restarting the app.
 */
fun Activity.applyScreenshotPolicy() {
    val allowScreenshots = runCatching {
        val container = (application as NiixApp).container
        container.storage.settings.getBool(SettingsStore.KEY_ALLOW_SCREENSHOTS, false)
    }.getOrDefault(false)

    if (allowScreenshots) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
    }
}
