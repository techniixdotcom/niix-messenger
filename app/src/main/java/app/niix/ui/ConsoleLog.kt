package app.niix.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import app.niix.R

/**
 * Terminal-style progress for the slow steps: key derivation, opening the database, generating
 * keys. Each takes a second or more on a phone, and without this the screen looks frozen.
 *
 * Main thread only. A step is shown as running until [ok] or [fail] closes it.
 */
class ConsoleLog(private val view: TextView) {

    private class Line(val text: String, var status: String?, var failed: Boolean)

    private val lines = ArrayList<Line>()
    private val ok = view.context.themeColor(R.attr.niixPrimary)
    private val bad = view.context.themeColor(R.attr.niixDanger)

    fun step(text: String) {
        lines += Line(text, null, false)
        render()
    }

    fun ok() = close(view.context.getString(R.string.console_ok), failed = false)

    fun fail(reason: String) = close(reason, failed = true)

    fun clear() {
        lines.clear()
        view.text = ""
        view.visibility = View.GONE
    }

    private fun close(status: String, failed: Boolean) {
        lines.lastOrNull()?.takeIf { it.status == null }?.let {
            it.status = status
            it.failed = failed
        }
        render()
    }

    private fun render() {
        val out = SpannableStringBuilder()
        for (line in lines) {
            if (out.isNotEmpty()) out.append('\n')
            out.append("> ").append(line.text)
            val status = line.status
            if (status == null) {
                out.append(" …")
            } else {
                val start = out.length + 1
                out.append(' ').append(status)
                out.setSpan(ForegroundColorSpan(if (line.failed) bad else ok), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (lines.lastOrNull()?.status == null) out.append("\n_")
        view.text = out
        view.visibility = View.VISIBLE
    }
}
