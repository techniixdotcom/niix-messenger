package app.niix

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.niix.core.storage.AttachmentCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class StorageInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun attachmentRoundTripsThroughRealFileIo() {
        val cipher = AttachmentCipher()
        val key = cipher.newKey()
        val source = File(context.cacheDir, "att-src").apply { writeText("file contents") }
        val sealed = File(context.cacheDir, "att-enc")
        val restored = File(context.cacheDir, "att-dec")
        try {
            cipher.encryptFile(key, source, sealed)
            cipher.decryptFile(key, sealed, restored)
            assertArrayEquals(source.readBytes(), restored.readBytes())
        } finally {
            listOf(source, sealed, restored).forEach { it.delete() }
        }
    }

    @Test
    fun attachmentCiphertextOnDiskDoesNotContainThePlaintext() {
        // Make sure it actually encrypts; a plain copy would pass a round trip too.
        val cipher = AttachmentCipher()
        val key = cipher.newKey()
        val secret = "a-distinctive-string-9f3a2b"
        val source = File(context.cacheDir, "leak-src").apply { writeText(secret) }
        val sealed = File(context.cacheDir, "leak-enc")
        try {
            cipher.encryptFile(key, source, sealed)
            val onDisk = sealed.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse("plaintext present in ciphertext", onDisk.contains(secret))
        } finally {
            listOf(source, sealed).forEach { it.delete() }
        }
    }

    @Test
    fun attachmentDecryptsOnlyUnderTheIdItWasBoundTo() {
        // With the right key, ciphertext still mustn't decrypt as a different attachment.
        val cipher = AttachmentCipher()
        val key = cipher.newKey()
        val sealed = ByteArrayOutputStream()
        cipher.encrypt(key, "contents".byteInputStream(), sealed, "id-A".toByteArray())

        val matching = ByteArrayOutputStream()
        cipher.decrypt(key, sealed.toByteArray().inputStream(), matching, "id-A".toByteArray())
        assertArrayEquals("contents".toByteArray(), matching.toByteArray())

        val mismatched = runCatching {
            cipher.decrypt(
                key,
                sealed.toByteArray().inputStream(),
                ByteArrayOutputStream(),
                "id-B".toByteArray(),
            )
        }
        assertTrue("ciphertext decrypted under the wrong attachment id", mismatched.isFailure)
    }

    @Test
    fun attachmentWithoutTheIdBindingIsRefused() {
        // Unbound ciphertext must not open on the bound path. A fallback would hand the unbound
        // path to anyone able to strip the binding.
        val cipher = AttachmentCipher()
        val key = cipher.newKey()

        val unbound = ByteArrayOutputStream()
        cipher.encrypt(key, "written before binding".byteInputStream(), unbound)

        val result = runCatching {
            cipher.decrypt(
                key,
                unbound.toByteArray().inputStream(),
                ByteArrayOutputStream(),
                "some-attachment-id".toByteArray(),
            )
        }
        assertTrue("unbound ciphertext was accepted on the bound path", result.isFailure)
    }

    @Test
    fun attachmentRefusesAWrongKey() {
        val cipher = AttachmentCipher()
        val sealed = ByteArrayOutputStream()
        cipher.encrypt(cipher.newKey(), "contents".byteInputStream(), sealed)

        val result = runCatching {
            cipher.decrypt(cipher.newKey(), sealed.toByteArray().inputStream(), ByteArrayOutputStream())
        }
        assertTrue("decrypted under the wrong key", result.isFailure)
    }

    @Test
    fun attachmentRefusesTamperedCiphertext() {
        // Authentication as well as secrecy: a flipped bit must be caught, not turned into garbage
        // the app then parses.
        val cipher = AttachmentCipher()
        val key = cipher.newKey()
        val sealed = ByteArrayOutputStream()
        cipher.encrypt(key, "contents to tamper with".byteInputStream(), sealed)

        val bytes = sealed.toByteArray()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()

        val result = runCatching {
            cipher.decrypt(key, bytes.inputStream(), ByteArrayOutputStream())
        }
        assertTrue("tampered ciphertext was accepted", result.isFailure)
    }

    @Test
    fun applicationStartsOnThisDevice() {
        // If the application object can't be obtained, the app doesn't run on this hardware at all,
        // and no JVM test would show that.
        assertNotNull(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext)
    }
}
