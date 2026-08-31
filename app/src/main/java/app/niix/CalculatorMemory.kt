package app.niix

import android.content.Context

object CalculatorMemory {

    private const val PREFS_NAME = "calc_state"
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
