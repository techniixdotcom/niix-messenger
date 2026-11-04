package app.niix.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.niix.AppContainer
import app.niix.NiixApp
import app.niix.R
import app.niix.TempFileGuard
import app.niix.UnlockFlow
import app.niix.core.storage.UnlockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PasscodeActivity : AppCompatActivity() {

    private val container: AppContainer get() = (application as NiixApp).container
    private val entered = StringBuilder()
    private lateinit var display: TextView
    private lateinit var console: ConsoleLog
    private lateinit var keys: List<Button>

    // One attempt at a time. Each costs a 64 MB Argon2 run, and a second tap while the first is
    // still working would double the memory and race it for the database.
    private var unlocking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_passcode)
        // The passcode screen is exactly what shouldn't be capturable.
        applyScreenshotPolicy()
        display = findViewById(R.id.passcode_display)
        console = ConsoleLog(findViewById(R.id.passcode_console))

        val grid = findViewById<GridLayout>(R.id.passcode_grid)
        keys = (0 until grid.childCount).mapNotNull { grid.getChildAt(it) as? Button }
        keys.filter { it.id != R.id.passcode_unlock }.forEach { key -> key.setOnClickListener { onKey(key.text.toString()) } }
        findViewById<Button>(R.id.passcode_unlock).setOnClickListener { submit() }
    }

    override fun onResume() {
        super.onResume()
        applyScreenshotPolicy()
        // Coming back while an attempt is still running (the screen went off, say). Locking now
        // would close the database under it.
        if (unlocking) return
        TempFileGuard.purge(this)
        val appLock = container.storage.appLock

        // No account yet: go to onboarding. isSetUp() is only false before an identity exists, so
        // this can't redirect an existing user.
        if (!appLock.isSetUp()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        // The inactivity wipe is checked here, where anyone who picks the phone up arrives, before
        // anything is opened. The wipe itself runs in the background because it waits for Tor to
        // stop.
        if (runCatching { appLock.inactivityWipeDue() }.getOrDefault(false)) {
            setUnlocking(true)
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { container.wipeIfInactive() }
                startActivity(
                    Intent(this@PasscodeActivity, OnboardingActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            }
            return
        }

        if (!appLock.isPasscodeEnabled()) {
            if (appLock.isUnlocked()) {
                container.lock.reset()
                goHome()
                return
            }
            setUnlocking(true)
            console.step(getString(R.string.step_open))
            lifecycleScope.launch {
                val result = withContext(Dispatchers.Default) { runCatching { appLock.unlockWithoutPasscode() }.getOrNull() }
                setUnlocking(false)
                if (result == UnlockResult.SUCCESS) {
                    console.ok()
                    container.lock.reset()
                    goHome()
                } else {
                    console.fail(getString(R.string.console_failed))
                }
            }
            return
        }

        container.lock()
        entered.clear()
        console.clear()
        render()
    }

    private fun onKey(key: String) {
        if (unlocking) return
        console.clear()
        if (key == "⌫") {
            if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
        } else {
            entered.append(key)
        }
        render()
    }

    private fun render() {
        display.text = if (entered.isEmpty()) getString(R.string.passcode_placeholder) else "•".repeat(entered.length)
    }

    private fun setUnlocking(value: Boolean) {
        unlocking = value
        keys.forEach { it.isEnabled = !value }
        render()
    }

    private fun submit() {
        if (unlocking) return
        val raw = entered.toString()
        if (raw.length < 6) {
            android.widget.Toast.makeText(this, getString(R.string.passcode_too_short), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        setUnlocking(true)
        console.clear()
        console.step(getString(R.string.step_unlock))
        lifecycleScope.launch {
            val opened = UnlockFlow.attempt(container, raw)
            if (opened) {
                console.ok()
                goHome()
            } else {
                entered.clear()
                setUnlocking(false)
                val remainingMillis = UnlockFlow.throttleRemainingMillis(container)
                console.fail(
                    if (remainingMillis > 0) {
                        getString(R.string.passcode_throttled, formatWait(remainingMillis))
                    } else {
                        getString(R.string.console_incorrect)
                    },
                )
            }
        }
    }

    private fun formatWait(remainingMillis: Long): String {
        val totalSeconds = (remainingMillis + 999) / 1000
        return if (totalSeconds < 60) {
            getString(R.string.duration_seconds, totalSeconds)
        } else {
            val minutes = (totalSeconds + 59) / 60
            getString(R.string.duration_minutes, minutes)
        }
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
