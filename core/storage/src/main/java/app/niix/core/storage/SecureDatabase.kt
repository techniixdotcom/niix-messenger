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

    internal fun rekeyWithBackup(newPassphrase: ByteArray) {
        val db = open()
        val liveFile = databaseFile()
        val backups = fileSet(liveFile).associateWith { File(it.parentFile, it.name + BACKUP_SUFFIX) }

        runCatching { db.execSQL("PRAGMA wal_checkpoint(FULL)") }
        backups.values.forEach { it.delete() }
        for ((source, backup) in backups) {
            if (source.exists()) source.copyTo(backup, overwrite = true)
        }

        try {
            db.changePassword(newPassphrase)
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            backups.values.forEach { it.delete() }
        } catch (e: Exception) {
            runCatching { db.close() }
            database = null
            for ((source, backup) in backups) {
                if (backup.exists()) backup.copyTo(source, overwrite = true) else source.delete()
            }
            backups.values.forEach { it.delete() }
            throw e
        }
    }

    internal fun recoverFromInterruptedRekey() {
        val liveFile = databaseFile()
        val backups = fileSet(liveFile).associateWith { File(it.parentFile, it.name + BACKUP_SUFFIX) }
        if (backups.values.none { it.exists() }) return
        for ((source, backup) in backups) {
            if (backup.exists()) backup.copyTo(source, overwrite = true)
        }
        backups.values.forEach { it.delete() }
    }

    private fun fileSet(main: File): List<File> = listOf(
        main,
        File(main.parentFile, main.name + "-wal"),
        File(main.parentFile, main.name + "-shm"),
    )

    fun close() {
        synchronized(this) {
            database?.close()
            database = null
        }
    }

    private fun openInternal(file: File, passphrase: ByteArray): SQLiteDatabase {

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
            Schema.COLUMN_MIGRATIONS.forEach { (_, statement) ->
                runCatching { db.execSQL(statement) }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun databaseFile(): File =
        File(appContext.noBackupFilesDir, Schema.DATABASE_FILENAME)

    companion object {
        private const val BACKUP_SUFFIX = ".rekey-backup"
        private val nativeLoaded = AtomicBoolean(false)

        private fun ensureNativeLoaded() {
            if (nativeLoaded.compareAndSet(false, true)) {
                System.loadLibrary("sqlcipher")
            }
        }
    }
}
