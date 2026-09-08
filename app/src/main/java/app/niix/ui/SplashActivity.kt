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

        val green = ContextCompat.getColor(this, R.color.niix_green)
        val pink = ContextCompat.getColor(this, R.color.niix_pink)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }

        root.addView(asciiView(NIIX_ART, green))
        root.addView(asciiView(MESSENGER_ART, pink))
        setContentView(root)

        // Tapping skips the wait -- a splash that cannot be dismissed is just an obstacle.
        root.setOnClickListener { goHome() }
        handler.postDelayed(proceed, SPLASH_MILLIS)
    }

    private fun asciiView(art: String, colour: Int): TextView = TextView(this).apply {
        text = art
        setTextColor(colour)
        typeface = android.graphics.Typeface.MONOSPACE
        // Sized in scaled pixels so the block stays proportionate on any density, and kept small
        // enough that the widest line fits a narrow phone without wrapping -- wrapped ASCII art
        // is unreadable.
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 7f)
        includeFontPadding = false
        setLineSpacing(0f, 0.95f)
        gravity = Gravity.CENTER_HORIZONTAL
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

        val NIIX_ART = """
            ███╗   ██╗██╗██╗██╗  ██╗
            ████╗  ██║██║██║╚██╗██╔╝
            ██╔██╗ ██║██║██║ ╚███╔╝ 
            ██║╚██╗██║██║██║ ██╔██╗ 
            ██║ ╚████║██║██║██╔╝ ██╗
            ╚═╝  ╚═══╝╚═╝╚═╝╚═╝  ╚═╝
        """.trimIndent()

        val MESSENGER_ART = """
            ███╗   ███╗███████╗███████╗███████╗███████╗███╗   ██╗ ██████╗ ███████╗██████╗ 
            ████╗ ████║██╔════╝██╔════╝██╔════╝██╔════╝████╗  ██║██╔════╝ ██╔════╝██╔══██╗
            ██╔████╔██║█████╗  ███████╗███████╗█████╗  ██╔██╗ ██║██║  ███╗█████╗  ██████╔╝
            ██║╚██╔╝██║██╔══╝  ╚════██║╚════██║██╔══╝  ██║╚██╗██║██║   ██║██╔══╝  ██╔══██╗
            ██║ ╚═╝ ██║███████╗███████║███████║███████╗██║ ╚████║╚██████╔╝███████╗██║  ██║
            ╚═╝     ╚═╝╚══════╝╚══════╝╚══════╝╚══════╝╚═╝  ╚═══╝ ╚═════╝ ╚══════╝╚═╝  ╚═╝
        """.trimIndent()
    }
}
