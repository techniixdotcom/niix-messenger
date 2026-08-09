package app.niix.core.storage

import android.content.ContentValues
import app.niix.core.model.Conversation
import app.niix.core.model.ConversationType
import net.zetetic.database.sqlcipher.SQLiteDatabase

class ConversationDao internal constructor(private val secureDatabase: SecureDatabase) {

    private val db: SQLiteDatabase get() = secureDatabase.open()
    private val t = Schema.Conversations

    fun upsert(conversation: Conversation) {
        val values = ContentValues().apply {
            put(t.COL_ID, conversation.id)
            put(t.COL_TYPE, conversation.type.name)
            put(t.COL_TITLE, conversation.title)
            put(t.COL_DISAPPEAR_SECONDS, conversation.disappearSeconds)
            put(t.COL_CREATED_AT, conversation.createdAtEpochMillis)
            put(t.COL_PENDING, if (conversation.pending) 1 else 0)
            put(t.COL_EPOCH, conversation.epoch)
        }
        db.insertWithOnConflict(t.TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun setPending(id: String, pending: Boolean) {
        val values = ContentValues().apply { put(t.COL_PENDING, if (pending) 1 else 0) }
        db.update(t.TABLE, values, "${t.COL_ID} = ?", arrayOf(id))
    }

    fun setEpoch(id: String, epoch: Long) {
        val values = ContentValues().apply { put(t.COL_EPOCH, epoch) }
        db.update(t.TABLE, values, "${t.COL_ID} = ?", arrayOf(id))
    }

    fun get(id: String): Conversation? {
        db.rawQuery("SELECT * FROM ${t.TABLE} WHERE ${t.COL_ID} = ?", arrayOf(id)).use { c ->
            return if (c.moveToFirst()) c.toConversation() else null
        }
    }

    fun list(): List<Conversation> {
        val result = mutableListOf<Conversation>()
        db.rawQuery("SELECT * FROM ${t.TABLE} ORDER BY ${t.COL_CREATED_AT} DESC", emptyArray()).use { c ->
            while (c.moveToNext()) result.add(c.toConversation())
        }
        return result
    }

    fun setDisappearSeconds(id: String, seconds: Long) {
        val values = ContentValues().apply { put(t.COL_DISAPPEAR_SECONDS, seconds) }
        db.update(t.TABLE, values, "${t.COL_ID} = ?", arrayOf(id))
    }

    fun delete(id: String) {
        db.delete(t.TABLE, "${t.COL_ID} = ?", arrayOf(id))
    }

    private fun android.database.Cursor.toConversation(): Conversation = Conversation(
        id = getString(getColumnIndexOrThrow(t.COL_ID)),
        type = ConversationType.valueOf(getString(getColumnIndexOrThrow(t.COL_TYPE))),
        title = getString(getColumnIndexOrThrow(t.COL_TITLE)),
        disappearSeconds = getLong(getColumnIndexOrThrow(t.COL_DISAPPEAR_SECONDS)),
        createdAtEpochMillis = getLong(getColumnIndexOrThrow(t.COL_CREATED_AT)),
        pending = getInt(getColumnIndexOrThrow(t.COL_PENDING)) != 0,
        epoch = getLong(getColumnIndexOrThrow(t.COL_EPOCH)),
    )
}
