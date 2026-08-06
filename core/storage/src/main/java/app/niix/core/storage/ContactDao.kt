package app.niix.core.storage

import android.content.ContentValues
import app.niix.core.model.Contact
import app.niix.core.model.IdentityFingerprint
import app.niix.core.model.OnionAddress
import app.niix.core.model.TrustState
import net.zetetic.database.sqlcipher.SQLiteDatabase

class ContactDao internal constructor(private val secureDatabase: SecureDatabase) {

    private val db: SQLiteDatabase get() = secureDatabase.open()
    private val t = Schema.Contacts

    fun upsert(contact: Contact) {
        val values = ContentValues().apply {
            put(t.COL_ONION, contact.onionAddress.value)
            put(t.COL_DISPLAY_NAME, contact.displayName)
            put(t.COL_FINGERPRINT, contact.fingerprint.displayable)
            put(t.COL_TRUST_STATE, contact.trustState.name)
            put(t.COL_ADDED_AT, contact.addedAtEpochMillis)
        }
        db.insertWithOnConflict(t.TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun get(onion: String): Contact? {
        db.rawQuery("SELECT * FROM ${t.TABLE} WHERE ${t.COL_ONION} = ?", arrayOf(onion)).use { c ->
            return if (c.moveToFirst()) c.toContact() else null
        }
    }

    /** All saved contacts, alphabetical by display name. Independent of conversations -- a
     * contact stays here even after its conversation is deleted. */
    fun list(): List<Contact> {
        val result = mutableListOf<Contact>()
        db.rawQuery(
            "SELECT * FROM ${t.TABLE} ORDER BY ${t.COL_DISPLAY_NAME} COLLATE NOCASE ASC",
            emptyArray(),
        ).use { c ->
            while (c.moveToNext()) result.add(c.toContact())
        }
        return result
    }

    fun isKnown(onion: String): Boolean {
        db.rawQuery("SELECT 1 FROM ${t.TABLE} WHERE ${t.COL_ONION} = ? LIMIT 1", arrayOf(onion)).use { c ->
            return c.moveToFirst()
        }
    }

    fun setTrustState(onion: String, trustState: TrustState) {
        val values = ContentValues().apply { put(t.COL_TRUST_STATE, trustState.name) }
        db.update(t.TABLE, values, "${t.COL_ONION} = ?", arrayOf(onion))
    }

    private fun android.database.Cursor.toContact(): Contact = Contact(
        onionAddress = OnionAddress.parse(getString(getColumnIndexOrThrow(t.COL_ONION))),
        displayName = getString(getColumnIndexOrThrow(t.COL_DISPLAY_NAME)),
        fingerprint = IdentityFingerprint(getString(getColumnIndexOrThrow(t.COL_FINGERPRINT))),
        trustState = TrustState.valueOf(getString(getColumnIndexOrThrow(t.COL_TRUST_STATE))),
        addedAtEpochMillis = getLong(getColumnIndexOrThrow(t.COL_ADDED_AT)),
    )
}
