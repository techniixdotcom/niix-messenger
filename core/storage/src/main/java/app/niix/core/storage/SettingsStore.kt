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

    /** Deletes a setting outright, rather than writing a default over it. Needed for settings
     * keyed by conversation id: leaving a row behind means a later conversation reusing that id
     * inherits it. */
    fun remove(key: String) {
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

        /** Route update checks and downloads through Tor rather than straight out over the
         * network. Off by default -- updates are far more reliable without the extra hop, and
         * the only thing this protects is whether an observer can tell this device runs the
         * app at all. Messages, contacts and everything else always go over Tor regardless. */
        const val KEY_UPDATE_OVER_TOR = "update_over_tor"

        /** Disconnect from Tor whenever the app locks. Off by default: with it on, nothing can
         * reach this device while it is locked, which is the stronger privacy property but also
         * means messages only arrive once you unlock. */
        const val KEY_DISCONNECT_WHEN_LOCKED = "disconnect_when_locked"

        /** Which launcher icon the undisguised app uses: true for the light variant. */
        const val KEY_LIGHT_ICON = "light_launcher_icon"

        /** Blur incoming messages until tapped. On by default -- it is the kind of protection
         * that only helps if it is there before you needed it. */
        const val KEY_BLUR_MESSAGES = "blur_incoming_messages"

        /** Skip the confirmation on the main-screen wipe button. Default false: an accidental
         * tap must not be able to destroy everything, and the whole point of putting the button
         * on the main screen is that it is easy to reach in a hurry -- which makes it equally
         * easy to reach by mistake. */
        const val KEY_WIPE_WITHOUT_CONFIRM = "wipe_without_confirmation"

        /** Per-conversation mute. Keyed by conversation id rather than kept as a list, so muting
         * one conversation cannot disturb another and a deleted conversation leaves nothing
         * behind that a later conversation could inherit. */
        fun mutedKey(conversationId: String) = "muted:$conversationId"
        const val KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at"

        const val KEY_RELAY_MODE_ENABLED = "relay_mode_enabled"

        const val KEY_RELAY_STORAGE_BUDGET_BYTES = "relay_storage_budget_bytes"

        const val KEY_COVER_TRAFFIC_ENABLED = "cover_traffic_enabled"
    }
}
