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

        val signedRecord = currentSignedPreKey() ?: error("No signed prekey available")

        val preKeyId = selectId(
            "SELECT ${Schema.PreKeys.COL_ID} FROM ${Schema.PreKeys.TABLE} LIMIT 1",
        ) ?: error("No one-time prekey available")
        val preKeyRecord = store.loadPreKey(preKeyId)

        val oneTimeKyberId = selectId(
            "SELECT ${Schema.KyberPreKeys.COL_ID} FROM ${Schema.KyberPreKeys.TABLE} " +
                "WHERE ${Schema.KyberPreKeys.COL_LAST_RESORT} = 0 LIMIT 1",
        )
        val kyberRecord = if (oneTimeKyberId != null) {
            store.loadKyberPreKey(oneTimeKyberId)
        } else {
            currentLastResortKyberPreKey() ?: error("No kyber prekey available")
        }

        return PreKeyBundle(
            account.registrationId,
            CryptoConstants.DEVICE_ID,
            preKeyId,
            preKeyRecord.keyPair.publicKey,
            signedRecord.id,
            signedRecord.keyPair.publicKey,
            signedRecord.signature,
            account.identityKeyPair.publicKey,
            kyberRecord.id,
            kyberRecord.keyPair.publicKey,
            kyberRecord.signature,
        )
    }

    /**
     * Rotates the signed prekey and the last-resort Kyber prekey if either is due, then prunes
     * anything old enough that no in-flight handshake could still need it. Safe -- and cheap --
     * to call as often as the caller likes: everything here is a no-op unless a key has actually
     * aged past its rotation interval, so [app.niix.ConnectivityService] just calls it once on
     * every service start plus on a slow periodic timer rather than tracking rotation state
     * itself.
     */
    @Synchronized
    fun rotateKeysIfDue() {
        generateInitialKeysIfNeeded()
        rotateSignedPreKeyIfDue()
        rotateLastResortKyberPreKeyIfDue()
        pruneStaleSignedPreKeys()
        pruneStaleLastResortKyberPreKeys()
    }

    private fun rotateSignedPreKeyIfDue() {
        val now = System.currentTimeMillis()
        val current = currentSignedPreKey()
        if (current != null && now - current.timestamp < CryptoConstants.SIGNED_PREKEY_ROTATION_INTERVAL_MILLIS) {
            return
        }
        val account = identityManager.getOrCreate()
        val keyPair = ECKeyPair.generate()
        val id = uniqueSignedPreKeyId()
        val signature = account.identityKeyPair.privateKey.calculateSignature(keyPair.publicKey.serialize())
        store.storeSignedPreKey(id, SignedPreKeyRecord(id, now, keyPair, signature))
    }

    private fun rotateLastResortKyberPreKeyIfDue() {
        val now = System.currentTimeMillis()
        val current = currentLastResortKyberPreKey()
        if (current != null && now - current.timestamp < CryptoConstants.KYBER_LAST_RESORT_ROTATION_INTERVAL_MILLIS) {
            return
        }
        val account = identityManager.getOrCreate()
        val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val id = uniqueKyberPreKeyId()
        val signature = account.identityKeyPair.privateKey.calculateSignature(keyPair.publicKey.serialize())
        store.storeKyberPreKey(id, KyberPreKeyRecord(id, now, keyPair, signature), lastResort = true)
    }

    /** The most recently generated signed prekey (there may be older, not-yet-pruned ones
     * around too -- see [pruneStaleSignedPreKeys]), or null if none exists yet. */
    private fun currentSignedPreKey(): SignedPreKeyRecord? =
        store.loadSignedPreKeys().maxByOrNull { it.timestamp }

    /** The most recently generated last-resort Kyber prekey, or null if none exists yet. There
     * are normally only one or two of these at once (old + new during the overlap window), so
     * loading and comparing them in Kotlin rather than in SQL is cheap. */
    private fun currentLastResortKyberPreKey(): KyberPreKeyRecord? =
        lastResortKyberIds().map { store.loadKyberPreKey(it) }.maxByOrNull { it.timestamp }

    /** Deletes signed prekeys older than [CryptoConstants.SIGNED_PREKEY_RETENTION_MILLIS],
     * always keeping the current one even if (implausibly) it's already past that age. */
    private fun pruneStaleSignedPreKeys() {
        val now = System.currentTimeMillis()
        val current = currentSignedPreKey() ?: return
        store.loadSignedPreKeys().forEach { record ->
            if (record.id != current.id && now - record.timestamp > CryptoConstants.SIGNED_PREKEY_RETENTION_MILLIS) {
                store.removeSignedPreKey(record.id)
            }
        }
    }

    /** Same idea as [pruneStaleSignedPreKeys], but for last-resort Kyber prekeys -- these aren't
     * removed through [org.signal.libsignal.protocol.state.KyberPreKeyStore.markKyberPreKeyUsed]
     * (that only ever deletes one-time Kyber keys; a last-resort key is reusable by design), so
     * pruning them is this method's job alone. */
    private fun pruneStaleLastResortKyberPreKeys() {
        val now = System.currentTimeMillis()
        val currentId = currentLastResortKyberPreKey()?.id ?: return
        lastResortKyberIds().forEach { id ->
            if (id == currentId) return@forEach
            val record = store.loadKyberPreKey(id)
            if (now - record.timestamp > CryptoConstants.KYBER_LAST_RESORT_RETENTION_MILLIS) {
                secureDatabase.open().delete(
                    Schema.KyberPreKeys.TABLE,
                    "${Schema.KyberPreKeys.COL_ID} = ?",
                    arrayOf(id.toString()),
                )
            }
        }
    }

    private fun lastResortKyberIds(): List<Int> {
        val ids = mutableListOf<Int>()
        secureDatabase.open().rawQuery(
            "SELECT ${Schema.KyberPreKeys.COL_ID} FROM ${Schema.KyberPreKeys.TABLE} " +
                "WHERE ${Schema.KyberPreKeys.COL_LAST_RESORT} = 1",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) ids.add(cursor.getInt(0))
        }
        return ids
    }

    private fun uniqueSignedPreKeyId(): Int {
        var id = CryptoConstants.randomId()
        while (store.containsSignedPreKey(id)) id = CryptoConstants.randomId()
        return id
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
