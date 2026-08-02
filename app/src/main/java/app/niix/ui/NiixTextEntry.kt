package app.niix.ui

import android.content.ClipboardManager
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.niix.R

/**
 * A drop-in replacement for EditText/TextInputEditText: a plain, non-editable field that reveals
 * this app's own [NiixKeyboard] inline when tapped. Nothing here ever requests focus in a way
 * that could summon a system or third-party input method -- typing anywhere in NiiX stays inside
 * the app.
 *
 * Optionally shows a paste button (for fields like a share code, which people realistically
 * copy from another device or a QR scan rather than type out by hand).
 */
class NiixTextEntry(
    context: Context,
    private val hint: String,
    private val multiline: Boolean = false,
    private val allowPaste: Boolean = false,
) : LinearLayout(context) {

    private val fieldView: TextView
    private val keyboardPanel: LinearLayout
    private val content = StringBuilder()
    private var keyboard: NiixKeyboard? = null

    var text: String
        get() = content.toString()
        set(value) {
            content.setLength(0)
            content.append(value)
            refresh()
        }

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fieldView = TextView(context).apply {
            setBackgroundResource(R.drawable.input_bg)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            textSize = 15f
            maxLines = if (multiline) 5 else 1
            isClickable = true
            isFocusable = true
            if (multiline) gravity = Gravity.TOP
        }
        row.addView(fieldView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (allowPaste) {
            val paste = ImageButton(context).apply {
                setImageResource(android.R.drawable.ic_menu_edit)
                background = null
                contentDescription = context.getString(R.string.action_paste)
                ContextCompat.getColorStateList(context, R.color.niix_pink)?.let { imageTintList = it }
                setOnClickListener { pasteFromClipboard() }
            }
            row.addView(paste, LayoutParams(dp(40), dp(40)).also { it.marginStart = dp(8) })
        }

        addView(row)

        keyboardPanel = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = View.GONE
            setBackgroundColor(ContextCompat.getColor(context, R.color.niix_surface))
            setPadding(dp(4), dp(6), dp(4), dp(10))
        }
        addView(keyboardPanel)

        keyboard = NiixKeyboard(
            context = context,
            container = keyboardPanel,
            onKey = { key -> content.append(key); refresh() },
            onBackspace = {
                if (content.isNotEmpty()) {
                    content.deleteCharAt(content.length - 1)
                    refresh()
                }
            },
            onDone = { keyboardPanel.visibility = View.GONE },
        )
        keyboard?.render()

        fieldView.setOnClickListener {
            keyboardPanel.visibility = if (keyboardPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        refresh()
    }

    /** Reads whatever is on the system clipboard and appends it -- the one deliberate exception
     * to "never leaves the app's own keyboard", since share codes are realistically copied from
     * a QR scan or another device, not typed by hand. */
    private fun pasteFromClipboard() {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = manager?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val pasted = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
            if (pasted.isNotEmpty()) {
                content.setLength(0)
                content.append(pasted)
                refresh()
            }
        }
    }

    private fun refresh() {
        if (content.isEmpty()) {
            fieldView.text = hint
            fieldView.setTextColor(ContextCompat.getColor(context, R.color.niix_on_surface_muted))
        } else {
            fieldView.text = content.toString()
            fieldView.setTextColor(ContextCompat.getColor(context, R.color.niix_on_surface))
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics,
    ).toInt()
}
