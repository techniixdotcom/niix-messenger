package app.niix.core.storage

import android.content.ContentValues
import net.zetetic.database.sqlcipher.SQLiteDatabase

class SettingsStore internal constructor(private val secureDatabase: SecureDatabase) : KeyValueStore {

    private val db: SQLiteDatabase get() = secureDatabase.open()
    private val t = Schema.Settings

    override fun getString(key: String): String? {
        db.rawQuery("SELECT ${t.COL_VALUE} FROM ${t.TABLE} WHERE ${t.COL_KEY} = ?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    override fun setString(key: String, value: String) {
        val values = ContentValues().apply {
            put(t.COL_KEY, key)
            put(t.COL_VALUE, value)
        }
        db.insertWithOnConflict(t.TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun getBool(key: String, default: Boolean): Boolean =
        getString(key)?.toBooleanStrictOrNull() ?: default

    override fun setBool(key: String, value: Boolean) = setString(key, value.toString())

    override fun remove(key: String) {
        db.delete(t.TABLE, "${t.COL_KEY} = ?", arrayOf(key))
    }

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
        const val KEY_UPDATE_CHECK_ENABLED = "update_check_enabled"

        const val KEY_UPDATE_OVER_TOR = "update_over_tor"

        const val KEY_DISCONNECT_WHEN_LOCKED = "disconnect_when_locked"



        /** Highest wall-clock time ever observed. See MonotonicClock. */
        const val KEY_CLOCK_HIGH_WATER = "clock_high_water"

        /** Skip the confirmation on the main-screen wipe button. Default false: an accidental
         * tap must not be able to destroy everything, and the whole point of putting the button
         * on the main screen is that it is easy to reach in a hurry, which makes it equally
         * easy to reach by mistake. */
        const val KEY_WIPE_WITHOUT_CONFIRM = "wipe_without_confirmation"

        fun mutedKey(conversationId: String) = "muted:$conversationId"
        const val KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at"

        const val KEY_RELAY_MODE_ENABLED = "relay_mode_enabled"

        const val KEY_RELAY_STORAGE_BUDGET_BYTES = "relay_storage_budget_bytes"

        const val KEY_COVER_TRAFFIC_ENABLED = "cover_traffic_enabled"
        /** Blur incoming messages until tapped. On by default, it is the kind of protection
         * that only helps if it is there before you needed it. */
        const val KEY_BLUR_MESSAGES = "blur_incoming_messages"
    }
}
