package app.niix.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.niix.AppContainer
import app.niix.NiixApp
import app.niix.R
import app.niix.core.storage.AppLockManager
import app.niix.core.storage.SettingsStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OnboardingActivity : AppCompatActivity() {

    private val container: AppContainer get() = (application as NiixApp).container
    private lateinit var console: ConsoleLog
    private lateinit var create: MaterialButton
    private lateinit var link: MaterialButton
    private lateinit var fields: List<TextInputEditText>

    override fun onCreate(savedInstanceState: Bundle?) {
        useNiixTheme()
        super.onCreate(savedInstanceState)
        if (container.storage.appLock.isSetUp()) {
            startActivity(Intent(this, PasscodeActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_onboarding)
        console = ConsoleLog(findViewById(R.id.onboard_console))
        create = findViewById(R.id.onboard_create)
        fields = listOf(R.id.onboard_username, R.id.onboard_passcode, R.id.onboard_confirm, R.id.onboard_duress).map { findViewById(it) }
        link = findViewById(R.id.onboard_link)
        create.setOnClickListener { submit(linking = false) }
        // Linking replaces this device's identity with the account's, so there's no name to pick:
        // it comes from the account. The passcode is still this device's own.
        link.setOnClickListener { submit(linking = true) }
    }

    override fun onResume() {
        super.onResume()
        applyScreenshotPolicy()
    }

    private fun submit(linking: Boolean) {
        val (usernameField, passcodeField, confirmField, duressField) = fields
        val username = usernameField.text?.toString()?.trim().orEmpty()
        val passcode = passcodeField.text?.toString().orEmpty()
        val confirm = confirmField.text?.toString().orEmpty()
        val duress = duressField.text?.toString().orEmpty()

        if (passcode.isNotEmpty() && passcode.length < AppLockManager.MIN_PASSCODE_LENGTH) {
            toast(getString(R.string.lock_too_short, AppLockManager.MIN_PASSCODE_LENGTH)); return
        }
        if (passcode != confirm) {
            toast(getString(R.string.lock_mismatch)); return
        }
        if (duress.isNotEmpty() && passcode.isEmpty()) {
            toast(getString(R.string.duress_needs_passcode)); return
        }
        if (duress.isNotEmpty() && (duress.length < AppLockManager.MIN_PASSCODE_LENGTH || duress == passcode)) {
            toast(getString(R.string.duress_invalid)); return
        }

        // Setup takes a few seconds and can't be run twice: the second attempt would find a
        // half-made account and fail. So everything is disabled until it's done.
        setBusy(true)
        console.clear()
        lifecycleScope.launch {
            if (createAccount(username, passcode, duress, linking)) {
                val next = if (linking) LinkDeviceActivity::class.java else HomeActivity::class.java
                startActivity(Intent(this@OnboardingActivity, next))
                finish()
            } else {
                toast(getString(R.string.onboarding_failed))
                setBusy(false)
            }
        }
    }

    private suspend fun createAccount(username: String, passcode: String, duress: String, linking: Boolean): Boolean {
        val appLock = container.storage.appLock
        val created = step(if (passcode.isEmpty()) R.string.step_setup_device else R.string.step_setup_passcode) {
            if (passcode.isEmpty()) {
                appLock.setUpWithoutPasscode()
            } else {
                val pass = passcode.toCharArray()
                try { appLock.setPasscode(pass) } finally { pass.fill('\u0000') }
            }
        }
        if (!created) return false

        if (linking) {
            container.storage.settings.setBool(SettingsStore.KEY_LINK_PENDING, true)
        } else {
            step(R.string.step_profile) {
                container.storage.settings.setString(
                    SettingsStore.KEY_USERNAME,
                    username.ifEmpty { getString(R.string.default_username) },
                )
                true
            }
        }
        if (duress.isNotEmpty()) {
            val stored = step(R.string.step_duress) {
                val d = duress.toCharArray()
                try { appLock.setDuressPasscode(d) } finally { d.fill('\u0000') }
            }
            if (!stored) return false
        }
        // Would otherwise happen when the network starts. Done here so the wait is all in one
        // place, with something on screen explaining it. Not fatal if it fails: the network start
        // tries again. A device being linked gets the account's keys instead.
        if (!linking) step(R.string.step_keys) { container.crypto.ensureKeysInitialized(); true }
        console.step(getString(R.string.step_ready))
        console.ok()
        return true
    }

    /** Runs [work] off the main thread as one console line. False or an exception fails it. */
    private suspend fun step(label: Int, work: () -> Boolean): Boolean {
        console.step(getString(label))
        val done = withContext(Dispatchers.Default) { runCatching(work).getOrDefault(false) }
        if (done) console.ok() else console.fail(getString(R.string.console_failed))
        return done
    }

    private fun setBusy(busy: Boolean) {
        create.isEnabled = !busy
        link.isEnabled = !busy
        create.setText(if (busy) R.string.onboarding_creating else R.string.onboarding_create)
        fields.forEach { it.isEnabled = !busy }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
