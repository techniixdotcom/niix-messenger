package app.niix.ui

import android.content.Context
import android.text.InputType
import android.widget.EditText
import app.niix.R

object NiixEditField {

    fun create(context: Context, hint: String, multiline: Boolean = false): EditText {
        return EditText(context).apply {
            this.hint = hint
            setBackgroundResource(R.drawable.input_bg)
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            textSize = 15f
            setTextColor(context.themeColor(R.attr.niixOnSurface))
            setHintTextColor(context.themeColor(R.attr.niixOnSurfaceMuted))
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
