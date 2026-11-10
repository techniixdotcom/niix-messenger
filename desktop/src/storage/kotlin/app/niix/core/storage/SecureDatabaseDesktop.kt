package app.niix.core.storage

import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase

class LockedException(message: String = "Database is locked") : IllegalStateException(message)

/**
 * The desktop version of SecureDatabase: the same SQLCipher 4 database, schema and rekey recovery,
 * opened through SQLite3 Multiple Ciphers instead of SQLCipher for Android. See SQLiteDatabase in
 * the desktop compat sources.
 */
class SecureDatabase internal constructor(private val directory: File) {

    @Volatile
    private var database: SQLiteDatabase? = null

    fun isOpen(): Boolean = database != null

    fun open(): SQLiteDatabase = database ?: throw LockedException()

    /** Runs [block] as one transaction: one disk sync for the lot instead of one per write. */
    fun <T> inTransaction(block: () -> T): T {
        val db = open()
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    /** Opens the database with [key], the 32 bytes AppLockManager derives, used as SQLCipher's raw key. */
    internal fun openWith(key: ByteArray): SQLiteDatabase {
        database?.let { return it }
        synchronized(this) {
            database?.let { return it }
            require(key.size == 32) { "Raw SQLCipher keys are 32 bytes" }
            recoverInterruptedRekey()
            val file = databaseFile()
            val opened = SQLiteDatabase.openWithRawKey(file, key)
            try {
                StoragePaths.makePrivate(file)
                opened.execSQL("PRAGMA foreign_keys = ON;")
                createSchema(opened)
            } catch (e: Exception) {
                runCatching { opened.close() }
                throw e
            }
            database = opened
            return opened
        }
    }

    internal fun rekeyWithBackup(newKey: ByteArray) {
        require(newKey.size == 32) { "Raw SQLCipher keys are 32 bytes" }
        rekey(open(), newKey)
    }

    private fun rekey(db: SQLiteDatabase, newKey: ByteArray) {
        val liveFile = databaseFile()
        val backups = fileSet(liveFile).associateWith { File(it.parentFile, it.name + BACKUP_SUFFIX) }

        backups.values.forEach { it.delete() }
        for ((source, backup) in backups) {
            if (source.exists()) {
                source.copyTo(backup, overwrite = true)
                StoragePaths.makePrivate(backup)
            }
        }

        // Written once the backups exist, removed once the rekey is verified. Without it, a crash
        // in between would let the next rekey delete the only copy of the old database.
        runCatching { rekeyMarkerFile().writeBytes(byteArrayOf(1)) }

        try {
            db.rekey(newKey)
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            runCatching { rekeyMarkerFile().delete() }
            backups.values.forEach { it.delete() }
        } catch (e: Exception) {
            runCatching { db.close() }
            database = null
            for ((source, backup) in backups) {
                if (backup.exists()) backup.copyTo(source, overwrite = true) else source.delete()
            }
            runCatching { rekeyMarkerFile().delete() }
            backups.values.forEach { it.delete() }
            throw e
        }
    }

    private fun fileSet(main: File): List<File> = listOf(
        main,
        File(main.parentFile, main.name + "-wal"),
        File(main.parentFile, main.name + "-shm"),
        File(main.parentFile, main.name + "-journal"),
    )

    fun close() {
        synchronized(this) {
            database?.close()
            database = null
        }
    }

    /**
     * Brings the schema up to date. Skipped when user_version already holds the fingerprint of the
     * current statements.
     */
    private fun createSchema(db: SQLiteDatabase) {
        if (db.version == SCHEMA_FINGERPRINT) return
        db.beginTransaction()
        try {
            Schema.DDL.forEach(db::execSQL)
            Schema.COLUMN_MIGRATIONS.forEach { (_, statement) ->
                try {
                    db.execSQL(statement)
                } catch (e: Exception) {
                    if (!isDuplicateColumnError(e)) throw e
                }
            }
            Schema.RETIRED_STATE.forEach(db::execSQL)
            db.version = SCHEMA_FINGERPRINT
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun isDuplicateColumnError(e: Exception): Boolean {
        val message = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ").lowercase()
        return message.contains("duplicate column")
    }

    private fun databaseFile(): File = File(directory, Schema.DATABASE_FILENAME)

    private fun rekeyMarkerFile(): File = File(directory, Schema.DATABASE_FILENAME + REKEY_MARKER_SUFFIX)

    /**
     * Rolls back a rekey that died partway, or deletes stale backups left by one that finished.
     * Same rules as on Android: with the marker the backups are restored, without it they're stale.
     */
    private fun recoverInterruptedRekey() {
        val marker = rekeyMarkerFile()
        if (!marker.exists()) {
            fileSet(databaseFile()).forEach { runCatching { File(it.parentFile, it.name + BACKUP_SUFFIX).delete() } }
            return
        }
        runCatching {
            val live = databaseFile()
            for (source in fileSet(live)) {
                val backup = File(source.parentFile, source.name + BACKUP_SUFFIX)
                if (backup.exists()) {
                    backup.copyTo(source, overwrite = true)
                    backup.delete()
                } else {
                    source.delete()
                }
            }
        }
        runCatching { marker.delete() }
    }

    companion object {
        private const val BACKUP_SUFFIX = ".rekey-backup"
        private const val REKEY_MARKER_SUFFIX = ".rekey-inprogress"

        /** Changes whenever any schema statement does. Never 0, which is what a new file has. */
        private val SCHEMA_FINGERPRINT: Int =
            (Schema.DDL + Schema.COLUMN_MIGRATIONS.map { it.second } + Schema.RETIRED_STATE)
                .joinToString("\n").hashCode().let { if (it == 0) 1 else it }
    }
}
