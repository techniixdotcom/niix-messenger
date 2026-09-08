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
     * Inserts a message, keeping any existing row with the same id rather than overwriting it.
     *
     * CONFLICT_IGNORE, not CONFLICT_REPLACE. Message ids on incoming traffic are chosen by the
     * sender, so replace semantics let anyone who learns (or guesses) an id rewrite the contents
     * of a message already sitting in the database -- silently changing history rather than
     * adding to it. Ignoring the duplicate is also the right behaviour for a genuine replay or a
     * message that arrives twice over different paths (direct and relay), which is normal here.
     * Outgoing state changes go through updateDeliveryState/startExpiry, not through insert, so
     * nothing legitimately depends on being able to overwrite a row this way.
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

    fun searchConversationIds(query: String): List<String> {
        val ids = LinkedHashSet<String>()
        db.rawQuery(
            "SELECT DISTINCT ${t.COL_CONVERSATION_ID} FROM ${t.TABLE} " +
                "WHERE ${t.COL_DELETED} = 0 AND ${t.COL_BODY} LIKE ?",
            arrayOf("%$query%"),
        ).use { c -> while (c.moveToNext()) ids.add(c.getString(0)) }
        return ids.toList()
    }

    fun searchMatchingBodies(query: String): Map<String, String> {
        val matches = LinkedHashMap<String, String>()
        db.rawQuery(
            "SELECT ${t.COL_CONVERSATION_ID}, ${t.COL_BODY} FROM ${t.TABLE} " +
                "WHERE ${t.COL_DELETED} = 0 AND ${t.COL_BODY} LIKE ? " +
                "ORDER BY ${t.COL_CREATED_AT} DESC",
            arrayOf("%$query%"),
        ).use { c ->
            while (c.moveToNext()) {
                val conversationId = c.getString(0)

                if (!matches.containsKey(conversationId)) matches[conversationId] = c.getString(1)
            }
        }
        return matches
    }

    /**
     * Outgoing messages that have not been confirmed as received, so they can be retried.
     *
     * Includes RELAYED as well as PENDING. A relayed message has only been handed to a relay --
     * it isn't confirmed delivered until the recipient picks it up and a receipt comes back. If
     * the relay drops it first (its store has both a TTL and a size quota), nothing would ever
     * retry it and the message would be silently lost while showing as "relayed" forever.
     *
     * Re-sending an already-relayed message is safe in both directions: message insert on the
     * receiving side ignores an id it already holds, and the relay store rejects an envelope it
     * is already carrying for that recipient. So a retry either reaches somewhere new or does
     * nothing -- it cannot duplicate a message.
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

    fun deleteForConversation(conversationId: String) {
        db.delete(t.TABLE, "${t.COL_CONVERSATION_ID} = ?", arrayOf(conversationId))
    }

    fun updateDeliveryState(id: String, state: DeliveryState) {
        val values = ContentValues().apply { put(t.COL_DELIVERY_STATE, state.name) }
        db.update(t.TABLE, values, "${t.COL_ID} = ?", arrayOf(id))
    }

    fun setExpiry(id: String, expiresAtEpochMillis: Long?) {
        val values = ContentValues().apply {
            if (expiresAtEpochMillis == null) putNull(t.COL_EXPIRES_AT) else put(t.COL_EXPIRES_AT, expiresAtEpochMillis)
        }
        db.update(t.TABLE, values, "${t.COL_ID} = ?", arrayOf(id))
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

    fun collectExpired(nowEpochMillis: Long): List<ExpiredMessage> {
        val expired = mutableListOf<ExpiredMessage>()
        db.rawQuery(
            "SELECT ${t.COL_ID}, ${t.COL_ATTACHMENT_ID} FROM ${t.TABLE} " +
                "WHERE ${t.COL_EXPIRES_AT} IS NOT NULL AND ${t.COL_EXPIRES_AT} <= ?",
            arrayOf(nowEpochMillis.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val attachmentId = if (c.isNull(1)) null else c.getString(1)
                expired.add(ExpiredMessage(c.getString(0), attachmentId))
            }
        }
        return expired
    }

    fun deleteExpired(nowEpochMillis: Long): List<ExpiredMessage> {
        val expired = collectExpired(nowEpochMillis)
        if (expired.isNotEmpty()) {
            db.delete(
                t.TABLE,
                "${t.COL_EXPIRES_AT} IS NOT NULL AND ${t.COL_EXPIRES_AT} <= ?",
                arrayOf(nowEpochMillis.toString()),
            )
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
    }

    private fun android.database.Cursor.toMessage(): Message {
        val attachmentIdx = getColumnIndexOrThrow(t.COL_ATTACHMENT_ID)
        val expiresIdx = getColumnIndexOrThrow(t.COL_EXPIRES_AT)
        val disappearIdx = getColumnIndexOrThrow(t.COL_DISAPPEAR_SECONDS)
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
        )
    }
}
