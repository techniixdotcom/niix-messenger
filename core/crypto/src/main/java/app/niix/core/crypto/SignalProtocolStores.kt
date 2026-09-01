package app.niix.core.crypto

import android.content.ContentValues
import app.niix.core.storage.Schema
import app.niix.core.storage.SecureDatabase
import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

internal class DatabaseSignalProtocolStore(
    private val secureDatabase: SecureDatabase,
    private val identityManager: IdentityManager,
) : SignalProtocolStore {

    private val db: SQLiteDatabase get() = secureDatabase.open()

    override fun getIdentityKeyPair(): IdentityKeyPair =
        identityManager.getOrCreate().identityKeyPair

    override fun getLocalRegistrationId(): Int =
        identityManager.getOrCreate().registrationId

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
    ): IdentityKeyStore.IdentityChange {
        val existing = getIdentity(address)
        if (existing != null && existing == identityKey) {
            return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
        val values = ContentValues().apply {
            put(Schema.Identities.COL_NAME, address.name)
            put(Schema.Identities.COL_IDENTITY_KEY, identityKey.serialize())
            put(Schema.Identities.COL_TRUST_STATE, TrustStates.UNVERIFIED)
            put(Schema.Identities.COL_FIRST_SEEN, System.currentTimeMillis())
        }
        db.insertWithOnConflict(
            Schema.Identities.TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return if (existing != null) {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val stored = getIdentity(address) ?: return true
        return stored == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        db.rawQuery(
            "SELECT ${Schema.Identities.COL_IDENTITY_KEY} FROM ${Schema.Identities.TABLE} " +
                "WHERE ${Schema.Identities.COL_NAME} = ?",
            arrayOf(address.name),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return IdentityKey(cursor.getBlob(0), 0)
        }
    }

    fun findNameByIdentityKey(identityKeyBytes: ByteArray): String? {
        db.rawQuery(
            "SELECT ${Schema.Identities.COL_NAME}, ${Schema.Identities.COL_IDENTITY_KEY} FROM ${Schema.Identities.TABLE}",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getBlob(1).contentEquals(identityKeyBytes)) {
                    return cursor.getString(0)
                }
            }
        }
        return null
    }

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val blob = loadBlob(Schema.PreKeys.TABLE, Schema.PreKeys.COL_ID, Schema.PreKeys.COL_RECORD, preKeyId)
            ?: throw InvalidKeyIdException("No prekey with id $preKeyId")
        return PreKeyRecord(blob)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        upsertRecord(Schema.PreKeys.TABLE, Schema.PreKeys.COL_ID, Schema.PreKeys.COL_RECORD, preKeyId, record.serialize())
    }

    override fun containsPreKey(preKeyId: Int): Boolean =
        exists(Schema.PreKeys.TABLE, Schema.PreKeys.COL_ID, preKeyId)

    override fun removePreKey(preKeyId: Int) {
        db.delete(Schema.PreKeys.TABLE, "${Schema.PreKeys.COL_ID} = ?", arrayOf(preKeyId.toString()))
    }

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val blob = loadBlob(
            Schema.SignedPreKeys.TABLE,
            Schema.SignedPreKeys.COL_ID,
            Schema.SignedPreKeys.COL_RECORD,
            signedPreKeyId,
        ) ?: throw InvalidKeyIdException("No signed prekey with id $signedPreKeyId")
        return SignedPreKeyRecord(blob)
    }

    override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> {
        val result = mutableListOf<SignedPreKeyRecord>()
        db.rawQuery(
            "SELECT ${Schema.SignedPreKeys.COL_RECORD} FROM ${Schema.SignedPreKeys.TABLE}",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(SignedPreKeyRecord(cursor.getBlob(0)))
            }
        }
        return result
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        upsertRecord(
            Schema.SignedPreKeys.TABLE,
            Schema.SignedPreKeys.COL_ID,
            Schema.SignedPreKeys.COL_RECORD,
            signedPreKeyId,
            record.serialize(),
        )
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        exists(Schema.SignedPreKeys.TABLE, Schema.SignedPreKeys.COL_ID, signedPreKeyId)

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        db.delete(
            Schema.SignedPreKeys.TABLE,
            "${Schema.SignedPreKeys.COL_ID} = ?",
            arrayOf(signedPreKeyId.toString()),
        )
    }

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val blob = loadBlob(
            Schema.KyberPreKeys.TABLE,
            Schema.KyberPreKeys.COL_ID,
            Schema.KyberPreKeys.COL_RECORD,
            kyberPreKeyId,
        ) ?: throw InvalidKeyIdException("No kyber prekey with id $kyberPreKeyId")
        return KyberPreKeyRecord(blob)
    }

    override fun loadKyberPreKeys(): MutableList<KyberPreKeyRecord> {
        val result = mutableListOf<KyberPreKeyRecord>()
        db.rawQuery(
            "SELECT ${Schema.KyberPreKeys.COL_RECORD} FROM ${Schema.KyberPreKeys.TABLE}",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(KyberPreKeyRecord(cursor.getBlob(0)))
            }
        }
        return result
    }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        storeKyberPreKey(kyberPreKeyId, record, lastResort = false)
    }

    fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord, lastResort: Boolean) {
        val values = ContentValues().apply {
            put(Schema.KyberPreKeys.COL_ID, kyberPreKeyId)
            put(Schema.KyberPreKeys.COL_RECORD, record.serialize())
            put(Schema.KyberPreKeys.COL_LAST_RESORT, if (lastResort) 1 else 0)
        }
        db.insertWithOnConflict(
            Schema.KyberPreKeys.TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        exists(Schema.KyberPreKeys.TABLE, Schema.KyberPreKeys.COL_ID, kyberPreKeyId)

    override fun markKyberPreKeyUsed(
        kyberPreKeyId: Int,
        signedPreKeyId: Int,
        baseKey: ECPublicKey,
    ) {
        if (isLastResort(kyberPreKeyId)) {
            recordLastResortUse(kyberPreKeyId, signedPreKeyId, baseKey.serialize())
        } else {
            db.delete(
                Schema.KyberPreKeys.TABLE,
                "${Schema.KyberPreKeys.COL_ID} = ?",
                arrayOf(kyberPreKeyId.toString()),
            )
        }
    }

    private fun isLastResort(kyberPreKeyId: Int): Boolean {
        db.rawQuery(
            "SELECT ${Schema.KyberPreKeys.COL_LAST_RESORT} FROM ${Schema.KyberPreKeys.TABLE} " +
                "WHERE ${Schema.KyberPreKeys.COL_ID} = ?",
            arrayOf(kyberPreKeyId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return false
            return cursor.getInt(0) == 1
        }
    }

    private fun recordLastResortUse(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ByteArray) {
        db.rawQuery(
            "SELECT ${Schema.KyberUsedBaseKeys.COL_BASE_KEY} FROM ${Schema.KyberUsedBaseKeys.TABLE} " +
                "WHERE ${Schema.KyberUsedBaseKeys.COL_KYBER_ID} = ? AND " +
                "${Schema.KyberUsedBaseKeys.COL_SIGNED_ID} = ?",
            arrayOf(kyberPreKeyId.toString(), signedPreKeyId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getBlob(0).contentEquals(baseKey)) {
                    throw ReusedBaseKeyException("Kyber last-resort base key reused")
                }
            }
        }
        val values = ContentValues().apply {
            put(Schema.KyberUsedBaseKeys.COL_KYBER_ID, kyberPreKeyId)
            put(Schema.KyberUsedBaseKeys.COL_SIGNED_ID, signedPreKeyId)
            put(Schema.KyberUsedBaseKeys.COL_BASE_KEY, baseKey)
        }
        db.insertWithOnConflict(
            Schema.KyberUsedBaseKeys.TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val blob = loadSessionBlob(address) ?: return SessionRecord()
        return SessionRecord(blob)
    }

    override fun loadExistingSessions(
        addresses: MutableList<SignalProtocolAddress>,
    ): MutableList<SessionRecord> {
        val result = mutableListOf<SessionRecord>()
        for (address in addresses) {
            val blob = loadSessionBlob(address)
                ?: throw NoSessionException("No session for ${address.name}.${address.deviceId}")
            result.add(SessionRecord(blob))
        }
        return result
    }

    override fun getSubDeviceSessions(name: String): MutableList<Int> {
        val result = mutableListOf<Int>()
        db.rawQuery(
            "SELECT ${Schema.Sessions.COL_DEVICE_ID} FROM ${Schema.Sessions.TABLE} " +
                "WHERE ${Schema.Sessions.COL_NAME} = ? AND ${Schema.Sessions.COL_DEVICE_ID} != ?",
            arrayOf(name, CryptoConstants.DEVICE_ID.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(cursor.getInt(0))
            }
        }
        return result
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        val values = ContentValues().apply {
            put(Schema.Sessions.COL_NAME, address.name)
            put(Schema.Sessions.COL_DEVICE_ID, address.deviceId)
            put(Schema.Sessions.COL_RECORD, record.serialize())
        }
        db.insertWithOnConflict(
            Schema.Sessions.TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        loadSessionBlob(address) != null

    override fun deleteSession(address: SignalProtocolAddress) {
        db.delete(
            Schema.Sessions.TABLE,
            "${Schema.Sessions.COL_NAME} = ? AND ${Schema.Sessions.COL_DEVICE_ID} = ?",
            arrayOf(address.name, address.deviceId.toString()),
        )
    }

    override fun deleteAllSessions(name: String) {
        db.delete(Schema.Sessions.TABLE, "${Schema.Sessions.COL_NAME} = ?", arrayOf(name))
    }

    private fun loadSessionBlob(address: SignalProtocolAddress): ByteArray? {
        db.rawQuery(
            "SELECT ${Schema.Sessions.COL_RECORD} FROM ${Schema.Sessions.TABLE} " +
                "WHERE ${Schema.Sessions.COL_NAME} = ? AND ${Schema.Sessions.COL_DEVICE_ID} = ?",
            arrayOf(address.name, address.deviceId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getBlob(0)
        }
    }

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord,
    ) {
        val values = ContentValues().apply {
            put(Schema.GroupSenderKeys.COL_SENDER_NAME, sender.name)
            put(Schema.GroupSenderKeys.COL_SENDER_DEVICE_ID, sender.deviceId)
            put(Schema.GroupSenderKeys.COL_DISTRIBUTION_ID, distributionId.toString())
            put(Schema.GroupSenderKeys.COL_RECORD, record.serialize())
        }
        db.insertWithOnConflict(
            Schema.GroupSenderKeys.TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun loadSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
    ): SenderKeyRecord? {
        db.rawQuery(
            "SELECT ${Schema.GroupSenderKeys.COL_RECORD} FROM ${Schema.GroupSenderKeys.TABLE} " +
                "WHERE ${Schema.GroupSenderKeys.COL_SENDER_NAME} = ? AND " +
                "${Schema.GroupSenderKeys.COL_SENDER_DEVICE_ID} = ? AND " +
                "${Schema.GroupSenderKeys.COL_DISTRIBUTION_ID} = ?",
            arrayOf(sender.name, sender.deviceId.toString(), distributionId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return SenderKeyRecord(cursor.getBlob(0))
        }
    }

    fun deleteSenderKey(sender: SignalProtocolAddress, distributionId: UUID) {
        db.delete(
            Schema.GroupSenderKeys.TABLE,
            "${Schema.GroupSenderKeys.COL_SENDER_NAME} = ? AND " +
                "${Schema.GroupSenderKeys.COL_SENDER_DEVICE_ID} = ? AND " +
                "${Schema.GroupSenderKeys.COL_DISTRIBUTION_ID} = ?",
            arrayOf(sender.name, sender.deviceId.toString(), distributionId.toString()),
        )
    }

    private fun loadBlob(table: String, idColumn: String, blobColumn: String, id: Int): ByteArray? {
        db.rawQuery(
            "SELECT $blobColumn FROM $table WHERE $idColumn = ?",
            arrayOf(id.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getBlob(0)
        }
    }

    private fun upsertRecord(table: String, idColumn: String, blobColumn: String, id: Int, blob: ByteArray) {
        val values = ContentValues().apply {
            put(idColumn, id)
            put(blobColumn, blob)
        }
        db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun exists(table: String, idColumn: String, id: Int): Boolean {
        db.rawQuery(
            "SELECT 1 FROM $table WHERE $idColumn = ? LIMIT 1",
            arrayOf(id.toString()),
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private object TrustStates {
        const val UNVERIFIED = "UNVERIFIED"
    }
}
