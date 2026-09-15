package app.niix.core.storage

import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.nio.CharBuffer
import java.security.SecureRandom

class BackupException(message: String) : IllegalStateException(message)

class EncryptedBackup internal constructor(
    private val secureDatabase: SecureDatabase,
    private val attachmentCipher: AttachmentCipher,
    private val tempDir: File,
) {

    private fun deleteWithSidecars(file: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            runCatching { File(file.absolutePath + suffix).delete() }
        }
    }

    private fun purgeStaleTempFiles() {
        runCatching {
            tempDir.listFiles()
                ?.filter { it.name.startsWith("export-") || it.name.startsWith("import-") }
                ?.forEach { it.delete() }
        }
    }

    fun export(passphrase: CharArray, destination: File) {
        purgeStaleTempFiles()
        val db = secureDatabase.open()
        val plain = File(tempDir, "export-${System.nanoTime()}.tmp")
        deleteWithSidecars(plain)
        val ephemeralKey = ByteArray(EPHEMERAL_KEY_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            db.execSQL(
                "ATTACH DATABASE ? AS plaintext KEY \"x'${hex(ephemeralKey)}'\"",
                arrayOf<Any>(plain.absolutePath),
            )
            db.rawQuery("SELECT sqlcipher_export('plaintext')", emptyArray()).use { it.moveToFirst() }
            db.execSQL("DETACH DATABASE plaintext")

            val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val header = buildHeader(salt)
            val key = deriveKey(passphrase, salt)
            try {
                destination.outputStream().use { rawOut ->
                    rawOut.write(header)

                    val combined: InputStream = SequenceInputStream(ByteArrayInputStream(ephemeralKey), plain.inputStream())
                    attachmentCipher.encrypt(key, combined, rawOut, associatedData = header)
                }
            } finally {
                key.fill(0)
            }
        } finally {
            ephemeralKey.fill(0)
            deleteWithSidecars(plain)
        }
    }

    fun import(passphrase: CharArray, source: File) {
        purgeStaleTempFiles()
        val plain = File(tempDir, "import-${System.nanoTime()}.tmp")
        deleteWithSidecars(plain)
        val keyHead = ByteArrayOutputStream(EPHEMERAL_KEY_BYTES)
        try {
            val header: ByteArray
            val salt: ByteArray
            source.inputStream().use { rawIn ->
                val parsed = readHeader(rawIn)
                header = parsed.first
                salt = parsed.second
                val key = deriveKey(passphrase, salt)
                try {
                    plain.outputStream().use { fileOut ->
                        val split = SplitOutputStream(EPHEMERAL_KEY_BYTES, keyHead, fileOut)
                        attachmentCipher.decrypt(key, rawIn, split, associatedData = header)
                        split.flush()
                    }
                } finally {
                    key.fill(0)
                }
            }
            val ephemeralKey = keyHead.toByteArray()
            if (ephemeralKey.size != EPHEMERAL_KEY_BYTES) throw BackupException("Malformed backup payload")
            try {
                replaceContentsFrom(plain, ephemeralKey)
            } finally {
                ephemeralKey.fill(0)
            }
        } finally {
            deleteWithSidecars(plain)
        }
    }

    private fun preflight(db: SQLiteDatabase) {
        val present = mutableSetOf<String>()
        db.rawQuery("SELECT name FROM backup.sqlite_master WHERE type = 'table'", null).use { c ->
            while (c.moveToNext()) present.add(c.getString(0))
        }
        val missing = TABLES.filterNot { it in present }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Backup is missing ${missing.size} expected table(s): ${missing.joinToString(", ")}. " +
                    "Nothing has been changed.",
            )
        }

        // Columns must match, in name and order.
        //
        // The restore copies rows with INSERT INTO main.x SELECT * FROM backup.x, which lines
        // columns up by position. A backup written before a column was added has a different
        // shape, so the insert fails partway, and by then the live table has already been
        // cleared. The transaction rolls it back, but the user is one bug away from losing
        // everything to a backup that was never going to work.
        //
        // Checking here means an incompatible backup is refused while the live data is still
        // untouched, and the message says which table rather than leaving a SQL error to
        // interpret.
        for (table in TABLES) {
            val live = columnsOf(db, "main", table)
            val backup = columnsOf(db, "backup", table)
            if (live != backup) {
                throw IllegalStateException(
                    "Backup table '$table' does not match this version of the app " +
                        "(expected ${live.size} column(s), backup has ${backup.size}). " +
                        "It was probably made by a different version. Nothing has been changed.",
                )
            }
        }
    }

    /** Column names in declaration order, which is the order SELECT * returns them in. */
    private fun columnsOf(db: SQLiteDatabase, schema: String, table: String): List<String> {
        val names = mutableListOf<String>()
        // PRAGMA cannot be parameterised, so the identifiers are interpolated. Both come from
        // TABLES, a compile-time constant list, never from the backup file or user input.
        db.rawQuery("PRAGMA $schema.table_info($table)", null).use { c ->
            val nameIndex = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIndex >= 0) names.add(c.getString(nameIndex))
            }
        }
        return names
    }

    private fun replaceContentsFrom(plain: File, ephemeralKey: ByteArray) {
        val db = secureDatabase.open()
        db.execSQL(
            "ATTACH DATABASE ? AS backup KEY \"x'${hex(ephemeralKey)}'\"",
            arrayOf<Any>(plain.absolutePath),
        )
 // Check the backup is coherent before touching live data.
 //
 // Decryption proves the archive was produced by someone holding the passphrase; it says
 // nothing about whether the contents are a usable database. A truncated export, one from
 // an incompatible version, or a malformed archive with a known passphrase
 // all decrypt successfully and then fail partway through the restore, after the live
 // tables have already been cleared. The transaction rolls that back, but relying on
 // rollback to protect the user's entire history is a thin margin when the check is
 // cheap. Verifying first means a bad archive is refused before anything is destroyed.
        preflight(db)

        db.execSQL("PRAGMA foreign_keys = OFF")
        db.beginTransaction()
        try {
 // On purpose not wrapped in runCatching. Each table is cleared and then refilled;
 // if the insert fails after the delete and the error is swallowed, that table is
 // left empty and the transaction still commits, a restore that silently destroys
 // the data it was meant to recover. Letting the exception propagate skips
 // setTransactionSuccessful, so endTransaction rolls the whole import back and the
 // caller is told it failed. A restore that fails loudly and changes nothing is
 // always better than one that half-succeeds.
            TABLES.forEach { table ->
                db.execSQL("DELETE FROM main.$table")
                db.execSQL("INSERT INTO main.$table SELECT * FROM backup.$table")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.execSQL("PRAGMA foreign_keys = ON")
            runCatching { db.execSQL("DETACH DATABASE backup") }
        }
    }

    private fun buildHeader(salt: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { s ->
            s.write(MAGIC)
            s.writeByte(VERSION)
            s.writeByte(salt.size)
            s.write(salt)
        }
        return out.toByteArray()
    }

    private fun readHeader(input: InputStream): Pair<ByteArray, ByteArray> {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        data.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw BackupException("Not a NiiX backup file")
        val version = data.readUnsignedByte()
        if (version != VERSION) throw BackupException("Unsupported backup version $version")
        val saltLen = data.readUnsignedByte()
        val salt = ByteArray(saltLen)
        data.readFully(salt)
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use { s ->
            s.write(MAGIC)
            s.writeByte(version)
            s.writeByte(saltLen)
            s.write(salt)
        }
        return header.toByteArray() to salt
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val bytes = encodeUtf8(passphrase)
        return try {
            PassphraseKdf.derivePasscodeKey(bytes, salt)
        } finally {
            bytes.fill(0)
        }
    }

    private fun encodeUtf8(chars: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private class SplitOutputStream(
        private val headBytes: Int,
        private val head: OutputStream,
        private val tail: OutputStream,
    ) : OutputStream() {
        private var written = 0

        override fun write(b: Int) {
            if (written < headBytes) head.write(b) else tail.write(b)
            written++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var remaining = len
            if (written < headBytes) {
                val toHead = minOf(remaining, headBytes - written)
                head.write(b, offset, toHead)
                offset += toHead
                remaining -= toHead
                written += toHead
            }
            if (remaining > 0) {
                tail.write(b, offset, remaining)
                written += remaining
            }
        }

        override fun flush() {
            head.flush()
            tail.flush()
        }
    }

    companion object {
        private val MAGIC = byteArrayOf('N'.code.toByte(), 'I'.code.toByte(), 'X'.code.toByte(), 'B'.code.toByte())
        private const val VERSION = 1
        private const val SALT_BYTES = 16
        private const val EPHEMERAL_KEY_BYTES = 32

        private val TABLES = listOf(
            Schema.Account.TABLE,
            Schema.OnionIdentity.TABLE,
            Schema.PreKeys.TABLE,
            Schema.SignedPreKeys.TABLE,
            Schema.KyberPreKeys.TABLE,
            Schema.KyberUsedBaseKeys.TABLE,
            Schema.GroupSenderKeys.TABLE,
            Schema.GroupSenderKeyState.TABLE,
            Schema.GroupRemoteSenderKeys.TABLE,
            Schema.Sessions.TABLE,
            Schema.Identities.TABLE,
            Schema.Contacts.TABLE,
            Schema.Conversations.TABLE,
            Schema.GroupMembers.TABLE,
            Schema.Messages.TABLE,
            Schema.Attachments.TABLE,
            Schema.Settings.TABLE,
            Schema.Blocked.TABLE,
            Schema.PendingGroupInvites.TABLE,
            Schema.RelayGrantsReceived.TABLE,
            Schema.RelayGrantsIssued.TABLE,
        )
    }
}
