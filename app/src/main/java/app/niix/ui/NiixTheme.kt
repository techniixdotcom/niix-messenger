package app.niix.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import app.niix.R

/**
 * The app's themes. Each one changes every colour in the app, through the attributes in
 * attrs.xml, and the app icon, through a launcher entry of its own in the manifest.
 *
 * The choice is kept outside the encrypted database because the lock screen needs it before
 * anything is unlocked, and it survives a wipe: a duress wipe that turned the app pink again
 * would show that something had happened.
 */
enum class NiixTheme(
    private val key: String,
    @StyleRes val style: Int,
    @StringRes val label: Int,
    private val launcher: String,
    val isLight: Boolean = false,
) {
    // Pink keeps the old launcher name on purpose. Rename it and everyone's home screen icon from
    // 1.0.1 goes poof. Don't be that guy.
    PINK("pink", R.style.Theme_Niix_Pink, R.string.theme_pink, "app.niix.LauncherReal"),
    GREEN("green", R.style.Theme_Niix_Green, R.string.theme_green, "app.niix.LauncherGreen"),
    ORANGE("orange", R.style.Theme_Niix_Orange, R.string.theme_orange, "app.niix.LauncherOrange"),
    ROYAL_BLUE("royal_blue", R.style.Theme_Niix_RoyalBlue, R.string.theme_royal_blue, "app.niix.LauncherRoyalBlue"),
    LIGHT("light", R.style.Theme_Niix_Light, R.string.theme_light, "app.niix.LauncherLight", isLight = true),
    DARK("dark", R.style.Theme_Niix_Dark, R.string.theme_dark, "app.niix.LauncherDark");

    /** One of the theme's colours, whatever theme [context] has, for previews and notifications. */
    @ColorInt
    fun color(context: Context, @AttrRes attr: Int): Int = ContextThemeWrapper(context, style).themeColor(attr)

    /** The theme's main colour. */
    @ColorInt
    fun accent(context: Context): Int = color(context, R.attr.niixPrimary)

    companion object {
        private const val PREFS = "niix_theme"
        private const val KEY_THEME = "theme"

        fun current(context: Context): NiixTheme {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, null)
            return entries.firstOrNull { it.key == saved } ?: PINK
        }

        /** Saves [theme] and switches the icon to it. Open screens follow when they're next shown. */
        fun select(context: Context, theme: NiixTheme) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_THEME, theme.key).apply()
            applyToProcess(context)
        }

        /**
         * Puts day or night mode and the launcher icon in line with the saved theme. Called when
         * the app starts, which also repairs an icon left switched off by an older version's
         * calculator disguise.
         */
        fun applyToProcess(context: Context) {
            val theme = current(context)
            AppCompatDelegate.setDefaultNightMode(
                if (theme.isLight) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES,
            )
            showIcon(context, theme)
        }

        /**
         * Exactly one launcher entry is switched on: the new one first, so there's never a moment
         * with no icon at all (launchers freak the hell out about that). Only entries in the wrong
         * state get touched, since every change makes the launcher redraw.
         */
        private fun showIcon(context: Context, theme: NiixTheme) {
            val pm = context.packageManager
            fun set(entry: NiixTheme, on: Boolean) {
                val component = ComponentName(context.packageName, entry.launcher)
                val wanted = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                if (pm.getComponentEnabledSetting(component) != wanted) {
                    runCatching { pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP) }
                }
            }
            set(theme, true)
            entries.filter { it != theme }.forEach { set(it, false) }
        }
    }
}

/** Applies the saved theme. Call before super.onCreate, so every view is made with it. */
fun AppCompatActivity.useNiixTheme(): NiixTheme = NiixTheme.current(this).also { setTheme(it.style) }

/** A colour from the current theme, by its attribute in attrs.xml. */
@ColorInt
fun Context.themeColor(@AttrRes attr: Int): Int {
    val value = TypedValue()
    check(theme.resolveAttribute(attr, value, true)) { "the theme has no colour for ${resources.getResourceEntryName(attr)}" }
    return value.data
}
