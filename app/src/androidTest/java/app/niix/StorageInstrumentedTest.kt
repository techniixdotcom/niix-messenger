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
 // Verifies it is actually encrypting rather than merely copying. A plain write would pass
 // a round-trip test perfectly.
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
 // The property the AEAD binding exists for: given the correct key, ciphertext must still
 // refuse to decrypt as a different attachment.
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
 // Unbound ciphertext must not decrypt on the bound path. There is no
 // fallback: retrying unbound when the bound attempt fails would hand that path to
 // anyone able to strip the binding, defeating the point of having it.
 //
 // This is why attachments created before the binding no longer open, a trade made
 // while the only users were testers.
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
 // Authentication, not just confidentiality: a flipped bit must be detected rather than
 // producing garbage plaintext the app would then try to parse.
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
 // A smoke test with real value: if the application object cannot be obtained, the app
 // does not run on this hardware at all, and no JVM test would tell you that.
        assertNotNull(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext)
    }
}
