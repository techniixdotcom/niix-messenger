package app.niix.core.storage

import android.content.ContentValues
import net.zetetic.database.sqlcipher.SQLiteDatabase

class BlocklistDao internal constructor(private val secureDatabase: SecureDatabase) {

    private val db: SQLiteDatabase get() = secureDatabase.open()
    private val t = Schema.Blocked

    fun block(onion: String) {
        val values = ContentValues().apply { put(t.COL_ONION, onion) }
        db.insertWithOnConflict(t.TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun unblock(onion: String) {
        db.delete(t.TABLE, "${t.COL_ONION} = ?", arrayOf(onion))
    }

    fun isBlocked(onion: String): Boolean {
        db.rawQuery("SELECT 1 FROM ${t.TABLE} WHERE ${t.COL_ONION} = ? LIMIT 1", arrayOf(onion)).use { c ->
            return c.moveToFirst()
        }
    }

    fun all(): List<String> {
        val result = mutableListOf<String>()
        db.rawQuery("SELECT ${t.COL_ONION} FROM ${t.TABLE}", emptyArray()).use { c ->
            while (c.moveToNext()) result.add(c.getString(0))
        }
        return result
    }
}
