package app.niix.core.storage

import android.content.ContentValues
import app.niix.core.model.Attachment
import app.niix.core.model.AttachmentState
import net.zetetic.database.sqlcipher.SQLiteDatabase

class AttachmentDao internal constructor(private val secureDatabase: SecureDatabase) {

    private val db: SQLiteDatabase get() = secureDatabase.open()
    private val t = Schema.Attachments

    fun insert(attachment: Attachment) {
        val values = ContentValues().apply {
            put(t.COL_ID, attachment.id)
            put(t.COL_CONVERSATION_ID, attachment.conversationId)
            put(t.COL_FILE_PATH, attachment.filePath)
            put(t.COL_MIME_TYPE, attachment.mimeType)
            put(t.COL_SIZE_BYTES, attachment.sizeBytes)
            put(t.COL_ENC_KEY, attachment.encKey)
            if (attachment.digest == null) putNull(t.COL_DIGEST) else put(t.COL_DIGEST, attachment.digest)
            put(t.COL_STATE, attachment.state.name)
            put(t.COL_CREATED_AT, attachment.createdAtEpochMillis)
        }
        db.insertWithOnConflict(t.TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun get(id: String): Attachment? {
        db.rawQuery("SELECT * FROM ${t.TABLE} WHERE ${t.COL_ID} = ?", arrayOf(id)).use { c ->
            return if (c.moveToFirst()) c.toAttachment() else null
        }
    }

    fun updateState(id: String, state: AttachmentState) {
        val values = ContentValues().apply { put(t.COL_STATE, state.name) }
        db.update(t.TABLE, values, "${t.COL_ID} = ?", arrayOf(id))
    }

    fun delete(id: String) {
        db.delete(t.TABLE, "${t.COL_ID} = ?", arrayOf(id))
    }

    fun listForConversation(conversationId: String): List<Attachment> {
        val items = mutableListOf<Attachment>()
        db.rawQuery("SELECT * FROM ${t.TABLE} WHERE ${t.COL_CONVERSATION_ID} = ?", arrayOf(conversationId)).use { c ->
            while (c.moveToNext()) items.add(c.toAttachment())
        }
        return items
    }

    fun deleteForConversation(conversationId: String) {
        db.delete(t.TABLE, "${t.COL_CONVERSATION_ID} = ?", arrayOf(conversationId))
    }

    private fun android.database.Cursor.toAttachment(): Attachment {
        val digestIdx = getColumnIndexOrThrow(t.COL_DIGEST)
        return Attachment(
            id = getString(getColumnIndexOrThrow(t.COL_ID)),
            conversationId = getString(getColumnIndexOrThrow(t.COL_CONVERSATION_ID)),
            filePath = getString(getColumnIndexOrThrow(t.COL_FILE_PATH)),
            mimeType = getString(getColumnIndexOrThrow(t.COL_MIME_TYPE)),
            sizeBytes = getLong(getColumnIndexOrThrow(t.COL_SIZE_BYTES)),
            encKey = getBlob(getColumnIndexOrThrow(t.COL_ENC_KEY)),
            digest = if (isNull(digestIdx)) null else getBlob(digestIdx),
            state = AttachmentState.valueOf(getString(getColumnIndexOrThrow(t.COL_STATE))),
            createdAtEpochMillis = getLong(getColumnIndexOrThrow(t.COL_CREATED_AT)),
        )
    }
}
