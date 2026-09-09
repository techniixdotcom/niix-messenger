package app.niix.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.niix.R

/**
 * Brief ASCII splash shown after unlocking, before the conversation list.
 *
 * Rendered as text rather than an image so it scales to any display without assets, and so the
 * two colours can be applied to the two words independently.
 *
 * Screenshot protection applies here like everywhere else -- this screen names the app, which is
 * exactly what the calculator disguise exists to keep off the screen. It also finishes itself
 * rather than staying on the back stack, so returning from the conversation list does not land
 * back on a screen announcing what the app is.
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val proceed = Runnable { goHome() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenshotPolicy()

        // Skipped when the user reached here through the passcode screen, which already shows
        // the logo -- displaying it twice in a row is just a delay between the user and their
        // messages. It is worth showing after the calculator disguise, where nothing has
        // identified the app yet and the logo marks the transition out of the decoy.
        val disguised = runCatching {
            (application as app.niix.NiixApp).container.storage.appLock.isDisguiseEnabled()
        }.getOrDefault(false)
        if (!disguised) {
            goHome()
            return
        }

        val green = ContextCompat.getColor(this, R.color.niix_green)
        val pink = ContextCompat.getColor(this, R.color.niix_pink)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }

        // Sized from the actual screen width rather than a fixed value. A hardcoded size fits one
        // device and overflows another -- which is exactly what happened: readable on one phone,
        // wrapped into nonsense on a narrower one. Deriving it from the measured width means the
        // art fits by construction on any display.
        val usableWidthPx = resources.displayMetrics.widthPixels * 0.88f
        root.addView(asciiView(NIIX_ART, green, fitWidthSp(usableWidthPx, NIIX_COLUMNS)))
        root.addView(asciiView(MESSENGER_ART, pink, fitWidthSp(usableWidthPx * 0.92f, MESSENGER_COLUMNS)))
        setContentView(root)

        // Tapping skips the wait -- a splash that cannot be dismissed is just an obstacle.
        root.setOnClickListener { goHome() }
        handler.postDelayed(proceed, SPLASH_MILLIS)
    }

    /**
     * Largest text size, in sp, at which [columns] monospace characters still fit [widthPx].
     *
     * A monospace glyph is about 0.6 of the text size wide; the ratio is approximate, so the
     * result is deliberately conservative. Wrapped ASCII art is not slightly worse, it is
     * unreadable, so erring small costs nothing and erring large ruins it.
     */
    private fun fitWidthSp(widthPx: Float, columns: Int): Float {
        val density = resources.displayMetrics.scaledDensity
        val maxSp = widthPx / (columns * 0.6f) / density
        return maxSp.coerceIn(4f, 40f)
    }

    private fun asciiView(art: String, colour: Int, sizeSp: Float): TextView = TextView(this).apply {
        text = art
        setTextColor(colour)
        typeface = android.graphics.Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        includeFontPadding = false
        setLineSpacing(0f, 0.95f)
        gravity = Gravity.CENTER_HORIZONTAL
        // Never wrap: a line that wraps destroys the alignment the art depends on. Better to
        // clip than to scramble, though the sizing above is meant to make this unreachable.
        setHorizontallyScrolling(true)
    }

    private fun goHome() {
        handler.removeCallbacks(proceed)
        startActivity(Intent(this, HomeActivity::class.java))
        // Removed from the back stack so returning from the conversation list does not reveal
        // this screen again.
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        handler.removeCallbacks(proceed)
        super.onDestroy()
    }

    private companion object {
        const val SPLASH_MILLIS = 1400L

        /**
         * Block characters and spaces only.
         *
         * The previous art mixed U+2588 blocks with box-drawing characters, which come from a
         * different Unicode range -- fonts are free to render them at different widths, and on
         * some devices they did, which collapsed the alignment into noise. Restricting the art to
         * one character plus spaces removes that entirely: every glyph is the same width in any
         * monospace font.
         */
        val NIIX_ART = """
            █   █ █ █ █   █
            ██  █      █ █ 
            █ █ █ █ █   █  
            █  ██ █ █  █ █ 
            █   █ █ █ █   █
        """.trimIndent()

        val MESSENGER_ART = """
            █   █ █████  ████  ████ █████ █   █  ████ █████ ████ 
            ██ ██ █     █     █     █     ██  █ █     █     █   █
            █ █ █ ████   ███   ███  ████  █ █ █ █  ██ ████  ████ 
            █   █ █         █     █ █     █  ██ █   █ █     █  █ 
            █   █ █████ ████  ████  █████ █   █  ████ █████ █   █
        """.trimIndent()

        /** Widest row of each block, used to size the text so it fits the screen exactly. */
        const val NIIX_COLUMNS = 15
        const val MESSENGER_COLUMNS = 53
    }
}
