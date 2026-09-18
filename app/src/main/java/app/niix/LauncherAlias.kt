package app.niix

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object LauncherAlias {

    private const val DISGUISED = "app.niix.LauncherDisguised"
    private const val REAL = "app.niix.LauncherReal"

    fun apply(context: Context, disguised: Boolean) {
        select(context, if (disguised) DISGUISED else REAL)
    }

    private fun select(context: Context, wanted: String) {
        val pm = context.packageManager
 // Enable first, then disable the rest. Doing it the other way round leaves a moment with
 // no launcher entry at all, and if the process is killed in between, which changing
 // component state can itself provoke, the app becomes unreachable from the home screen.
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, wanted),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        listOf(DISGUISED, REAL)
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
