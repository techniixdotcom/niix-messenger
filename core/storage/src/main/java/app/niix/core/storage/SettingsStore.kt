package app.niix.core.storage

import android.content.ContentValues
import net.zetetic.database.sqlcipher.SQLiteDatabase

class SettingsStore internal constructor(private val secureDatabase: SecureDatabase) {

    private val db: SQLiteDatabase get() = secureDatabase.open()
    private val t = Schema.Settings

    fun getString(key: String): String? {
        db.rawQuery("SELECT ${t.COL_VALUE} FROM ${t.TABLE} WHERE ${t.COL_KEY} = ?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun setString(key: String, value: String) {
        val values = ContentValues().apply {
            put(t.COL_KEY, key)
            put(t.COL_VALUE, value)
        }
        db.insertWithOnConflict(t.TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getBool(key: String, default: Boolean): Boolean =
        getString(key)?.toBooleanStrictOrNull() ?: default

    fun setBool(key: String, value: Boolean) = setString(key, value.toString())

    fun getLong(key: String, default: Long): Long =
        getString(key)?.toLongOrNull() ?: default

    fun setLong(key: String, value: Long) = setString(key, value.toString())

    companion object {
        const val KEY_ALLOWLIST_ONLY = "allowlist_only"
        const val KEY_NOTIFICATION_PRIVACY = "notification_privacy"
        const val KEY_LOCK_TIMEOUT_MILLIS = "lock_timeout_millis"
        const val KEY_USERNAME = "username"
        const val KEY_BATTERY_ASKED = "battery_asked"
        const val KEY_ALLOW_SCREENSHOTS = "allow_screenshots"
        const val KEY_PROFILE_KEY = "profile_key"
    }
}
