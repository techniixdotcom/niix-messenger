package app.niix

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object LauncherAlias {

    fun apply(context: Context, disguised: Boolean) {
        val pm = context.packageManager
        val disguisedAlias = ComponentName(context.packageName, "app.niix.LauncherDisguised")
        val realAlias = ComponentName(context.packageName, "app.niix.LauncherReal")
        if (disguised) {
            pm.setComponentEnabledSetting(disguisedAlias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(realAlias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        } else {
            pm.setComponentEnabledSetting(realAlias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(disguisedAlias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
