package app.niix.core.storage

import android.content.ContentValues
import net.zetetic.database.sqlcipher.SQLiteDatabase

data class RelayGrantReceived(
    val issuerOnion: String,
    val issuerIdentityKey: ByteArray,
    val issuedAt: Long,
    val expiresAt: Long,
    val signature: ByteArray,
    /** The relay token this contact issued us: the public key is what relays see in place of our
     * identity, the private key signs our store requests. Null for grants from before tokens. */
    val tokenPublicKey: ByteArray? = null,
    val tokenPrivateKey: ByteArray? = null,
)

data class RelayGrantIssued(
    val granteeOnion: String,
    val granteeIdentityKey: ByteArray,
    val issuedAt: Long,
    val expiresAt: Long,
)

class RelayGrantDao internal constructor(private val secureDatabase: SecureDatabase) {

    private val db: SQLiteDatabase get() = secureDatabase.open()
    private val received = Schema.RelayGrantsReceived
    private val issued = Schema.RelayGrantsIssued

    fun upsertReceived(grant: RelayGrantReceived) {
        val values = ContentValues().apply {
            put(received.COL_ISSUER_ONION, grant.issuerOnion)
            put(received.COL_ISSUER_IDENTITY_KEY, grant.issuerIdentityKey)
            put(received.COL_ISSUED_AT, grant.issuedAt)
            put(received.COL_EXPIRES_AT, grant.expiresAt)
            put(received.COL_SIGNATURE, grant.signature)
            put(received.COL_TOKEN_PUBLIC, grant.tokenPublicKey)
            put(received.COL_TOKEN_PRIVATE, grant.tokenPrivateKey)
        }
        db.insertWithOnConflict(received.TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getReceived(issuerOnion: String): RelayGrantReceived? {
        db.rawQuery(
            "SELECT * FROM ${received.TABLE} WHERE ${received.COL_ISSUER_ONION} = ?",
            arrayOf(issuerOnion),
        ).use { c ->
            if (!c.moveToFirst()) return null
            return RelayGrantReceived(
                issuerOnion = c.getString(c.getColumnIndexOrThrow(received.COL_ISSUER_ONION)),
                issuerIdentityKey = c.getBlob(c.getColumnIndexOrThrow(received.COL_ISSUER_IDENTITY_KEY)),
                issuedAt = c.getLong(c.getColumnIndexOrThrow(received.COL_ISSUED_AT)),
                expiresAt = c.getLong(c.getColumnIndexOrThrow(received.COL_EXPIRES_AT)),
                signature = c.getBlob(c.getColumnIndexOrThrow(received.COL_SIGNATURE)),
                tokenPublicKey = optionalBlob(c, received.COL_TOKEN_PUBLIC),
                tokenPrivateKey = optionalBlob(c, received.COL_TOKEN_PRIVATE),
            )
        }
    }

    fun deleteReceived(issuerOnion: String) {
        db.delete(received.TABLE, "${received.COL_ISSUER_ONION} = ?", arrayOf(issuerOnion))
    }

    fun upsertIssued(grant: RelayGrantIssued) {
        // The key being replaced is kept, so envelopes a sender left on relays under it before
        // receiving the renewal can still be matched to them. Renewals are days apart and
        // envelopes live hours, so one previous key covers it.
        val previous = getIssued(grant.granteeOnion)?.granteeIdentityKey
            ?.takeUnless { it.contentEquals(grant.granteeIdentityKey) }
        val values = ContentValues().apply {
            put(issued.COL_PREVIOUS_GRANTEE_KEY, previous)
            put(issued.COL_GRANTEE_ONION, grant.granteeOnion)
            put(issued.COL_GRANTEE_IDENTITY_KEY, grant.granteeIdentityKey)
            put(issued.COL_ISSUED_AT, grant.issuedAt)
            put(issued.COL_EXPIRES_AT, grant.expiresAt)
        }
        db.insertWithOnConflict(issued.TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * The contact a relay envelope came from, by the key it was stored under: the current or
     * previous token key issued to them. Compared as hex because query arguments are text.
     */
    fun issuedOnionForGranteeKey(key: ByteArray): String? {
        val hex = key.joinToString("") { "%02X".format(it) }
        db.rawQuery(
            "SELECT ${issued.COL_GRANTEE_ONION} FROM ${issued.TABLE} " +
                "WHERE hex(${issued.COL_GRANTEE_IDENTITY_KEY}) = ? OR hex(${issued.COL_PREVIOUS_GRANTEE_KEY}) = ? LIMIT 1",
            arrayOf(hex, hex),
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    private fun optionalBlob(c: android.database.Cursor, column: String): ByteArray? {
        val index = c.getColumnIndex(column)
        return if (index < 0 || c.isNull(index)) null else c.getBlob(index)
    }

    fun getIssued(granteeOnion: String): RelayGrantIssued? {
        db.rawQuery(
            "SELECT * FROM ${issued.TABLE} WHERE ${issued.COL_GRANTEE_ONION} = ?",
            arrayOf(granteeOnion),
        ).use { c ->
            if (!c.moveToFirst()) return null
            return RelayGrantIssued(
                granteeOnion = c.getString(c.getColumnIndexOrThrow(issued.COL_GRANTEE_ONION)),
                granteeIdentityKey = c.getBlob(c.getColumnIndexOrThrow(issued.COL_GRANTEE_IDENTITY_KEY)),
                issuedAt = c.getLong(c.getColumnIndexOrThrow(issued.COL_ISSUED_AT)),
                expiresAt = c.getLong(c.getColumnIndexOrThrow(issued.COL_EXPIRES_AT)),
            )
        }
    }

    fun deleteIssued(granteeOnion: String) {
        db.delete(issued.TABLE, "${issued.COL_GRANTEE_ONION} = ?", arrayOf(granteeOnion))
    }
}
