package app.niix.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
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
        val allowScreenshots = runCatching {

            container.storage.settings.getBool(app.niix.core.storage.SettingsStore.KEY_ALLOW_SCREENSHOTS, false)
        }.getOrDefault(false)
        if (!allowScreenshots) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
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
