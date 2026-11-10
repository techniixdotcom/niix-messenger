package app.niix.core.storage

import java.io.File
import java.nio.file.Files
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * The desktop storage stack end to end, on the real SQLCipher-compatible driver: setup, passcode,
 * lock and unlock, duress, the locked inbox and backups. SecureStorage is a process-wide singleton,
 * so the steps run in order against one data folder.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DesktopStorageTest {

    companion object {
        private lateinit var root: File
        private lateinit var storage: SecureStorage
        private val PASSCODE = "12345678".toCharArray()
        private val DURESS = "87654321".toCharArray()

        @JvmStatic
        @BeforeClass
        fun setUp() {
            KeyVault.keyringDisabled = true
            root = Files.createTempDirectory("niix-desktop-test").toFile()
            storage = SecureStorage.getInstance(
                StoragePaths(File(root, "files"), File(root, "private"), File(root, "cache")),
            )
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            root.deleteRecursively()
        }
    }

    @Test
    fun a_setUpWithoutPasscodeOpensAndPersists() {
        assertFalse(storage.appLock.isSetUp())
        assertTrue(storage.appLock.setUpWithoutPasscode())
        assertTrue(storage.appLock.isUnlocked())
        assertEquals("file", storage.keyStoreKind())
        storage.settings.setString("probe", "first")
        storage.lock()
        assertFalse(storage.appLock.isUnlocked())
        assertEquals(UnlockResult.SUCCESS, storage.appLock.unlockWithoutPasscode())
        assertEquals("first", storage.settings.getString("probe"))
    }

    @Test
    fun b_databaseFileIsEncrypted() {
        val file = File(File(root, "private"), Schema.DATABASE_FILENAME)
        assertTrue(file.isFile)
        val header = file.readBytes().copyOf(16)
        assertFalse(String(header, Charsets.ISO_8859_1).startsWith("SQLite format 3"))
    }

    @Test
    fun c_enablePasscodeRekeysAndUnlocks() {
        assertTrue(storage.appLock.enablePasscode(PASSCODE.copyOf()))
        assertTrue(storage.appLock.isPasscodeEnabled())
        storage.lock()
        assertEquals(UnlockResult.FAILED, storage.appLock.unlock("00000000".toCharArray()))
        assertFalse(storage.appLock.isUnlocked())
        // The failed attempt starts a back-off; wait it out like a user would.
        val wait = storage.appLock.throttleRemainingMillis()
        if (wait > 0) Thread.sleep(wait + 50)
        assertEquals(UnlockResult.SUCCESS, storage.appLock.unlock(PASSCODE.copyOf()))
        assertEquals("first", storage.settings.getString("probe"))
    }

    @Test
    fun d_lockedInboxRoundTrip() {
        val inbox = storage.lockedInbox
        inbox.prepare()
        assertTrue(inbox.enqueue("abc.onion", byteArrayOf(1, 2, 3), viaRelay = false))
        assertTrue(inbox.enqueue("def.onion", byteArrayOf(4, 5), viaRelay = true))
        assertFalse(inbox.isEmpty())
        val drained = inbox.drain()
        assertEquals(2, drained.size)
        assertEquals("abc.onion", drained[0].senderOnion)
        assertArrayEquals(byteArrayOf(1, 2, 3), drained[0].ciphertext)
        assertFalse(drained[0].viaRelay)
        assertTrue(drained[1].viaRelay)
        assertTrue(inbox.isEmpty())
    }

    @Test
    fun e_backupRoundTrip() {
        storage.settings.setString("probe", "before-backup")
        val archive = File(root, "backup.niix")
        storage.backup().export("correct horse".toCharArray(), archive)
        assertTrue(archive.length() > 0)
        storage.settings.setString("probe", "after-backup")
        try {
            storage.backup().import("wrong horse".toCharArray(), archive)
            fail("A wrong passphrase must not restore anything")
        } catch (_: Exception) {
        }
        assertEquals("after-backup", storage.settings.getString("probe"))
        storage.backup().import("correct horse".toCharArray(), archive)
        assertEquals("before-backup", storage.settings.getString("probe"))
    }

    @Test
    fun f_duressPasscodeIsRecognised() {
        assertTrue(storage.appLock.setDuressPasscode(DURESS.copyOf()))
        storage.lock()
        assertEquals(UnlockResult.DURESS, storage.appLock.unlock(DURESS.copyOf()))
    }

    @Test
    fun g_wipeRemovesEverything() {
        storage.wipeAllData()
        assertFalse(storage.appLock.isSetUp())
        assertFalse(File(File(root, "private"), Schema.DATABASE_FILENAME).exists())
        assertNull(KeyVault.backend())
    }

    @Test
    fun h_shimTransactionsAndConversions() {
        val file = File(root, "shim.db")
        val key = ByteArray(32) { it.toByte() }
        val db = SQLiteDatabase.openWithRawKey(file, key)
        try {
            db.execSQL("CREATE TABLE t (id TEXT PRIMARY KEY, n INTEGER, r REAL, b BLOB)")
            val values = android.content.ContentValues().apply {
                put("id", "a")
                put("n", 7L)
                put("r", 1.5)
                put("b", byteArrayOf(9, 8))
            }
            assertTrue(db.insertWithOnConflict("t", null, values, SQLiteDatabase.CONFLICT_REPLACE) > 0)
            assertEquals(-1L, db.insertWithOnConflict("t", null, values, SQLiteDatabase.CONFLICT_IGNORE))
            assertEquals(-1L, db.insert("t", null, values))

            db.beginTransaction()
            try {
                db.beginTransaction()
                try {
                    db.execSQL("INSERT INTO t (id, n) VALUES ('nested', 1)")
                } finally {
                    db.endTransaction()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.rawQuery("SELECT count(*) FROM t WHERE id = ?", arrayOf("nested")).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("An unsuccessful inner transaction rolls back the outer one", 0, c.getInt(0))
            }

            db.rawQuery("SELECT id, n, r, b FROM t", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("a", c.getString(c.getColumnIndexOrThrow("id")))
                assertEquals(7L, c.getLong(1))
                assertEquals("7", c.getString(1))
                assertEquals(1.5, c.getDouble(2), 0.0)
                assertArrayEquals(byteArrayOf(9, 8), c.getBlob(3))
                assertEquals(-1, c.getColumnIndex("missing"))
                assertFalse(c.moveToNext())
            }
            assertEquals(1, db.update("t", android.content.ContentValues().apply { putNull("b") }, "id = ?", arrayOf("a")))
            db.rawQuery("SELECT b FROM t", null).use { c ->
                c.moveToFirst()
                assertTrue(c.isNull(0))
                assertNull(c.getBlob(0))
            }
            db.version = 42
            assertEquals(42, db.version)
            assertEquals(1, db.delete("t", "id = ?", arrayOf("a")))
        } finally {
            db.close()
        }
        try {
            SQLiteDatabase.openWithRawKey(file, ByteArray(32) { 1 }).close()
            fail("The wrong key must not open the database")
        } catch (_: Exception) {
        }
        assertNotNull(SQLiteDatabase.openWithRawKey(file, key).also { it.close() })
    }
}
