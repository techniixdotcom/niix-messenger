package app.niix.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import app.niix.R

/**
 * The in-app keyboard. It exists so that what people type in NiiX never passes through a
 * third-party keyboard that could log it.
 *
 * Each language that can be typed without an input method editor has its own letter rows, and a
 * globe key switches to plain Latin for share codes and relay addresses. Chinese, Japanese, Korean
 * and Thai need composition or candidate lists that a simple key grid can't do, so those default to
 * the phone's keyboard instead (see NiixKeyboardController).
 */
class NiixKeyboard(
    private val context: Context,
    private val container: ViewGroup,
    private val onKey: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onDone: () -> Unit,
) {
    private var shift = false
    private var symbols = false
    private val native: Layout = layoutFor(context.resources.configuration.locales[0].language)
    private var layout: Layout = native

    fun render() {
        container.removeAllViews()
        // Digits first, in both layouts, so typing a number doesn't mean switching away.
        container.addView(buildRow(DIGITS, isLastRow = false, rtl = false))
        val rows = if (symbols) SYMBOL_ROWS else layout.rows
        rows.forEachIndexed { index, row ->
            container.addView(buildRow(row, isLastRow = index == rows.size - 1, rtl = layout.rtl && !symbols))
        }
        container.addView(buildBottomRow())
    }

    private fun buildRow(row: String, isLastRow: Boolean, rtl: Boolean): LinearLayout {
        val line = row(rtl)
        val cased = !symbols && layout.cased
        if (cased && isLastRow) {
            line.addView(specialKey(if (shift) "⇧•" else "⇧", 1.5f) { shift = !shift; render() })
        }
        for (ch in row) {
            val label = if (cased && shift) ch.uppercaseChar().toString() else ch.toString()
            // Zero-width non-joiner, which Persian needs between parts of a word. It has no glyph of
            // its own, so the key shows a marker.
            if (ch == ZWNJ) line.addView(charKey("‹›", 1f) { onKey(ZWNJ.toString()) })
            else line.addView(charKey(label, 1f) { onKey(label) })
        }
        if (isLastRow && !symbols) {
            line.addView(specialKey("⌫", 1.5f) { onBackspace() })
        }
        return line
    }

    private fun buildBottomRow(): LinearLayout {
        val line = row(rtl = false)
        line.addView(specialKey(if (symbols) layout.label else "#+=", 1.5f) { symbols = !symbols; shift = false; render() })
        if (native != LATIN && !symbols) {
            // Back and forth between the language's letters and Latin.
            val other = if (layout == native) LATIN else native
            line.addView(specialKey(other.label, 1.5f) { layout = other; shift = false; render() })
        }
        if (symbols) line.addView(specialKey("⌫", 1.2f) { onBackspace() })
        line.addView(charKey(context.getString(R.string.keyboard_space), 4f) { onKey(" ") })
        line.addView(specialKey("⏎", 1.5f) { onDone() })
        return line
    }

    private fun row(rtl: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        gravity = Gravity.CENTER
        // Fixed per row, not taken from the app's language: a Latin row stays left to right even
        // when the app is in Arabic, and an Arabic row reads right to left everywhere.
        layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    }

    private fun charKey(label: String, weight: Float, action: () -> Unit): Button =
        button(label, weight, accent = false, action)

    private fun specialKey(label: String, weight: Float, action: () -> Unit): Button =
        button(label, weight, accent = true, action)

    private fun button(label: String, weight: Float, accent: Boolean, action: () -> Unit): Button {
        return Button(context).apply {
            text = label
            isAllCaps = false
            // Large text: people use this keyboard for their most sensitive messages, so accuracy
            // matters more than fitting in.
            textSize = if (label.length > 2) 16f else 24f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setTextColor(context.themeColor(if (accent) R.attr.niixPrimary else R.attr.niixOnSurface))
            setBackgroundResource(R.drawable.keyboard_key_bg)
            layoutParams = LinearLayout.LayoutParams(0, dp(46)).also {
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

    private data class Layout(val label: String, val rows: List<String>, val cased: Boolean = true, val rtl: Boolean = false)

    companion object {
        private const val DIGITS = "1234567890"
        private const val ZWNJ = '‌'
        private val SYMBOL_ROWS = listOf("-/:;()$&@\"", ".,?!'#%*+", "[]{}<>^~\\|")

        private val LATIN = Layout("ABC", listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"))

        // The last row of each gets the shift and backspace keys, so it's kept short.
        private val LAYOUTS = mapOf(
            "de" to Layout("ABC", listOf("qwertzuiopü", "asdfghjklöä", "yxcvbnmß")),
            "fr" to Layout("ABC", listOf("éèêàçùëïô", "azertyuiop", "qsdfghjklm", "wxcvbn")),
            "es" to Layout("ABC", listOf("áéíóúü¿¡", "qwertyuiop", "asdfghjklñ", "zxcvbnm")),
            "it" to Layout("ABC", listOf("àèéìòù", "qwertyuiop", "asdfghjkl", "zxcvbnm")),
            "ru" to Layout("АБВ", listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбюё")),
            "ar" to Layout("أبت", listOf("ضصثقفغعهخحجد", "شسيبلاتنمكط", "أإآ،؟", "ذئءؤرىةوزظ"), cased = false, rtl = true),
            "fa" to Layout("الف", listOf("ضصثقفغعهخحجچ", "شسیبلاتنمکگ", "آئءأ،؟‌", "ظطزرذدپوژ"), cased = false, rtl = true),
        )

        private fun layoutFor(language: String): Layout = LAYOUTS[language] ?: LATIN

        /**
         * Whether the in-app keyboard can type [language] at all. For the rest the phone's
         * keyboard is the default, or people couldn't write in their own language.
         */
        fun supports(language: String): Boolean = language == "en" || language in LAYOUTS
    }
}
