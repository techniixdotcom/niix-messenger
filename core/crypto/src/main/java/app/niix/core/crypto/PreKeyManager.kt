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
        // Counts only keys not yet handed out: served ones wait to be used and are not available.
        if (countWhere(
                "SELECT COUNT(*) FROM ${Schema.PreKeys.TABLE} WHERE ${Schema.PreKeys.COL_SERVED_AT} IS NULL",
            ) < CryptoConstants.LOW_WATERMARK_ONE_TIME_PREKEYS
        ) {
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

        // Each one-time key is handed out once. It is marked as served, and never offered again
        // while unserved keys remain; its private half is kept until someone's first message
        // uses it. Handing the same key to several people meant only one of them could complete
        // a session with it, and the others' first messages failed to decrypt.
        //
        // Served keys that are never used would pile up without limit if bundle requests arrive
        // faster than conversations start, so above MAX_SERVED_UNUSED the most recent served key
        // is offered again instead. That trades strict one-time use for availability, and only
        // under a flood; normal use never gets near the cap.
        pruneStaleServedKeys()
        val now = System.currentTimeMillis()

        val preKeyId = takeOneTimeKey(
            Schema.PreKeys.TABLE, Schema.PreKeys.COL_ID, Schema.PreKeys.COL_SERVED_AT, "", now,
        ) { generateOneTimePreKeys(CryptoConstants.ONE_TIME_PREKEY_BATCH) }
            ?: error("No one-time prekey available")
        val preKeyRecord = store.loadPreKey(preKeyId)

        // Kyber one-time keys the same way. When none is available, the last-resort key below is
        // used, which libsignal supports.
        val oneTimeKyberId = takeOneTimeKey(
            Schema.KyberPreKeys.TABLE, Schema.KyberPreKeys.COL_ID, Schema.KyberPreKeys.COL_SERVED_AT,
            "${Schema.KyberPreKeys.COL_LAST_RESORT} = 0", now,
        ) {
            generateOneTimeKyberPreKeys(
                CryptoConstants.ONE_TIME_KYBER_PREKEY_BATCH,
                identityManager.getOrCreate().identityKeyPair.privateKey,
                now,
            )
        }
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

    private fun currentSignedPreKey(): SignedPreKeyRecord? =
        store.loadSignedPreKeys().maxByOrNull { it.timestamp }

    private fun currentLastResortKyberPreKey(): KyberPreKeyRecord? =
        lastResortKyberIds().map { store.loadKyberPreKey(it) }.maxByOrNull { it.timestamp }

    private fun pruneStaleSignedPreKeys() {
        val now = System.currentTimeMillis()
        val current = currentSignedPreKey() ?: return
        store.loadSignedPreKeys().forEach { record ->
            if (record.id != current.id && now - record.timestamp > CryptoConstants.SIGNED_PREKEY_RETENTION_MILLIS) {
                store.removeSignedPreKey(record.id)
            }
        }
    }

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

    /**
     * An unserved one-time key id, marked as served. Generates a fresh batch when none is left,
     * and past MAX_SERVED_UNUSED re-offers the most recently served key instead of creating more.
     */
    private fun takeOneTimeKey(
        table: String,
        idCol: String,
        servedCol: String,
        condition: String,
        now: Long,
        generateBatch: () -> Unit,
    ): Int? {
        val and = if (condition.isEmpty()) "" else " AND $condition"
        val where = if (condition.isEmpty()) "" else " WHERE $condition"
        val servedUnused = countWhere("SELECT COUNT(*) FROM $table WHERE $servedCol IS NOT NULL$and")
        if (servedUnused >= MAX_SERVED_UNUSED) {
            return selectId("SELECT $idCol FROM $table$where ORDER BY $servedCol DESC LIMIT 1", emptyArray())
        }
        val unservedSql = "SELECT $idCol FROM $table WHERE $servedCol IS NULL$and LIMIT 1"
        var id = selectId(unservedSql, emptyArray())
        if (id == null) {
            generateBatch()
            id = selectId(unservedSql, emptyArray())
        }
        if (id != null) {
            secureDatabase.open().execSQL(
                "UPDATE $table SET $servedCol = ? WHERE $idCol = ?",
                arrayOf<Any>(now, id),
            )
        }
        return id
    }

    /**
     * Deletes keys handed out long ago that were never used. A first message arriving that late
     * would fail anyway, and keeping them only grows the database.
     */
    private fun pruneStaleServedKeys() {
        val cutoff = System.currentTimeMillis() - SERVED_KEY_LIFETIME_MILLIS
        val db = secureDatabase.open()
        db.execSQL(
            "DELETE FROM ${Schema.PreKeys.TABLE} WHERE ${Schema.PreKeys.COL_SERVED_AT} < ?",
            arrayOf<Any>(cutoff),
        )
        db.execSQL(
            "DELETE FROM ${Schema.KyberPreKeys.TABLE} WHERE ${Schema.KyberPreKeys.COL_SERVED_AT} < ? " +
                "AND ${Schema.KyberPreKeys.COL_LAST_RESORT} = 0",
            arrayOf<Any>(cutoff),
        )
    }

    private fun selectId(sql: String, args: Array<String>): Int? {
        secureDatabase.open().rawQuery(sql, args).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getInt(0)
        }
    }


    private fun countOneTimeKyber(): Int =
        countWhere(
            "SELECT COUNT(*) FROM ${Schema.KyberPreKeys.TABLE} " +
                "WHERE ${Schema.KyberPreKeys.COL_LAST_RESORT} = 0 AND ${Schema.KyberPreKeys.COL_SERVED_AT} IS NULL",
        )

    private fun countWhere(sql: String): Int {
        secureDatabase.open().rawQuery(sql, emptyArray()).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private companion object {
        /** Past this many served-but-unused keys, one is re-offered instead of creating more. */
        const val MAX_SERVED_UNUSED = 500

        /** Served keys unused after this long are deleted. */
        const val SERVED_KEY_LIFETIME_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
