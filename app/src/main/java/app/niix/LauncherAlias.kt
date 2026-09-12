package app.niix

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Chooses which launcher entry the home screen shows.
 *
 * Android provides no way to change an app's icon at runtime, so the supported approach is to
 * declare one activity-alias per icon and enable exactly one. Enabling two would put two entries
 * on the home screen; enabling none would remove the app from the launcher entirely and leave it
 * unopenable, so the order below matters -- the new alias is enabled before the others are
 * disabled.
 */
object LauncherAlias {

    private const val DISGUISED = "app.niix.LauncherDisguised"
    private const val REAL_DARK = "app.niix.LauncherReal"
    private const val REAL_LIGHT = "app.niix.LauncherRealLight"

    /** Icon choice for the undisguised app. */
    enum class Icon { DARK, LIGHT }

    fun apply(context: Context, disguised: Boolean, icon: Icon = Icon.DARK) {
        val wanted = when {
            disguised -> DISGUISED
            icon == Icon.LIGHT -> REAL_LIGHT
            else -> REAL_DARK
        }
        select(context, wanted)
    }

    private fun select(context: Context, wanted: String) {
        val pm = context.packageManager
        // Enable first, then disable the rest. Doing it the other way round leaves a moment with
        // no launcher entry at all, and if the process is killed in between -- which changing
        // component state can itself provoke -- the app becomes unreachable from the home screen.
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, wanted),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        listOf(DISGUISED, REAL_DARK, REAL_LIGHT)
            .filter { it != wanted }
            .forEach { alias ->
                pm.setComponentEnabledSetting(
                    ComponentName(context.packageName, alias),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
    }
}
