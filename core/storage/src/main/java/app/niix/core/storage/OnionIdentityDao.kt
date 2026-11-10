package app.niix.core.storage

import android.content.ContentValues
import java.security.SecureRandom

class OnionIdentityDao internal constructor(private val secureDatabase: SecureDatabase) {

    fun getOrCreate(): ByteArray {
        load()?.let { return it }
        synchronized(this) {
            load()?.let { return it }
            val seed = ByteArray(SEED_BYTES).also { SecureRandom().nextBytes(it) }
            val db = secureDatabase.open()
            val values = ContentValues().apply {
                put(Schema.OnionIdentity.COL_ID, Schema.OnionIdentity.SINGLETON_ID)
                put(Schema.OnionIdentity.COL_PRIVATE_KEY, seed)
            }
            db.insert(Schema.OnionIdentity.TABLE, null, values)
            return seed
        }
    }

    private fun load(): ByteArray? {
        val db = secureDatabase.open()
        db.rawQuery(
            "SELECT ${Schema.OnionIdentity.COL_PRIVATE_KEY} FROM ${Schema.OnionIdentity.TABLE} " +
                "WHERE ${Schema.OnionIdentity.COL_ID} = ?",
            arrayOf(Schema.OnionIdentity.SINGLETON_ID.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getBlob(0)
        }
    }

    companion object {
        const val SEED_BYTES = 32
    }
}
