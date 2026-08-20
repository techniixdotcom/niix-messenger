package app.niix.core.storage

import android.content.ContentValues
import net.zetetic.database.sqlcipher.SQLiteDatabase

data class GroupSenderKeyState(
    val conversationId: String,
    val distributionId: String,
    /** The conversation epoch this distribution was generated at. If the conversation's current
     * epoch has since moved past this, the distribution is stale -- a membership change
     * happened -- and a caller should generate and redistribute a fresh one rather than reuse
     * it. See ConversationManager.syncGroup. */
    val epoch: Long,
)

/**
 * Tracks which of *our own* sender-key sessions (see [app.niix.core.crypto.GroupCryptoEngine])
 * is current for each group we're a member of. This is deliberately separate from the actual
 * key material -- that lives in the crypto module's own `group_sender_keys` table, keyed by
 * (sender, distributionId) per libsignal's `SenderKeyStore` contract -- this table just answers
 * "which distributionId is our current one for this group right now."
 */
class GroupSenderKeyStateDao internal constructor(private val secureDatabase: SecureDatabase) {

    private val db: SQLiteDatabase get() = secureDatabase.open()
    private val t = Schema.GroupSenderKeyState

    fun get(conversationId: String): GroupSenderKeyState? {
        db.rawQuery(
            "SELECT ${t.COL_DISTRIBUTION_ID}, ${t.COL_EPOCH} FROM ${t.TABLE} " +
                "WHERE ${t.COL_CONVERSATION_ID} = ?",
            arrayOf(conversationId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return GroupSenderKeyState(conversationId, cursor.getString(0), cursor.getLong(1))
        }
    }

    fun set(state: GroupSenderKeyState) {
        val values = ContentValues().apply {
            put(t.COL_CONVERSATION_ID, state.conversationId)
            put(t.COL_DISTRIBUTION_ID, state.distributionId)
            put(t.COL_EPOCH, state.epoch)
        }
        db.insertWithOnConflict(t.TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun delete(conversationId: String) {
        db.delete(t.TABLE, "${t.COL_CONVERSATION_ID} = ?", arrayOf(conversationId))
    }
}
