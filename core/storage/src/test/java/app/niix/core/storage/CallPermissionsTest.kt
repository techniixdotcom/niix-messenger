package app.niix.core.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tests for [CallPermissions], which decides whose calls may ring this phone. */
class CallPermissionsTest {

    private lateinit var settings: FakeSettings
    private lateinit var calls: CallPermissions

    private val alice = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.onion"
    private val bob = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.onion"
    private val aliceKey = ByteArray(33) { 5 }
    private val otherKey = ByteArray(33) { 6 }

    @Before
    fun setUp() {
        settings = FakeSettings()
        calls = CallPermissions(settings)
    }

    @Test
    fun `nobody is allowed by default`() {
        assertFalse(calls.allowedByMe(alice, aliceKey))
        assertFalse(calls.allowedByThem(alice))
        assertFalse(calls.mutual(alice, aliceKey))
    }

    @Test
    fun `allowing only one side is not enough to call`() {
        calls.allow(alice, aliceKey)
        assertTrue(calls.allowedByMe(alice, aliceKey))
        assertFalse(calls.mutual(alice, aliceKey))

        calls.disallow(alice)
        calls.setAllowedByThem(alice, true)
        assertFalse(calls.mutual(alice, aliceKey))
    }

    @Test
    fun `both sides allowing makes it mutual`() {
        calls.allow(alice, aliceKey)
        calls.setAllowedByThem(alice, true)
        assertTrue(calls.mutual(alice, aliceKey))
    }

    @Test
    fun `a changed identity key is no longer allowed`() {
        calls.allow(alice, aliceKey)
        calls.setAllowedByThem(alice, true)
        assertFalse(calls.allowedByMe(alice, otherKey))
        assertFalse(calls.mutual(alice, otherKey))
        assertTrue(calls.isAllowedByMeRecorded(alice))
    }

    @Test
    fun `a missing identity key is never allowed`() {
        calls.allow(alice, aliceKey)
        assertFalse(calls.allowedByMe(alice, null))
    }

    @Test
    fun `permissions are per contact`() {
        calls.allow(alice, aliceKey)
        calls.setAllowedByThem(alice, true)
        assertFalse(calls.allowedByMe(bob, aliceKey))
        assertFalse(calls.allowedByThem(bob))
    }

    @Test
    fun `either side withdrawing stops it`() {
        calls.allow(alice, aliceKey)
        calls.setAllowedByThem(alice, true)
        calls.setAllowedByThem(alice, false)
        assertFalse(calls.mutual(alice, aliceKey))

        calls.setAllowedByThem(alice, true)
        calls.disallow(alice)
        assertFalse(calls.mutual(alice, aliceKey))
    }

    @Test
    fun `forget clears both directions`() {
        calls.allow(alice, aliceKey)
        calls.setAllowedByThem(alice, true)
        calls.forget(alice)
        assertFalse(calls.isAllowedByMeRecorded(alice))
        assertFalse(calls.allowedByThem(alice))
    }

    @Test
    fun `a corrupt stored key fails closed`() {
        settings.setString("call_allow:$alice", "zz")
        assertFalse(calls.allowedByMe(alice, aliceKey))
        settings.setString("call_allow:$alice", "abc")
        assertFalse(calls.allowedByMe(alice, aliceKey))
        settings.setString("call_allow:$alice", "")
        assertFalse(calls.allowedByMe(alice, aliceKey))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `allowing needs a key`() {
        calls.allow(alice, ByteArray(0))
    }
}
