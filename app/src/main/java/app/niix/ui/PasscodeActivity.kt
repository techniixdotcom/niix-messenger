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
import kotlinx.coroutines.launch

class PasscodeActivity : AppCompatActivity() {

    private val container: AppContainer get() = (application as NiixApp).container
    private val entered = StringBuilder()
    private lateinit var display: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_passcode)
        // The passcode entry screen is exactly what shouldn't be capturable.
        applyScreenshotPolicy()
        display = findViewById(R.id.passcode_display)

        val grid = findViewById<GridLayout>(R.id.passcode_grid)
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            if (child is Button && child.id != R.id.passcode_unlock) {
                child.setOnClickListener { onKey(child.text.toString()) }
            }
        }
        findViewById<Button>(R.id.passcode_unlock).setOnClickListener { submit() }
    }

    override fun onResume() {
        super.onResume()
        applyScreenshotPolicy()
        container.lock()
        TempFileGuard.purge(this)
        entered.clear()
        render()

        // A device with no account yet goes to onboarding.
        //
        // Onboarding was only ever reachable through the calculator's registration code, which
        // worked while the disguise was the default. It is not any more, so a new user landing
        // here had no route to creating an account at all -- the app was unusable on first run.
        // isSetUp() is false only before an identity exists, so this cannot divert anyone who
        // already has one.
        if (!container.storage.appLock.isSetUp()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        if (!container.storage.appLock.isPasscodeEnabled()) {

            lifecycleScope.launch {
                if (container.storage.appLock.unlockWithoutPasscode() == UnlockResult.SUCCESS) {
                    container.lock.reset()
                    goHome()
                }
            }
        }
    }

    private fun onKey(key: String) {
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

    private fun submit() {
        val raw = entered.toString()
        if (raw.length < 6) {
            android.widget.Toast.makeText(this, getString(R.string.passcode_too_short), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val opened = UnlockFlow.attempt(container, raw)
            if (opened) {
                goHome()
            } else {
                entered.clear()
                render()
                val remainingMillis = UnlockFlow.throttleRemainingMillis(container)
                val message = if (remainingMillis > 0) {
                    getString(R.string.passcode_throttled, formatWait(remainingMillis))
                } else {
                    getString(R.string.passcode_incorrect)
                }
                android.widget.Toast.makeText(this@PasscodeActivity, message, android.widget.Toast.LENGTH_SHORT).show()
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
