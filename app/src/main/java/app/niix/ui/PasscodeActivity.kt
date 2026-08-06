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

/**
 * The real, un-disguised entry point: shown instead of the calculator when the person has
 * turned the disguise off in Settings while keeping a passcode. Unlike the calculator, there is
 * no need for ambiguity here -- the app's identity is already visible from its icon and name --
 * so a wrong code can just say so, rather than silently pretending to be arithmetic.
 */
class PasscodeActivity : AppCompatActivity() {

    private val container: AppContainer get() = (application as NiixApp).container
    private val entered = StringBuilder()
    private lateinit var display: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_passcode)
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
        container.storage.appLock.lock()
        TempFileGuard.purge(this)
        entered.clear()
        render()

        if (!container.storage.appLock.isPasscodeEnabled()) {
            // Passcode protection is off: there is nothing to gate here, open straight through.
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
                android.widget.Toast.makeText(this@PasscodeActivity, getString(R.string.passcode_incorrect), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
