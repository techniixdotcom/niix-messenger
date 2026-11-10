package app.niix.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.niix.AppContainer
import app.niix.NiixApp
import app.niix.R

open class SecureActivity : AppCompatActivity() {

    protected val container: AppContainer get() = (application as NiixApp).container

    /** The theme this screen was made with, so it can follow a change made on another screen. */
    private lateinit var shownTheme: NiixTheme

    override fun onCreate(savedInstanceState: Bundle?) {
        shownTheme = useNiixTheme()
        super.onCreate(savedInstanceState)
        applySystemBars()
        applyScreenshotPolicy()
    }

    /** Bars in the theme's background, with dark icons on the Light theme and light ones otherwise. */
    private fun applySystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val bg = themeColor(R.attr.niixBg)
        @Suppress("DEPRECATION")
        window.statusBarColor = bg
        @Suppress("DEPRECATION")
        window.navigationBarColor = bg
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = shownTheme.isLight
            isAppearanceLightNavigationBars = shownTheme.isLight
        }
    }

    /**
     * True for a screen that may show while the app is locked. Only the call screen is, and only
     * to say that a call is ringing: it shows nothing from the database, which stays closed.
     */
    protected open val showsWhileLocked: Boolean get() = false

    override fun onResume() {
        super.onResume()
        // The theme was changed in Settings while this screen sat behind it.
        if (NiixTheme.current(this) != shownTheme) {
            recreate()
            return
        }
        // On every resume, because FLAG_SECURE belongs to the window: a screen opened before the
        // setting changed would otherwise keep the old behaviour.
        applyScreenshotPolicy()
        if (showsWhileLocked) return
        val appLock = container.storage.appLock
        // Without a passcode there's nothing to lock behind, and closing the database only to
        // reopen it straight away cost a key derivation on the main thread every time.
        if (appLock.isUnlocked() && (!appLock.isPasscodeEnabled() || !container.lock.shouldLock())) {
            container.lock.reset()
            // Without a passcode the app can stay open for days with no unlock to restart the
            // inactivity countdown, so being used counts too.
            appLock.recordUse()
            return
        }
        // Locked, or the timeout ran out. The lock screen does the unlocking, off the main thread.
        container.lock()
        startActivity(Intent(this, PasscodeActivity::class.java))
        finishAffinity()
    }
}
