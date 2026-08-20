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
        const val KEY_UPDATE_CHECK_ENABLED = "update_check_enabled"
        const val KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at"

        /** "Enable relay mode" (item 11.8): whether this device stores *other* people's offline
         * messages and participates in the relay Kademlia overlay as a queryable node. Default
         * OFF. Sending/receiving your own messages via other people's relays is unaffected by
         * this and always available -- see [app.niix.core.relay.RelayManager]'s class doc. */
        const val KEY_RELAY_MODE_ENABLED = "relay_mode_enabled"

        /** Optional storage/bandwidth budget (bytes) an operator caps relay hosting at -- feeds
         * `maxTotalRelayBytes` (item 11.5). Defaults to
         * [app.niix.core.relay.RelayProtocol.DEFAULT_MAX_TOTAL_RELAY_BYTES]. */
        const val KEY_RELAY_STORAGE_BUDGET_BYTES = "relay_storage_budget_bytes"

        /** Cover traffic: periodic decoy messages sent to a random contact so real message
         * timing isn't distinguishable from idle to a network-position observer -- see
         * [app.niix.core.messaging.CoverTrafficScheduler]. Off by default: it costs battery and
         * a small amount of bandwidth, so enabling it should be the person's own choice. */
        const val KEY_COVER_TRAFFIC_ENABLED = "cover_traffic_enabled"
    }
}
