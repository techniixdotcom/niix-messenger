package app.niix

import android.content.Context

object CalculatorMemory {

    private const val PREFS_NAME = "calc_state"

    /**
     * Clears the stored calculator state.
     *
     * Called during a wipe. The calculator deliberately keeps working afterwards -- one that
     * forgot everything the instant someone unlocked it would be conspicuous -- but it must not
     * still be holding whatever was entered before the wipe, which is real user data and could
     * itself be revealing.
     */
    fun reset(context: Context) {
        prefs(context).edit().clear().commit()
    }
    private const val KEY_MEMORY = "m"

    fun store(context: Context, value: String) {
        prefs(context).edit().putString(KEY_MEMORY, value).apply()
    }

    fun recall(context: Context): String? = prefs(context).getString(KEY_MEMORY, null)

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_MEMORY).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
