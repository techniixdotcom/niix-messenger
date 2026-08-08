package app.niix.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

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
        installSystemImeGuard()
    }

    /** Wires a field so focusing it reveals this screen's keyboard and routes typing to it. */
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

    /**
     * Call from the hosting Activity's `onWindowFocusChanged(true)` (i.e. when the app comes
     * back to the foreground). This is a cheap first line of defense; [installSystemImeGuard]
     * below is what actually closes the bug in practice, since the system's IME restore can
     * happen slightly after window focus is regained (see its doc comment).
     */
    fun reassertOnWindowFocus() {
        activeField?.let { hideSystemIme(it) }
    }

    /**
     * Reacts to the system IME actually becoming visible -- rather than a fixed hide() called at
     * a guessed moment -- and force-hides it whenever one of this app's own fields has focus.
     *
     * `showSoftInputOnFocus = false` and the window-focus hide above only intercept specific
     * paths the system can use to summon its keyboard. Returning to the app after minimizing it
     * goes through a different path: the system restores "the IME was visible when this window
     * lost focus" on its own, sometimes with a short delay after window focus actually returns,
     * which raced ahead of [reassertOnWindowFocus] and is why both keyboards still showed up.
     * Listening for the real IME-visibility change and hiding it every time catches that path
     * (and any other), regardless of timing.
     *
     * Registered on the whole window's root view (not just [panel]) and keyed off the shared
     * [globalActiveField] rather than this instance's own field, so multiple controllers in the
     * same screen (e.g. a rename dialog's field alongside the main input) don't fight over the
     * one listener a view can hold -- whichever one (re-)installs it, the behavior is identical.
     */
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
        /** The field (if any, across every [NiixKeyboardController] in the current screen) that
         * this app's own keyboard is currently driving -- see [installSystemImeGuard]. */
        private var globalActiveField: EditText? = null
    }
}
