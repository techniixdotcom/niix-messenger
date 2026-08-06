package app.niix

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Enables the disguised or real launcher alias to match [disguised], and disables the other,
 * always enabling the new one before disabling the old one so there's never a moment where
 * neither is enabled. This is the single place that touches these components: Settings calls it
 * when the person toggles disguise, and any code path that wipes the account (Settings' manual
 * wipe, and a duress-triggered wipe in UnlockFlow) calls it too, since a wipe resets the
 * internal disguise flag back to its default and the visible launcher icon must follow it --
 * otherwise Settings could show "disguise on" while the home screen still shows the real icon.
 */
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
