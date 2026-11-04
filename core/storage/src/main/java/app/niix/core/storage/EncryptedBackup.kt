package app.niix.core.storage

import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.CharBuffer
import java.security.SecureRandom

class BackupException(message: String) : IllegalStateException(message)

class EncryptedBackup internal constructor(
    private val secureDatabase: SecureDatabase,
    private val attachmentCipher: AttachmentCipher,
    private val tempDir: File,
    private val attachmentsDir: File,
    private val profilesDir: File,
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

    /**
     * Writes the database and every stored file into one archive under [passphrase].
     *
     * Inside the encryption: the database's temporary key, the database, then the attachment and
     * profile picture files. Those are already encrypted with keys held in the database, so they're
     * copied as they are.
     */
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
                    DataOutputStream(attachmentCipher.encryptingStream(key, rawOut, associatedData = header)).use { out ->
                        out.write(ephemeralKey)
                        out.writeLong(plain.length())
                        plain.inputStream().use { it.copyTo(out) }
                        val files = storedFiles()
                        out.writeInt(files.size)
                        files.forEach { (folder, file) ->
                            out.writeByte(folder)
                            out.writeUTF(file.name)
                            out.writeLong(file.length())
                            file.inputStream().use { it.copyTo(out) }
                        }
                    }
                }
            } finally {
                key.fill(0)
            }
        } finally {
            ephemeralKey.fill(0)
            deleteWithSidecars(plain)
        }
    }

    /**
     * Replaces this device's data with the archive's. Nothing live is touched until the whole
     * archive has decrypted and checked out: the database is restored in one transaction, and the
     * files are unpacked to the side and only moved into place after it.
     */
    fun import(passphrase: CharArray, source: File) {
        purgeStaleTempFiles()
        val plain = File(tempDir, "import-${System.nanoTime()}.tmp")
        val staging = File(tempDir, "import-files-${System.nanoTime()}")
        deleteWithSidecars(plain)
        val ephemeralKey = ByteArray(EPHEMERAL_KEY_BYTES)
        var version = 0
        try {
            source.inputStream().use { rawIn ->
                val (header, salt, headerVersion) = readHeader(rawIn)
                version = headerVersion
                val key = deriveKey(passphrase, salt)
                try {
                    DataInputStream(attachmentCipher.decryptingStream(key, rawIn, associatedData = header)).use { input ->
                        input.readFully(ephemeralKey)
                        if (version == 1) {
                            // The first format: the database and nothing after it.
                            plain.outputStream().use { input.copyTo(it) }
                        } else {
                            val dbLength = input.readLong()
                            if (dbLength < 0) throw BackupException("Malformed backup payload")
                            plain.outputStream().use { copyExactly(input, it, dbLength) }
                            unpackFiles(input, staging)
                            // Reading to the end is what proves the archive wasn't cut short.
                            if (input.read() != -1) throw BackupException("Malformed backup payload")
                        }
                    }
                } finally {
                    key.fill(0)
                }
            }
            replaceContentsFrom(plain, ephemeralKey)
            if (version != 1) moveFilesIntoPlace(staging)
        } catch (_: java.io.EOFException) {
            throw BackupException("Malformed backup payload")
        } finally {
            ephemeralKey.fill(0)
            deleteWithSidecars(plain)
            staging.deleteRecursively()
        }
    }

    /** Every attachment and profile picture file, with the folder it belongs in. */
    private fun storedFiles(): List<Pair<Int, File>> =
        listOf(FOLDER_ATTACHMENTS to attachmentsDir, FOLDER_PROFILES to profilesDir).flatMap { (folder, dir) ->
            dir.listFiles().orEmpty().filter { it.isFile && isValidName(folder, it.name) }.map { folder to it }
        }

    private fun unpackFiles(input: DataInputStream, staging: File) {
        val count = input.readInt()
        if (count !in 0..MAX_FILES) throw BackupException("Malformed backup payload")
        repeat(count) {
            val folder = input.readUnsignedByte()
            val name = input.readUTF()
            val length = input.readLong()
            // Names come from inside the archive, so they're checked like any outside input: only
            // the names this app gives its files, which can't climb out of the folder.
            if (!isValidName(folder, name) || length < 0) throw BackupException("Malformed backup payload")
            val dir = File(staging, folder.toString()).apply { mkdirs() }
            File(dir, name).outputStream().use { copyExactly(input, it, length) }
        }
    }

    private fun moveFilesIntoPlace(staging: File) {
        for ((folder, live) in listOf(FOLDER_ATTACHMENTS to attachmentsDir, FOLDER_PROFILES to profilesDir)) {
            live.deleteRecursively()
            live.mkdirs()
            File(staging, folder.toString()).listFiles().orEmpty().forEach { file ->
                if (!file.renameTo(File(live, file.name))) file.copyTo(File(live, file.name), overwrite = true)
            }
        }
    }

    private fun isValidName(folder: Int, name: String): Boolean = when (folder) {
        FOLDER_ATTACHMENTS -> name.endsWith(ENC) && AttachmentFiles.isValidId(name.removeSuffix(ENC))
        FOLDER_PROFILES -> PROFILE_NAME.matches(name)
        else -> false
    }

    private fun copyExactly(input: InputStream, out: OutputStream, count: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw BackupException("Malformed backup payload")
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    /**
     * Checks the backup can be restored, before any live data is touched, and returns the columns
     * to copy for each table it has. A backup from an older version may lack tables and columns
     * added since; those are left empty or at their defaults. Anything else that doesn't line up
     * would fail halfway through the restore, so it's refused here, naming the table.
     */
    private fun preflight(db: SQLiteDatabase): Map<String, List<String>> {
        val present = mutableSetOf<String>()
        db.rawQuery("SELECT name FROM backup.sqlite_master WHERE type = 'table'", null).use { c ->
            while (c.moveToNext()) present.add(c.getString(0))
        }
        val missing = TABLES.filterNot { it in present || it in ADDED_LATER }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Backup is missing ${missing.size} expected table(s): ${missing.joinToString(", ")}. " +
                    "Nothing has been changed.",
            )
        }
        return TABLES.filter { it in present }.associateWith { table ->
            val live = columnsOf(db, "main", table).toSet()
            val backup = columnsOf(db, "backup", table)
            if (!live.containsAll(backup)) {
                throw IllegalStateException(
                    "Backup table '$table' has columns this version of the app doesn't know. " +
                        "It was probably made by a newer version. Nothing has been changed.",
                )
            }
            backup
        }
    }

    /** Column names in declaration order, which is the order SELECT * returns them. */
    private fun columnsOf(db: SQLiteDatabase, schema: String, table: String): List<String> {
        val names = mutableListOf<String>()
        // PRAGMA can't take parameters, so the names are interpolated. Both come from the TABLES
        // constant, never from the backup or the user.
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
        // Check the backup makes sense before touching live data. Decrypting only proves whoever
        // made it knew the passphrase; a truncated or incompatible export decrypts fine and then
        // fails halfway through the restore.
        val columns = preflight(db)

        db.execSQL("PRAGMA foreign_keys = OFF")
        db.beginTransaction()
        try {
            // Deliberately not in runCatching. If an insert fails after its table was cleared and
            // the error were swallowed, the transaction would commit with that table empty. Letting
            // it throw skips setTransactionSuccessful, so the whole import rolls back and the
            // caller hears about it.
            TABLES.forEach { table ->
                db.execSQL("DELETE FROM main.$table")
                // Named columns, so a backup missing a newer column still lines up. The names come
                // from the backup's own schema and were checked against ours in preflight.
                columns[table]?.joinToString(", ")?.let { names ->
                    db.execSQL("INSERT INTO main.$table ($names) SELECT $names FROM backup.$table")
                }
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

    private fun readHeader(input: InputStream): Triple<ByteArray, ByteArray, Int> {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        data.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw BackupException("Not a NiiX backup file")
        val version = data.readUnsignedByte()
        if (version !in 1..VERSION) throw BackupException("Unsupported backup version $version")
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
        return Triple(header.toByteArray(), salt, version)
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

    companion object {
        private val MAGIC = byteArrayOf('N'.code.toByte(), 'I'.code.toByte(), 'X'.code.toByte(), 'B'.code.toByte())
        /** 2 added the attachment and profile picture files. 1 is still restored. */
        private const val VERSION = 2
        private const val FOLDER_ATTACHMENTS = 1
        private const val FOLDER_PROFILES = 2
        private const val ENC = ".enc"
        private val PROFILE_NAME = Regex("^[0-9a-f]{64}\\.enc$")

        /** Well above any real account, low enough that a forged count can't do much. */
        private const val MAX_FILES = 1_000_000
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
            Schema.Devices.TABLE,
            Schema.DeviceLists.TABLE,
        )

        /** Tables older backups don't have. They're restored empty. */
        private val ADDED_LATER = setOf(Schema.Devices.TABLE, Schema.DeviceLists.TABLE)
    }
}
