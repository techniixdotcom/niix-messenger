package app.niix.core.storage

import android.content.Context
import net.zetetic.database.DatabaseErrorHandler
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook

class LockedException(message: String = "Database is locked") : IllegalStateException(message)

class SecureDatabase internal constructor(private val appContext: Context) {

    @Volatile
    private var database: SQLiteDatabase? = null

    fun isOpen(): Boolean = database != null

    fun open(): SQLiteDatabase = database ?: throw LockedException()

    internal fun openWith(passphrase: ByteArray): SQLiteDatabase {
        database?.let { return it }
        synchronized(this) {
            database?.let { return it }
            ensureNativeLoaded()
            val opened = openInternal(databaseFile(), passphrase)
            opened.execSQL("PRAGMA foreign_keys = ON;")
            createSchema(opened)
            database = opened
            return opened
        }
    }

    internal fun changePassphrase(newPassphrase: ByteArray) {
        val db = open()
        db.changePassword(newPassphrase)
    }

    fun close() {
        synchronized(this) {
            database?.close()
            database = null
        }
    }

    private fun openInternal(file: File, passphrase: ByteArray): SQLiteDatabase {
        // The single SQLCipher-version-sensitive call. Adjust the trailing
        // typed-null arguments if your pinned SQLCipher exposes a different
        // openOrCreateDatabase signature.
        return SQLiteDatabase.openOrCreateDatabase(
            file,
            passphrase,
            null as SQLiteDatabase.CursorFactory?,
            null as DatabaseErrorHandler?,
            null as SQLiteDatabaseHook?,
        )
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            Schema.DDL.forEach(db::execSQL)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun databaseFile(): File =
        File(appContext.noBackupFilesDir, Schema.DATABASE_FILENAME)

    companion object {
        private val nativeLoaded = AtomicBoolean(false)

        private fun ensureNativeLoaded() {
            if (nativeLoaded.compareAndSet(false, true)) {
                System.loadLibrary("sqlcipher")
            }
        }
    }
}
