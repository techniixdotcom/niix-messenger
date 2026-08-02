package app.niix.core.crypto

import app.niix.core.storage.Schema
import app.niix.core.storage.SecureDatabase
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

internal class PreKeyManager(
    private val store: DatabaseSignalProtocolStore,
    private val identityManager: IdentityManager,
    private val secureDatabase: SecureDatabase,
) {

    @Synchronized
    fun generateInitialKeysIfNeeded() {
        if (store.loadSignedPreKeys().isNotEmpty()) return

        val account = identityManager.getOrCreate()
        val identityPrivate = account.identityKeyPair.privateKey
        val now = System.currentTimeMillis()

        val signedKeyPair = ECKeyPair.generate()
        val signedId = CryptoConstants.randomId()
        val signedSignature = identityPrivate.calculateSignature(signedKeyPair.publicKey.serialize())
        store.storeSignedPreKey(signedId, SignedPreKeyRecord(signedId, now, signedKeyPair, signedSignature))

        generateOneTimePreKeys(CryptoConstants.ONE_TIME_PREKEY_BATCH)
        generateOneTimeKyberPreKeys(CryptoConstants.ONE_TIME_KYBER_PREKEY_BATCH, identityPrivate, now)

        val lastResortKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val lastResortId = CryptoConstants.randomId()
        val lastResortSignature =
            identityPrivate.calculateSignature(lastResortKeyPair.publicKey.serialize())
        store.storeKyberPreKey(
            lastResortId,
            KyberPreKeyRecord(lastResortId, now, lastResortKeyPair, lastResortSignature),
            lastResort = true,
        )
    }

    @Synchronized
    fun replenishOneTimeKeysIfLow() {
        if (count(Schema.PreKeys.TABLE) < CryptoConstants.LOW_WATERMARK_ONE_TIME_PREKEYS) {
            generateOneTimePreKeys(CryptoConstants.ONE_TIME_PREKEY_BATCH)
        }
        val account = identityManager.getOrCreate()
        if (countOneTimeKyber() < CryptoConstants.LOW_WATERMARK_KYBER_PREKEYS) {
            generateOneTimeKyberPreKeys(
                CryptoConstants.ONE_TIME_KYBER_PREKEY_BATCH,
                account.identityKeyPair.privateKey,
                System.currentTimeMillis(),
            )
        }
    }

    @Synchronized
    fun createLocalBundle(): PreKeyBundle {
        generateInitialKeysIfNeeded()
        val account = identityManager.getOrCreate()

        val signedId = store.loadSignedPreKeys().first().id
        val signedRecord = store.loadSignedPreKey(signedId)

        val preKeyId = selectId(
            "SELECT ${Schema.PreKeys.COL_ID} FROM ${Schema.PreKeys.TABLE} LIMIT 1",
        ) ?: error("No one-time prekey available")
        val preKeyRecord = store.loadPreKey(preKeyId)

        val kyberId = selectId(
            "SELECT ${Schema.KyberPreKeys.COL_ID} FROM ${Schema.KyberPreKeys.TABLE} " +
                "WHERE ${Schema.KyberPreKeys.COL_LAST_RESORT} = 0 LIMIT 1",
        ) ?: selectId(
            "SELECT ${Schema.KyberPreKeys.COL_ID} FROM ${Schema.KyberPreKeys.TABLE} " +
                "WHERE ${Schema.KyberPreKeys.COL_LAST_RESORT} = 1 LIMIT 1",
        ) ?: error("No kyber prekey available")
        val kyberRecord = store.loadKyberPreKey(kyberId)

        return PreKeyBundle(
            account.registrationId,
            CryptoConstants.DEVICE_ID,
            preKeyId,
            preKeyRecord.keyPair.publicKey,
            signedId,
            signedRecord.keyPair.publicKey,
            signedRecord.signature,
            account.identityKeyPair.publicKey,
            kyberId,
            kyberRecord.keyPair.publicKey,
            kyberRecord.signature,
        )
    }

    private fun generateOneTimePreKeys(count: Int) {
        repeat(count) {
            val id = uniquePreKeyId()
            store.storePreKey(id, PreKeyRecord(id, ECKeyPair.generate()))
        }
    }

    private fun generateOneTimeKyberPreKeys(count: Int, identityPrivate: org.signal.libsignal.protocol.ecc.ECPrivateKey, now: Long) {
        repeat(count) {
            val id = uniqueKyberPreKeyId()
            val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val signature = identityPrivate.calculateSignature(keyPair.publicKey.serialize())
            store.storeKyberPreKey(id, KyberPreKeyRecord(id, now, keyPair, signature))
        }
    }

    private fun uniquePreKeyId(): Int {
        var id = CryptoConstants.randomId()
        while (store.containsPreKey(id)) id = CryptoConstants.randomId()
        return id
    }

    private fun uniqueKyberPreKeyId(): Int {
        var id = CryptoConstants.randomId()
        while (store.containsKyberPreKey(id)) id = CryptoConstants.randomId()
        return id
    }

    private fun selectId(sql: String): Int? {
        secureDatabase.open().rawQuery(sql, emptyArray()).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getInt(0)
        }
    }

    private fun count(table: String): Int = countWhere("SELECT COUNT(*) FROM $table")

    private fun countOneTimeKyber(): Int =
        countWhere(
            "SELECT COUNT(*) FROM ${Schema.KyberPreKeys.TABLE} " +
                "WHERE ${Schema.KyberPreKeys.COL_LAST_RESORT} = 0",
        )

    private fun countWhere(sql: String): Int {
        secureDatabase.open().rawQuery(sql, emptyArray()).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }
}
