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

    /**
     * Opens the database with [key], the 32 bytes AppLockManager derives.
     *
     * The key goes to SQLCipher in raw form ("x'<hex>'"), so SQLCipher uses it as the encryption
     * key directly instead of running its own 256,000 rounds of PBKDF2 over it first. Those rounds
     * took as long as the Argon2 step and added nothing: the key is already random and 256 bits.
     */
    internal fun openWith(key: ByteArray): SQLiteDatabase {
        database?.let { return it }
        synchronized(this) {
            database?.let { return it }
            ensureNativeLoaded()
            // Before any open attempt, so an interrupted rekey is undone while the files are
            // untouched.
            recoverInterruptedRekey()
            val file = databaseFile()
            val raw = rawKey(key)
            try {
                val opened = try {
                    openInternal(file, raw)
                } catch (e: Exception) {
                    if (!file.exists()) throw e
                    openAndUpgrade(file, key, raw)
                }
                opened.execSQL("PRAGMA foreign_keys = ON;")
                createSchema(opened)
                database = opened
                return opened
            } finally {
                raw.fill(0)
            }
        }
    }

    /**
     * Databases from earlier versions took the key as a passphrase, with SQLCipher's PBKDF2 on
     * top. Opened that way once, then rekeyed to the raw key so every later open is fast.
     *
     * A wrong passcode ends up here too and fails a second time, which makes it a little slower.
     * The duress code takes the same path, so the two still take the same time.
     */
    private fun openAndUpgrade(file: File, key: ByteArray, raw: ByteArray): SQLiteDatabase {
        val db = openInternal(file, key)
        return try {
            rekey(db, raw)
            db
        } catch (_: Exception) {
            // rekey put the old file back and closed it. Open it the old way; the upgrade is tried
            // again next time.
            openInternal(file, key)
        }
    }

    internal fun rekeyWithBackup(newKey: ByteArray) {
        val raw = rawKey(newKey)
        try {
            rekey(open(), raw)
        } finally {
            raw.fill(0)
        }
    }

    private fun rekey(db: SQLiteDatabase, newRawKey: ByteArray) {
        val liveFile = databaseFile()
        val backups = fileSet(liveFile).associateWith { File(it.parentFile, it.name + BACKUP_SUFFIX) }

        runCatching { db.execSQL("PRAGMA wal_checkpoint(FULL)") }
        backups.values.forEach { it.delete() }
        for ((source, backup) in backups) {
            if (source.exists()) source.copyTo(backup, overwrite = true)
        }

        // Written once the backups exist, removed once the rekey is verified. The catch below
        // handles exceptions, but not the process dying (a crash, a kill, a pulled battery);
        // without the marker, the next rekey would delete the backups and with them the only copy
        // of the old database.
        runCatching { rekeyMarkerFile().writeBytes(byteArrayOf(1)) }

        try {
            db.changePassword(newRawKey)
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            // Marker first: once it's gone the backups aren't recovery data any more, so a crash in
            // between just leaves stale copies instead of restoring over a database that was
            // rekeyed fine.
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
    )

    fun close() {
        synchronized(this) {
            database?.close()
            database = null
        }
    }

    private fun openInternal(file: File, key: ByteArray): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(
            file,
            key,
            null as SQLiteDatabase.CursorFactory?,
            null as DatabaseErrorHandler?,
            null as SQLiteDatabaseHook?,
        )

    /**
     * Brings the schema up to date. Skipped when user_version already holds the fingerprint of
     * the current statements: they're all idempotent, but running them on every unlock meant a few
     * dozen statements and a thrown exception per already-added column.
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
                    // These ADD COLUMN statements run on every open, and "column already exists"
                    // just means they ran before. Anything else is a real failure and is rethrown
                    // so the transaction rolls back instead of leaving a half-migrated database.
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

    /**
     * SQLite reports an already-applied ADD COLUMN as "duplicate column name". Matched on the
     * message because SQLCipher doesn't expose a separate error code.
     */
    private fun isDuplicateColumnError(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: return false
        return message.contains("duplicate column")
    }

    private fun databaseFile(): File =
        File(appContext.noBackupFilesDir, Schema.DATABASE_FILENAME)

    private fun rekeyMarkerFile(): File =
        File(appContext.noBackupFilesDir, Schema.DATABASE_FILENAME + REKEY_MARKER_SUFFIX)

    /**
     * Rolls back a rekey that died partway.
     *
     * If the marker is there, we re-encrypted but never confirmed the new key works, so neither key
     * may open the database. Restoring the backups gets back to something the existing passcode
     * opens. Redoing a passcode change is annoying; an unopenable database is unrecoverable.
     *
     * Without the marker the rekey was verified, and any backups left over are stale copies from a
     * crash during cleanup. Restoring those would roll the database back to the old key under the
     * new salt and lock the user out, so they're deleted instead.
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
                    // No backup means the file didn't exist when the rekey started, so it shouldn't
                    // exist afterwards either.
                    source.delete()
                }
            }
        }
        runCatching { marker.delete() }
    }

    companion object {
        private const val BACKUP_SUFFIX = ".rekey-backup"
        private val HEX = "0123456789abcdef".toByteArray()

        /** Changes whenever any schema statement does. Never 0, which is what a new file has. */
        private val SCHEMA_FINGERPRINT: Int =
            (Schema.DDL + Schema.COLUMN_MIGRATIONS.map { it.second } + Schema.RETIRED_STATE)
                .joinToString("\n").hashCode().let { if (it == 0) 1 else it }

        /**
         * SQLCipher's raw key syntax, x'<64 hex digits>', as bytes so the caller can wipe it. A
         * String couldn't be.
         */
        private fun rawKey(key: ByteArray): ByteArray {
            require(key.size == 32) { "Raw SQLCipher keys are 32 bytes" }
            val out = ByteArray(3 + key.size * 2)
            out[0] = 'x'.code.toByte()
            out[1] = '\''.code.toByte()
            key.forEachIndexed { i, b ->
                out[2 + i * 2] = HEX[(b.toInt() shr 4) and 0xF]
                out[3 + i * 2] = HEX[b.toInt() and 0xF]
            }
            out[out.size - 1] = '\''.code.toByte()
            return out
        }

        /** Exists while a rekey has started but not been verified. See recoverInterruptedRekey. */
        private const val REKEY_MARKER_SUFFIX = ".rekey-inprogress"
        private val nativeLoaded = AtomicBoolean(false)

        private fun ensureNativeLoaded() {
            if (nativeLoaded.compareAndSet(false, true)) {
                System.loadLibrary("sqlcipher")
            }
        }
    }
}
