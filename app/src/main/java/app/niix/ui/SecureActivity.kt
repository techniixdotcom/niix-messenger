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
        // Re-applied on every resume, not just at creation: a screen opened while screenshots
        // were still allowed would otherwise keep allowing them even after the setting is turned
        // off, because FLAG_SECURE is a property of the window rather than something re-read.
        applyScreenshotPolicy()
        if (!container.storage.appLock.isUnlocked() || container.lock.shouldLock()) {
            container.storage.appLock.lock()
            if (!container.storage.appLock.isPasscodeEnabled()) {

                if (container.storage.appLock.unlockWithoutPasscode() == app.niix.core.storage.UnlockResult.SUCCESS) {
                    container.lock.reset()
                    return
                }
            }
            val destination = if (container.storage.appLock.isDisguiseEnabled()) {
                CalculatorActivity::class.java
            } else {
                PasscodeActivity::class.java
            }
            startActivity(Intent(this, destination))
            finishAffinity()
            return
        }
        container.lock.reset()
    }

    override fun onStop() {
        super.onStop()
        container.lock.onBackgrounded()
    }
}
