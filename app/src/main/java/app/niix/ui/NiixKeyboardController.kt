package app.niix.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText

/**
 * Manages a single [NiixKeyboard] docked in [panel] (expected to sit at the very bottom of the
 * screen, like a real keyboard does) and shows/hides it based on native focus changes of
 * whichever [EditText] fields have been [attach]ed. Key presses are applied at the field's
 * current cursor position or selection -- exactly what a real keyboard's input connection would
 * do -- rather than always appending to the end.
 */
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
    }

    /** Wires a field so focusing it reveals this screen's keyboard and routes typing to it. */
    fun attach(field: EditText) {
        field.showSoftInputOnFocus = false
        field.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activeField = field
                show()
            } else if (activeField === field) {
                activeField = null
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
}
