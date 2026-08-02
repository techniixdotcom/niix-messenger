package app.niix.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import app.niix.AppContainer
import app.niix.NiixApp

open class SecureActivity : AppCompatActivity() {

    protected val container: AppContainer get() = (application as NiixApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    override fun onResume() {
        super.onResume()
        if (!container.storage.appLock.isUnlocked() || container.lock.shouldLock()) {
            container.storage.appLock.lock()
            startActivity(Intent(this, CalculatorActivity::class.java))
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
