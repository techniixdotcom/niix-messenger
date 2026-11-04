package app.niix.ui

import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.niix.QrCodes
import app.niix.R
import app.niix.core.crypto.SafetyNumber
import app.niix.core.messaging.ConversationManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/** Renaming a contact and checking their safety number, shared by the chat and the contact's profile. */
internal class ContactDialogs(
    private val activity: AppCompatActivity,
    private val conversations: ConversationManager,
) {

    private var renameKeyboard: NiixKeyboardController? = null

    /** For the activity's onWindowFocusChanged, which can take the rename sheet's keyboard away. */
    fun reassertKeyboard() {
        renameKeyboard?.reassertOnWindowFocus()
    }

    fun rename(onion: String, current: String, onRenamed: (String) -> Unit) {
        val sheet = BottomSheetDialog(activity)
        val field = NiixEditField.create(activity, activity.getString(R.string.hint_contact_name)).apply {
            setText(current)
            setSelection(text.length)
        }
        val keyboardPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.niix_surface))
            setPadding(dp(4), dp(6), dp(4), dp(10))
        }
        val saveButton = MaterialButton(activity).apply { text = activity.getString(R.string.dialog_save) }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.niix_surface))
            setPadding(dp(20), dp(20), dp(20), dp(10))
            addView(
                TextView(activity).apply {
                    text = activity.getString(R.string.menu_rename)
                    setTextColor(color(R.color.niix_on_surface))
                    textSize = 17f
                    setPadding(0, 0, 0, dp(14))
                },
            )
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                saveButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                    it.topMargin = dp(14)
                },
            )
            addView(keyboardPanel)
        }
        renameKeyboard = NiixKeyboardController(activity, keyboardPanel).apply { attach(field) }

        saveButton.setOnClickListener {
            val newName = field.text.toString().trim()
            if (newName.isNotEmpty()) {
                activity.lifecycleScope.launch {
                    runCatching { conversations.renameContact(onion, newName) }
                    onRenamed(newName)
                }
            }
            sheet.dismiss()
        }
        sheet.setContentView(content)
        sheet.setOnDismissListener { renameKeyboard = null }
        sheet.show()
        field.requestFocus()
    }

    /** The safety number as digits and a QR code, with the option to mark the contact verified. */
    fun showSafetyNumber(onion: String, onVerified: () -> Unit) {
        activity.lifecycleScope.launch {
            val number = runCatching { conversations.safetyNumber(onion) }.getOrNull()
            if (number == null) {
                toast(R.string.toast_no_session)
                return@launch
            }
            val digits = TextView(activity).apply {
                setPadding(48, 32, 48, 16)
                textSize = 16f
                setTextIsSelectable(true)
                text = SafetyNumber.formatted(number)
            }
            val code = ImageView(activity).apply { QrCodes.encode(number, 480)?.let { setImageBitmap(it) } }
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(32, 24, 32, 8)
                addView(digits)
                addView(code)
            }
            niixDialog(activity)
                .setTitle(R.string.action_safety_number)
                .setMessage(R.string.safety_number_explainer)
                .setView(column)
                .setPositiveButton(R.string.action_mark_verified) { _, _ -> markVerified(onion, onVerified) }
                .setNegativeButton(R.string.dialog_close, null)
                .show()
        }
    }

    private fun markVerified(onion: String, onVerified: () -> Unit) {
        activity.lifecycleScope.launch {
            val pinned = runCatching { conversations.markVerified(onion) }.getOrDefault(false)
            // Only say verified if the key was actually pinned.
            toast(if (pinned) R.string.toast_verified else R.string.toast_verify_no_identity)
            onVerified()
        }
    }

    private fun toast(message: Int) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    private fun color(id: Int) = activity.resources.getColor(id, activity.theme)

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
