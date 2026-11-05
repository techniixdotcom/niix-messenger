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

    /** Every key starting with [prefix], compared literally. */
    fun keysWithPrefix(prefix: String): List<String> =
        db.rawQuery(
            "SELECT ${t.COL_KEY} FROM ${t.TABLE} WHERE substr(${t.COL_KEY}, 1, ?) = ?",
            arrayOf(prefix.length.toString(), prefix),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** Removes every key starting with [prefix]. Compared literally, so % and _ mean themselves. */
    fun removeWithPrefix(prefix: String) {
        db.delete(t.TABLE, "substr(${t.COL_KEY}, 1, ?) = ?", arrayOf(prefix.length.toString(), prefix))
    }

    companion object {
        const val KEY_ALLOWLIST_ONLY = "allowlist_only"
        const val KEY_NOTIFICATION_PRIVACY = "notification_privacy"
        const val KEY_USERNAME = "username"

        /**
         * The account this device belongs to, when it was linked to one. Unset on the device an
         * account was created on, whose own address is the account's.
         */
        const val KEY_ACCOUNT = "account_onion"

        /**
         * Set when setup chose to link to an existing account, until it's linked or the person
         * backs out. While set, the app opens on the link screen.
         */
        const val KEY_LINK_PENDING = "link_pending"

        /** The key any of the account's devices can erase the others with. See WipeBeacon. */
        const val KEY_WIPE_KEY = "wipe_key"

        /** The device-list epoch the wipe key dates from, so an older key arriving late can't replace it. */
        const val KEY_WIPE_KEY_EPOCH = "wipe_key_epoch"

        /** When the wipe key last changed, while some of our devices may not have it yet. */
        const val KEY_WIPE_KEY_OWED_SINCE = "wipe_key_owed_since"
        const val KEY_BATTERY_ASKED = "battery_asked"
        const val KEY_ALLOW_SCREENSHOTS = "allow_screenshots"
        const val KEY_PROFILE_KEY = "profile_key"
        const val KEY_UPDATE_CHECK_ENABLED = "update_check_enabled"
        const val KEY_UPDATE_OVER_TOR = "update_over_tor"
        const val KEY_NEXT_UPDATE_CHECK = "next_update_check"
        const val KEY_DISCONNECT_WHEN_LOCKED = "disconnect_when_locked"
        const val KEY_COVER_TRAFFIC_ENABLED = "cover_traffic_enabled"

        /** Ping contacts after reconnecting so they send what they were holding. Off by default: it
         * tells them when this device comes online. Same key as 1.0.1, so the choice carries over. */
        const val KEY_ANNOUNCE_ONLINE = "announce_online"

        /** When the "NiiX was stopped in the background" notice was last shown, to limit it. */
        const val KEY_STOPPED_NOTICE_AT = "stopped_notice_at"

        /** Type with the phone's keyboard instead of NiiX's own. See NiixKeyboardController. */
        const val KEY_PHONE_KEYBOARD = "phone_keyboard"

        /** Latest wall-clock time seen. See MonotonicClock. */
        const val KEY_CLOCK_HIGH_WATER = "clock_high_water"

        /**
         * Skip the confirmation on the main-screen wipe button. Off by default: the button is easy
         * to reach in a hurry, which also makes it easy to hit by accident.
         */
        const val KEY_WIPE_WITHOUT_CONFIRM = "wipe_without_confirmation"

        /**
         * Blur incoming messages until tapped. On by default, because it only helps if it was
         * already on.
         */
        const val KEY_BLUR_MESSAGES = "blur_incoming_messages"

        fun mutedKey(conversationId: String) = "muted:$conversationId"
    }
}
