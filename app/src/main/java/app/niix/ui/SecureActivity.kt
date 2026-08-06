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

    /**
     * Forces a dark status bar and navigation bar to match the app's theme.
     *
     * On Android 15+ (targetSdk 35) the framework enforces edge-to-edge and ignores the
     * `android:statusBarColor`/`android:navigationBarColor` theme attributes entirely, and some
     * OEM skins fall back to tinting the transparent system bars with the app's colorPrimary
     * (green here) instead of leaving them alone -- which is what produced the green bar behind
     * the status bar icons. Setting this explicitly through the Window/WindowInsetsController
     * APIs is the supported way to control system bar appearance on every Android version and
     * device skin, so every screen extending this class gets a consistent dark bar.
     */
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
                // No passcode configured: reopen automatically and stay right here, exactly
                // like an app with no lock at all -- there is no screen to bounce to.
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
