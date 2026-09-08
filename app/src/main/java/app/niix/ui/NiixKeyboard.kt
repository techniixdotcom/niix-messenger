package app.niix.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import app.niix.R

class NiixKeyboard(
    private val context: Context,
    private val container: ViewGroup,
    private val onKey: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onDone: () -> Unit,
) {
    private var shift = false
    private var symbols = false

    private val lettersRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val symbolRows = listOf("1234567890", "-/:;()$&@\"", ".,?!'#%*+")

    fun render() {
        container.removeAllViews()
        val rows = if (symbols) symbolRows else lettersRows
        rows.forEachIndexed { index, row -> container.addView(buildRow(row, index, rows.size)) }
        container.addView(buildBottomRow())
    }

    private fun buildRow(row: String, index: Int, totalRows: Int): LinearLayout {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
        }
        val isLastRow = index == totalRows - 1
        if (!symbols && isLastRow) {
            line.addView(specialKey(if (shift) "\u21e7\u2022" else "\u21e7", 1.5f) { shift = !shift; render() })
        }
        for (ch in row) {
            val label = if (!symbols && shift) ch.uppercaseChar().toString() else ch.toString()
            line.addView(charKey(label, 1f) { onKey(label) })
        }
        if (!symbols && isLastRow) {
            line.addView(specialKey("\u232b", 1.5f) { onBackspace() })
        }
        return line
    }

    private fun buildBottomRow(): LinearLayout {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
        }
        line.addView(specialKey(if (symbols) "ABC" else "123", 1.5f) { symbols = !symbols; shift = false; render() })
        if (symbols) {
            line.addView(specialKey("\u232b", 1.2f) { onBackspace() })
        }
        line.addView(charKey(context.getString(R.string.keyboard_space), 4f) { onKey(" ") })
        line.addView(specialKey("\u23ce", 1.5f) { onDone() })
        return line
    }

    private fun charKey(label: String, weight: Float, action: () -> Unit): Button =
        button(label, weight, pink = false, action)

    private fun specialKey(label: String, weight: Float, action: () -> Unit): Button =
        button(label, weight, pink = true, action)

    private fun button(label: String, weight: Float, pink: Boolean, action: () -> Unit): Button {
        return Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 23f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setTextColor(context.getColor(if (pink) R.color.niix_pink else R.color.niix_on_surface))
            setBackgroundResource(R.drawable.keyboard_key_bg)
            layoutParams = LinearLayout.LayoutParams(0, dp(48)).also {
                it.weight = weight
                it.setMargins(dp(2), dp(3), dp(2), dp(3))
            }
            setOnClickListener { action() }
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics,
    ).toInt()
}
