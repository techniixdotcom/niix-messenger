package app.niix.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.niix.NiixApp
import app.niix.core.storage.SettingsStore

/**
 * Puts the in-app keyboard under text fields, or leaves them to the phone's keyboard if the user
 * chose that. See [usesPhoneKeyboard].
 */
class NiixKeyboardController(context: Context, private val panel: ViewGroup) {

    private val phoneKeyboard = usesPhoneKeyboard(context)
    private var activeField: EditText? = null

    private val keyboard = NiixKeyboard(
        context = context,
        container = panel,
        onKey = { key -> activeField?.let { insertAtSelection(it, key) } },
        onBackspace = { activeField?.let { deleteAtSelection(it) } },
        onDone = { activeField?.clearFocus(); hide() },
    )

    init {
        panel.visibility = View.GONE
        if (!phoneKeyboard) {
            keyboard.render()
            installSystemImeGuard()
        }
    }

    fun attach(field: EditText) {
        if (phoneKeyboard) {
            field.showSoftInputOnFocus = true
            // Asks the keyboard not to learn from or remember what's typed here. Keyboards are
            // free to ignore it, which is why the in-app keyboard is the default where it can be.
            field.imeOptions = field.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            return
        }
        field.showSoftInputOnFocus = false
        field.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activeField = field
                globalActiveField = field
                show()
            } else if (activeField === field) {
                activeField = null
                if (globalActiveField === field) globalActiveField = null
                hide()
            }
        }
    }

    private fun show() {
        panel.visibility = View.VISIBLE
    }

    private fun hide() {
        panel.visibility = View.GONE
    }

    fun reassertOnWindowFocus() {
        if (!phoneKeyboard) activeField?.let { hideSystemIme(it) }
    }

    private fun installSystemImeGuard() {
        val root = panel.rootView
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val field = globalActiveField
            if (field != null && insets.isVisible(WindowInsetsCompat.Type.ime())) {
                hideSystemIme(field)
            }
            insets
        }
    }

    private fun hideSystemIme(field: EditText) {
        val imm = field.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(field.windowToken, 0)
    }

    private fun insertAtSelection(field: EditText, key: String) {
        val length = field.text.length
        val start = field.selectionStart.coerceIn(0, length)
        val end = field.selectionEnd.coerceIn(0, length)
        field.text.replace(minOf(start, end), maxOf(start, end), key)
    }

    private fun deleteAtSelection(field: EditText) {
        val length = field.text.length
        val start = field.selectionStart.coerceIn(0, length)
        val end = field.selectionEnd.coerceIn(0, length)
        if (start != end) {
            field.text.delete(minOf(start, end), maxOf(start, end))
        } else if (start > 0) {
            // A whole character, not half of one: emoji and some scripts take two UTF-16 units.
            field.text.delete(Character.offsetByCodePoints(field.text, start, -1), start)
        }
    }

    companion object {

        private var globalActiveField: EditText? = null

        /**
         * The user's choice if they made one. Otherwise the in-app keyboard wherever it can type
         * the app's language, and the phone's keyboard where it can't (Chinese, Japanese, Korean,
         * Thai).
         */
        fun usesPhoneKeyboard(context: Context): Boolean {
            val default = !NiixKeyboard.supports(context.resources.configuration.locales[0].language)
            return runCatching {
                (context.applicationContext as NiixApp).container.storage.settings.getBool(SettingsStore.KEY_PHONE_KEYBOARD, default)
            }.getOrDefault(default)
        }
    }
}
