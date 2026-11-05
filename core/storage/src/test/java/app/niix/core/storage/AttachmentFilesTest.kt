package app.niix.core.storage

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AttachmentFilesTest {

    private lateinit var root: File
    private lateinit var attachmentsDir: File

    @Before
    fun setUp() {
        root = File.createTempFile("niix-attachment-test", "").apply {
            delete()
            mkdirs()
        }
        attachmentsDir = File(root, "attachments").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private val validId = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"

    @Test
    fun `valid uuid resolves inside attachmentsDir`() {
        val resolved = AttachmentFiles.resolve(attachmentsDir, validId)
        assertEquals(File(attachmentsDir, "$validId.enc").canonicalFile, resolved)
    }

    @Test
    fun `path traversal id is rejected`() {
        val malicious = "../../../../data/data/app.niix/databases/niix"
        assertFalse(AttachmentFiles.isValidId(malicious))
        assertNull(AttachmentFiles.resolve(attachmentsDir, malicious))
    }

    @Test
    fun `traversal id embedded in otherwise-uuid-shaped string is rejected`() {
        val malicious = "1b4e28ba-2fa1-11d2-883f-0016d3cca427/../../../secret"
        assertNull(AttachmentFiles.resolve(attachmentsDir, malicious))
    }

    @Test
    fun `absolute path id is rejected`() {
        assertNull(AttachmentFiles.resolve(attachmentsDir, "/etc/passwd"))
    }

    @Test
    fun `empty and garbage ids are rejected`() {
        assertNull(AttachmentFiles.resolve(attachmentsDir, ""))
        assertNull(AttachmentFiles.resolve(attachmentsDir, "not-a-uuid"))
        assertNull(AttachmentFiles.resolve(attachmentsDir, "1b4e28ba-2fa1-11d2-883f-0016d3cca42"))
    }

    @Test
    fun `delete removes only a file that resolves inside attachmentsDir`() {
        val target = File(attachmentsDir, "$validId.enc")
        target.writeText("ciphertext")
        assertTrue(AttachmentFiles.delete(attachmentsDir, validId))
        assertFalse(target.exists())
    }

    @Test
    fun `delete never touches a file outside attachmentsDir even if one exists at the traversal target`() {
        val outsideVictim = File(root, "victim.enc")
        outsideVictim.writeText("do not delete me")
        val malicious = "..-..-..-..-............"
        assertFalse(AttachmentFiles.delete(attachmentsDir, malicious))
        assertTrue(outsideVictim.exists())
    }

    @Test
    fun `delete of a nonexistent but validly-shaped id is a safe no-op`() {
        assertFalse(AttachmentFiles.delete(attachmentsDir, validId))
    }
}
