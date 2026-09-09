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

    private fun load(): LocalAccount? {
        val db = secureDatabase.open()
        db.rawQuery(
            "SELECT ${Schema.Account.COL_IDENTITY_KEYPAIR}, ${Schema.Account.COL_REGISTRATION_ID} " +
                "FROM ${Schema.Account.TABLE} WHERE ${Schema.Account.COL_ID} = ?",
            arrayOf(Schema.Account.SINGLETON_ID.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val keyPairBytes = cursor.getBlob(0)
            val registrationId = cursor.getInt(1)
            return LocalAccount(IdentityKeyPair(keyPairBytes), registrationId)
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
        return LocalAccount(identityKeyPair, registrationId)
    }
}

internal data class LocalAccount(
    val identityKeyPair: IdentityKeyPair,
    val registrationId: Int,
)
