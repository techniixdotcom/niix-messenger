package app.niix.ui

import android.content.Context
import android.text.InputType
import android.widget.EditText
import androidx.core.content.ContextCompat
import app.niix.R

/**
 * Builds a real, native [EditText] -- so tapping it gives a genuine text cursor, drag-to-select,
 * word/double-tap selection, and Android's own Copy/Select All/Paste floating toolbar all work
 * exactly as they do everywhere else on the phone -- but with [EditText.setShowSoftInputOnFocus]
 * turned off, so focusing it never summons any system or third-party keyboard. Key input comes
 * only from [NiixKeyboardController], which this field is meant to be attached to.
 */
object NiixEditField {

    fun create(context: Context, hint: String, multiline: Boolean = false): EditText {
        return EditText(context).apply {
            this.hint = hint
            setBackgroundResource(R.drawable.input_bg)
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.niix_on_surface))
            setHintTextColor(ContextCompat.getColor(context, R.color.niix_on_surface_muted))
            inputType = if (multiline) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            maxLines = if (multiline) 5 else 1
            isSingleLine = !multiline
            showSoftInputOnFocus = false
            isFocusableInTouchMode = true
            isLongClickable = true
        }
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
