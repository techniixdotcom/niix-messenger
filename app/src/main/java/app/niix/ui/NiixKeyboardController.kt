package app.niix.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class NiixKeyboardController(context: Context, private val panel: ViewGroup) {

    private var activeField: EditText? = null

    private val keyboard = NiixKeyboard(
        context = context,
        container = panel,
        onKey = { key -> activeField?.let { insertAtSelection(it, key) } },
        onBackspace = { activeField?.let { deleteAtSelection(it) } },
        onDone = { activeField?.clearFocus(); hide() },
    )

    init {
        keyboard.render()
        panel.visibility = View.GONE
        installSystemImeGuard()
    }

    fun attach(field: EditText) {
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
        activeField?.let { hideSystemIme(it) }
    }

    private fun installSystemImeGuard() {
        val root = panel.rootView
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
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
            field.text.delete(start - 1, start)
        }
    }

    companion object {

        private var globalActiveField: EditText? = null
    }
}
