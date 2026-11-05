package app.niix.core.crypto

import android.content.ContentValues
import app.niix.core.storage.Schema
import app.niix.core.storage.SecureDatabase
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.util.KeyHelper

internal class IdentityManager(private val secureDatabase: SecureDatabase) {

    @Volatile
    private var cached: LocalAccount? = null

    fun getOrCreate(): LocalAccount {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val loaded = load() ?: generateAndPersist()
            cached = loaded
            return loaded
        }
    }

    fun reset() {
        synchronized(this) {
            cached = null
        }
    }

    /**
     * Makes this device another device of an existing account: its identity key replaces ours and
     * [deviceId] becomes our number within the account. Everything made with the old identity goes,
     * since prekeys signed by it and sessions built on it are useless now. The registration id stays
     * our own; it identifies this install, not the account.
     */
    fun adopt(identityKeyPair: IdentityKeyPair, deviceId: Int) {
        synchronized(this) {
            val db = secureDatabase.open()
            val registrationId = load()?.registrationId ?: KeyHelper.generateRegistrationId(false)
            secureDatabase.inTransaction {
                listOf(
                    Schema.PreKeys.TABLE, Schema.SignedPreKeys.TABLE, Schema.KyberPreKeys.TABLE,
                    Schema.KyberUsedBaseKeys.TABLE, Schema.Sessions.TABLE, Schema.Identities.TABLE,
                    Schema.Account.TABLE,
                ).forEach { db.delete(it, null, null) }
                db.insert(Schema.Account.TABLE, null, ContentValues().apply {
                    put(Schema.Account.COL_ID, Schema.Account.SINGLETON_ID)
                    put(Schema.Account.COL_IDENTITY_KEYPAIR, identityKeyPair.serialize())
                    put(Schema.Account.COL_REGISTRATION_ID, registrationId)
                    put(Schema.Account.COL_DEVICE_ID, deviceId)
                })
            }
            cached = null
        }
    }

    private fun load(): LocalAccount? {
        val db = secureDatabase.open()
        db.rawQuery(
            "SELECT ${Schema.Account.COL_IDENTITY_KEYPAIR}, ${Schema.Account.COL_REGISTRATION_ID}, " +
                "${Schema.Account.COL_DEVICE_ID} FROM ${Schema.Account.TABLE} WHERE ${Schema.Account.COL_ID} = ?",
            arrayOf(Schema.Account.SINGLETON_ID.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return LocalAccount(IdentityKeyPair(cursor.getBlob(0)), cursor.getInt(1), cursor.getInt(2))
        }
    }

    private fun generateAndPersist(): LocalAccount {
        val ecKeyPair = ECKeyPair.generate()
        val identityKey = IdentityKey(ecKeyPair.publicKey)
        val identityKeyPair = IdentityKeyPair(identityKey, ecKeyPair.privateKey)
        val registrationId = KeyHelper.generateRegistrationId(false)

        val db = secureDatabase.open()
        val values = ContentValues().apply {
            put(Schema.Account.COL_ID, Schema.Account.SINGLETON_ID)
            put(Schema.Account.COL_IDENTITY_KEYPAIR, identityKeyPair.serialize())
            put(Schema.Account.COL_REGISTRATION_ID, registrationId)
        }
        db.insert(Schema.Account.TABLE, null, values)
        return LocalAccount(identityKeyPair, registrationId, CryptoConstants.FIRST_DEVICE_ID)
    }
}

internal data class LocalAccount(
    val identityKeyPair: IdentityKeyPair,
    val registrationId: Int,
    val deviceId: Int,
)
