package app.niix.core.storage

import android.content.ContentValues
import app.niix.core.model.DeliveryState
import app.niix.core.model.Message
import app.niix.core.model.MessageDirection
import app.niix.core.model.MessageType
import app.niix.core.model.clampDisappearSeconds
import net.zetetic.database.sqlcipher.SQLiteDatabase

data class ExpiredMessage(val id: String, val attachmentId: String?)

class MessageDao internal constructor(private val secureDatabase: SecureDatabase) {

    private val db: SQLiteDatabase get() = secureDatabase.open()
    private val t = Schema.Messages

    /**
     * Inserts a message, keeping an existing row with the same id instead of overwriting it.
     *
     * Incoming message ids are chosen by the sender, so REPLACE would let anyone who knew an id
     * rewrite a message already stored. Ignoring duplicates is also right for a message that shows
     * up twice (directly and via a relay), which is normal. Outgoing state changes go through
     * updateDeliveryState and startExpiry, never through insert.
     */
    fun insert(message: Message) {
        db.insertWithOnConflict(t.TABLE, null, message.toValues(), SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun get(id: String): Message? {
        db.rawQuery("SELECT * FROM ${t.TABLE} WHERE ${t.COL_ID} = ?", arrayOf(id)).use { c ->
            return if (c.moveToFirst()) c.toMessage() else null
        }
    }

    fun listForConversation(conversationId: String, includeDeleted: Boolean = false): List<Message> {
        val deletedClause = if (includeDeleted) "" else " AND ${t.COL_DELETED} = 0"
        val result = mutableListOf<Message>()
        db.rawQuery(
            "SELECT * FROM ${t.TABLE} WHERE ${t.COL_CONVERSATION_ID} = ?$deletedClause " +
                "ORDER BY ${t.COL_CREATED_AT} ASC",
            arrayOf(conversationId),
        ).use { c ->
            while (c.moveToNext()) result.add(c.toMessage())
        }
        return result
    }

    /** The newest message in a conversation that hasn't been deleted, for the conversation list. */
    fun latest(conversationId: String): Message? {
        db.rawQuery(
            "SELECT * FROM ${t.TABLE} WHERE ${t.COL_CONVERSATION_ID} = ? AND ${t.COL_DELETED} = 0 " +
                "ORDER BY ${t.COL_CREATED_AT} DESC LIMIT 1",
            arrayOf(conversationId),
        ).use { c ->
            return if (c.moveToFirst()) c.toMessage() else null
        }
    }

    /**
     * Outgoing messages not yet confirmed as received, so they can be retried.
     *
     * RELAYED counts too: a relay can drop or expire a message, and only the recipient's receipt
     * confirms delivery. Resending is safe because the receiver ignores a message id it already
     * has.
     */
    fun pendingOutgoing(): List<Message> {
        val messages = mutableListOf<Message>()
        db.rawQuery(
            "SELECT * FROM ${t.TABLE} WHERE ${t.COL_DIRECTION} = ? AND ${t.COL_DELETED} = 0 " +
                "AND ${t.COL_DELIVERY_STATE} IN (?, ?) ORDER BY ${t.COL_CREATED_AT} ASC",
            arrayOf(MessageDirection.OUTGOING.name, DeliveryState.PENDING.name, DeliveryState.RELAYED.name),
        ).use { c -> while (c.moveToNext()) messages.add(c.toMessage()) }
        return messages
    }

    /** Whether an outgoing message created since [sinceMillis] is still PENDING. */
    fun hasUnsentSince(sinceMillis: Long): Boolean =
        db.rawQuery(
            "SELECT 1 FROM ${t.TABLE} WHERE ${t.COL_DIRECTION} = ? AND ${t.COL_DELETED} = 0 " +
                "AND ${t.COL_DELIVERY_STATE} = ? AND ${t.COL_CREATED_AT} >= ? LIMIT 1",
            arrayOf(MessageDirection.OUTGOING.name, DeliveryState.PENDING.name, sinceMillis.toString()),
        ).use { it.moveToFirst() }

    fun deleteForConversation(conversationId: String) {
        db.delete(t.TABLE, "${t.COL_CONVERSATION_ID} = ?", arrayOf(conversationId))
    }

    /**
     * Puts a FAILED outgoing message back to PENDING, dated [nowMillis] so it moves to the end of
     * the conversation and its retries start over. False if it isn't a failed outgoing message.
     */
    fun sendAgain(id: String, nowMillis: Long): Boolean {
        val values = ContentValues().apply {
            put(t.COL_DELIVERY_STATE, DeliveryState.PENDING.name)
            put(t.COL_CREATED_AT, nowMillis)
        }
        return db.update(
            t.TABLE, values,
            "${t.COL_ID} = ? AND ${t.COL_DIRECTION} = ? AND ${t.COL_DELIVERY_STATE} = ? AND ${t.COL_DELETED} = 0",
            arrayOf(id, MessageDirection.OUTGOING.name, DeliveryState.FAILED.name),
        ) > 0
    }

    fun updateDeliveryState(id: String, state: DeliveryState) {
        val values = ContentValues().apply { put(t.COL_DELIVERY_STATE, state.name) }
        db.update(t.TABLE, values, "${t.COL_ID} = ?", arrayOf(id))
    }

    /**
     * Marks a message as revealed. A targeted update, so it can't disturb delivery state or expiry
     * that changed in the meantime.
     */
    fun markRevealed(messageId: String) {
        val values = ContentValues().apply { put(t.COL_REVEALED, 1) }
        db.update(t.TABLE, values, "${t.COL_ID} = ?", arrayOf(messageId))
    }

    fun startExpiry(id: String, durationSeconds: Long, nowEpochMillis: Long) {
        val safeDuration = clampDisappearSeconds(durationSeconds)
        val values = ContentValues().apply { put(t.COL_EXPIRES_AT, nowEpochMillis + safeDuration * 1000) }
        db.update(t.TABLE, values, "${t.COL_ID} = ? AND ${t.COL_EXPIRES_AT} IS NULL", arrayOf(id))
    }

    fun markDeletedForEveryone(id: String) {
        val values = ContentValues().apply {
            put(t.COL_DELETED, 1)
            put(t.COL_BODY, "")
            putNull(t.COL_ATTACHMENT_ID)
        }
        db.update(t.TABLE, values, "${t.COL_ID} = ?", arrayOf(id))
    }

    fun deleteLocally(id: String): String? {
        val attachmentId = get(id)?.attachmentId
        db.delete(t.TABLE, "${t.COL_ID} = ?", arrayOf(id))
        return attachmentId
    }

    /**
     * Deletes everything past its expiry and returns what went, so the caller can remove the
     * attachment files. One transaction, so nothing can expire between the select and the delete
     * and leave an attachment file nobody knows about.
     */
    fun deleteExpired(nowEpochMillis: Long): List<ExpiredMessage> {
        val expired = mutableListOf<ExpiredMessage>()
        val database = db
        database.beginTransaction()
        try {
            database.rawQuery(
                "SELECT ${t.COL_ID}, ${t.COL_ATTACHMENT_ID} FROM ${t.TABLE} " +
                    "WHERE ${t.COL_EXPIRES_AT} IS NOT NULL AND ${t.COL_EXPIRES_AT} <= ?",
                arrayOf(nowEpochMillis.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    expired.add(ExpiredMessage(c.getString(0), if (c.isNull(1)) null else c.getString(1)))
                }
            }
            if (expired.isNotEmpty()) {
                database.delete(t.TABLE, "${t.COL_EXPIRES_AT} IS NOT NULL AND ${t.COL_EXPIRES_AT} <= ?", arrayOf(nowEpochMillis.toString()))
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return expired
    }

    private fun Message.toValues(): ContentValues = ContentValues().apply {
        put(t.COL_ID, id)
        put(t.COL_CONVERSATION_ID, conversationId)
        put(t.COL_SENDER_ONION, senderOnion)
        put(t.COL_DIRECTION, direction.name)
        put(t.COL_TYPE, type.name)
        put(t.COL_BODY, body)
        if (attachmentId == null) putNull(t.COL_ATTACHMENT_ID) else put(t.COL_ATTACHMENT_ID, attachmentId)
        put(t.COL_CREATED_AT, createdAtEpochMillis)
        if (expiresAtEpochMillis == null) putNull(t.COL_EXPIRES_AT) else put(t.COL_EXPIRES_AT, expiresAtEpochMillis)
        put(t.COL_DELIVERY_STATE, deliveryState.name)
        put(t.COL_DELETED, if (deleted) 1 else 0)
        put(t.COL_REMOTE_DELETABLE, if (remoteDeletable) 1 else 0)
        if (disappearSeconds == null) putNull(t.COL_DISAPPEAR_SECONDS) else put(t.COL_DISAPPEAR_SECONDS, disappearSeconds)
        if (replyToId == null) putNull(t.COL_REPLY_TO_ID) else put(t.COL_REPLY_TO_ID, replyToId)
        put(t.COL_REVEALED, if (revealed) 1 else 0)
    }

    private fun android.database.Cursor.toMessage(): Message {
        val attachmentIdx = getColumnIndexOrThrow(t.COL_ATTACHMENT_ID)
        val expiresIdx = getColumnIndexOrThrow(t.COL_EXPIRES_AT)
        val disappearIdx = getColumnIndexOrThrow(t.COL_DISAPPEAR_SECONDS)
        // OrThrow on purpose: migrations run when the database opens, so a missing column means one
        // failed, and a lenient read would just hide it.
        val replyIdx = getColumnIndexOrThrow(t.COL_REPLY_TO_ID)
        val revealedIdx = getColumnIndexOrThrow(t.COL_REVEALED)
        return Message(
            id = getString(getColumnIndexOrThrow(t.COL_ID)),
            conversationId = getString(getColumnIndexOrThrow(t.COL_CONVERSATION_ID)),
            senderOnion = getString(getColumnIndexOrThrow(t.COL_SENDER_ONION)),
            direction = MessageDirection.valueOf(getString(getColumnIndexOrThrow(t.COL_DIRECTION))),
            type = MessageType.valueOf(getString(getColumnIndexOrThrow(t.COL_TYPE))),
            body = getString(getColumnIndexOrThrow(t.COL_BODY)),
            attachmentId = if (isNull(attachmentIdx)) null else getString(attachmentIdx),
            createdAtEpochMillis = getLong(getColumnIndexOrThrow(t.COL_CREATED_AT)),
            expiresAtEpochMillis = if (isNull(expiresIdx)) null else getLong(expiresIdx),
            deliveryState = DeliveryState.valueOf(getString(getColumnIndexOrThrow(t.COL_DELIVERY_STATE))),
            deleted = getInt(getColumnIndexOrThrow(t.COL_DELETED)) == 1,
            remoteDeletable = getInt(getColumnIndexOrThrow(t.COL_REMOTE_DELETABLE)) == 1,
            disappearSeconds = if (isNull(disappearIdx)) null else getLong(disappearIdx),
            replyToId = if (isNull(replyIdx)) null else getString(replyIdx),
            revealed = getInt(revealedIdx) == 1,
        )
    }
}
