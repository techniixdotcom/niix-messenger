package app.niix.core.storage

import android.content.ContentValues
import net.zetetic.database.sqlcipher.SQLiteDatabase

/** A RelayGrant received *from* [issuerOnion] -- see [Schema.RelayGrantsReceived]. */
data class RelayGrantReceived(
    val issuerOnion: String,
    val issuerIdentityKey: ByteArray,
    val issuedAt: Long,
    val expiresAt: Long,
    val signature: ByteArray,
)

/** A RelayGrant this device has issued *to* [granteeOnion] -- see [Schema.RelayGrantsIssued]. */
data class RelayGrantIssued(
    val granteeOnion: String,
    val granteeIdentityKey: ByteArray,
    val issuedAt: Long,
    val expiresAt: Long,
)

/**
 * Persists the two RelayGrant tables item 11.1 of the relay build spec describes: certificates
 * this device has received from contacts (authorizing *this device* to relay-store for them) and
 * certificates this device has issued to contacts (tracked so the reissue-before-expiry check
 * doesn't need to re-derive anything). See [app.niix.core.relay.RelayManager] for how both are
 * used.
 */
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
            )
        }
    }

    fun deleteReceived(issuerOnion: String) {
        db.delete(received.TABLE, "${received.COL_ISSUER_ONION} = ?", arrayOf(issuerOnion))
    }

    fun upsertIssued(grant: RelayGrantIssued) {
        val values = ContentValues().apply {
            put(issued.COL_GRANTEE_ONION, grant.granteeOnion)
            put(issued.COL_GRANTEE_IDENTITY_KEY, grant.granteeIdentityKey)
            put(issued.COL_ISSUED_AT, grant.issuedAt)
            put(issued.COL_EXPIRES_AT, grant.expiresAt)
        }
        db.insertWithOnConflict(issued.TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
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
