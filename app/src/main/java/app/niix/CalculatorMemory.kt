package app.niix

import android.content.Context

/**
 * Backing store for the calculator's ordinary Memory feature (MC/MR/M+/MS) -- the same kind of
 * persistent memory a real calculator has. Deliberately plain SharedPreferences, not the
 * encrypted database: memory has to be readable before any passcode is entered, exactly like a
 * real calculator's memory works before you've "unlocked" anything.
 *
 * If the person opts in (Settings > duress passcode), their duress code can also live here --
 * indistinguishable from an ordinary remembered number to anyone who doesn't already know what
 * to look for, rather than a labeled secret.
 */
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
