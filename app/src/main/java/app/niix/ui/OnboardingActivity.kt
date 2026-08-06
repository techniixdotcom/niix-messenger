package app.niix.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.niix.AppContainer
import app.niix.NiixApp
import app.niix.R
import app.niix.core.storage.SettingsStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OnboardingActivity : AppCompatActivity() {

    private val container: AppContainer get() = (application as NiixApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (container.storage.appLock.isSetUp()) {
            val destination = if (container.storage.appLock.isDisguiseEnabled()) {
                CalculatorActivity::class.java
            } else {
                PasscodeActivity::class.java
            }
            startActivity(Intent(this, destination))
            finish()
            return
        }
        setContentView(R.layout.activity_onboarding)
        findViewById<MaterialButton>(R.id.onboard_create).setOnClickListener { submit() }
    }

    private fun submit() {
        val username = findViewById<TextInputEditText>(R.id.onboard_username).text?.toString()?.trim().orEmpty()
        val passcode = findViewById<TextInputEditText>(R.id.onboard_passcode).text?.toString().orEmpty()
        val confirm = findViewById<TextInputEditText>(R.id.onboard_confirm).text?.toString().orEmpty()
        val duress = findViewById<TextInputEditText>(R.id.onboard_duress).text?.toString().orEmpty()

        if (passcode.length < MIN_PASSCODE) {
            toast(getString(R.string.lock_too_short, MIN_PASSCODE)); return
        }
        if (passcode != confirm) {
            toast(getString(R.string.lock_mismatch)); return
        }
        if (duress.isNotEmpty() && (duress.length < MIN_PASSCODE || duress == passcode)) {
            toast(getString(R.string.duress_invalid)); return
        }

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.Default) {
                val pass = passcode.toCharArray()
                try {
                    val created = container.storage.appLock.setPasscode(pass)
                    if (created) {
                        container.storage.settings.setString(
                            SettingsStore.KEY_USERNAME,
                            username.ifEmpty { getString(R.string.default_username) },
                        )
                        if (duress.isNotEmpty()) {
                            val d = duress.toCharArray()
                            try { container.storage.appLock.setDuressPasscode(d) } finally { d.fill('\u0000') }
                        }
                    }
                    created
                } finally {
                    pass.fill('\u0000')
                }
            }
            if (ok) {
                startActivity(Intent(this@OnboardingActivity, HomeActivity::class.java))
                finish()
            } else {
                toast(getString(R.string.onboarding_failed))
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val MIN_PASSCODE = 6
    }
}
