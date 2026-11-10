package app.niix.core.storage

import android.content.ContentValues
import net.zetetic.database.sqlcipher.SQLiteDatabase

class GroupRemoteSenderKeyDao internal constructor(private val secureDatabase: SecureDatabase) {

    private val db: SQLiteDatabase get() = secureDatabase.open()
    private val t = Schema.GroupRemoteSenderKeys

    fun record(conversationId: String, senderOnion: String, distributionId: String) {
        val values = ContentValues().apply {
            put(t.COL_CONVERSATION_ID, conversationId)
            put(t.COL_SENDER_ONION, senderOnion)
            put(t.COL_DISTRIBUTION_ID, distributionId)
        }
        db.insertWithOnConflict(t.TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun listDistributionIds(conversationId: String, senderOnion: String): List<String> {
        val ids = mutableListOf<String>()
        db.rawQuery(
            "SELECT ${t.COL_DISTRIBUTION_ID} FROM ${t.TABLE} " +
                "WHERE ${t.COL_CONVERSATION_ID} = ? AND ${t.COL_SENDER_ONION} = ?",
            arrayOf(conversationId, senderOnion),
        ).use { cursor ->
            while (cursor.moveToNext()) ids.add(cursor.getString(0))
        }
        return ids
    }

    fun deleteForConversationAndSender(conversationId: String, senderOnion: String) {
        db.delete(
            t.TABLE,
            "${t.COL_CONVERSATION_ID} = ? AND ${t.COL_SENDER_ONION} = ?",
            arrayOf(conversationId, senderOnion),
        )
    }

    fun deleteForConversation(conversationId: String) {
        db.delete(t.TABLE, "${t.COL_CONVERSATION_ID} = ?", arrayOf(conversationId))
    }
}
