package app.niix.core.storage

import android.content.ContentValues
import net.zetetic.database.sqlcipher.SQLiteDatabase

data class PendingGroupInvite(
    val conversationId: String,
    val inviterOnion: String,
    val title: String,
    val members: List<String>,
    val admins: List<String>,
    val receivedAtEpochMillis: Long,
    val epoch: Long = 1,
)

/**
 * A group invite for a conversationId this device has never seen before. Nothing here ever
 * touches `conversations` or `group_members` -- those only get written once the person
 * explicitly accepts (see ConversationManager.acceptGroupInvite), so an unsolicited invite can
 * never plant a fabricated group (or membership list) without a deliberate action first.
 */
class PendingGroupInviteDao internal constructor(private val secureDatabase: SecureDatabase) {

    private val db: SQLiteDatabase get() = secureDatabase.open()
    private val t = Schema.PendingGroupInvites

    /** Onions (Tor v3 addresses) never contain commas, so a plain join/split is safe here
     * without pulling in a JSON dependency for two small string lists. */
    fun upsert(invite: PendingGroupInvite) {
        val values = ContentValues().apply {
            put(t.COL_CONVERSATION_ID, invite.conversationId)
            put(t.COL_INVITER_ONION, invite.inviterOnion)
            put(t.COL_TITLE, invite.title)
            put(t.COL_MEMBERS, invite.members.joinToString(","))
            put(t.COL_ADMINS, invite.admins.joinToString(","))
            put(t.COL_RECEIVED_AT, invite.receivedAtEpochMillis)
            put(t.COL_EPOCH, invite.epoch)
        }
        db.insertWithOnConflict(t.TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun get(conversationId: String): PendingGroupInvite? {
        db.rawQuery("SELECT * FROM ${t.TABLE} WHERE ${t.COL_CONVERSATION_ID} = ?", arrayOf(conversationId)).use { c ->
            return if (c.moveToFirst()) c.toInvite() else null
        }
    }

    fun list(): List<PendingGroupInvite> {
        val result = mutableListOf<PendingGroupInvite>()
        db.rawQuery("SELECT * FROM ${t.TABLE} ORDER BY ${t.COL_RECEIVED_AT} DESC", null).use { c ->
            while (c.moveToNext()) result.add(c.toInvite())
        }
        return result
    }

    fun delete(conversationId: String) {
        db.delete(t.TABLE, "${t.COL_CONVERSATION_ID} = ?", arrayOf(conversationId))
    }

    private fun android.database.Cursor.toInvite(): PendingGroupInvite = PendingGroupInvite(
        conversationId = getString(getColumnIndexOrThrow(t.COL_CONVERSATION_ID)),
        inviterOnion = getString(getColumnIndexOrThrow(t.COL_INVITER_ONION)),
        title = getString(getColumnIndexOrThrow(t.COL_TITLE)),
        members = getString(getColumnIndexOrThrow(t.COL_MEMBERS)).split(",").filter { it.isNotBlank() },
        admins = getString(getColumnIndexOrThrow(t.COL_ADMINS)).split(",").filter { it.isNotBlank() },
        receivedAtEpochMillis = getLong(getColumnIndexOrThrow(t.COL_RECEIVED_AT)),
        epoch = getLong(getColumnIndexOrThrow(t.COL_EPOCH)),
    )
}
