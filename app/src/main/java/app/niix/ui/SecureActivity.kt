package app.niix.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.niix.AppContainer
import app.niix.NiixApp
import app.niix.R

open class SecureActivity : AppCompatActivity() {

    protected val container: AppContainer get() = (application as NiixApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDarkSystemBars()
        applyScreenshotPolicy()
    }

    private fun applyDarkSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val bg = ContextCompat.getColor(this, R.color.niix_bg)
        @Suppress("DEPRECATION")
        window.statusBarColor = bg
        @Suppress("DEPRECATION")
        window.navigationBarColor = bg
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    override fun onResume() {
        super.onResume()
        // On every resume, because FLAG_SECURE belongs to the window: a screen opened before the
        // setting changed would otherwise keep the old behaviour.
        applyScreenshotPolicy()
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
