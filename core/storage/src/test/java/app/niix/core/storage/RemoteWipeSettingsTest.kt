package app.niix.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [RemoteWipeSettings].
 *
 * This decides whether a message destroys every message, contact and key on the device. It is the
 * most destructive authorisation in the app and had no tests at all, which is the wrong way round
 * from how much of the suite covers things that merely fail to render.
 *
 * Backed by a fake settings store so the logic can be exercised without a database. The logic is
 * what is being tested; SQLCipher is not.
 */
class RemoteWipeSettingsTest {

    private lateinit var settings: FakeSettings
    private lateinit var wipe: RemoteWipeSettings

    private val alice = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.onion"
    private val mallory = "mmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmm.onion"
    private val aliceKey = ByteArray(32) { 1 }
    private val otherKey = ByteArray(32) { 2 }

    @Before
    fun setUp() {
        settings = FakeSettings()
        wipe = RemoteWipeSettings(settings)
    }

    @Test
    fun `disabled by default`() {
        assertFalse(wipe.isEnabled())
    }

    @Test
    fun `refuses everything while disabled`() {
        // Even a correctly nominated contact with the right key cannot wipe a device where the
        // feature was never turned on.
        wipe.nominate(alice, aliceKey)
        assertFalse(wipe.shouldWipe(alice, aliceKey))
    }

    @Test
    fun `accepts a nominated contact with the pinned key`() {
        wipe.enable()
        wipe.nominate(alice, aliceKey)
        assertTrue(wipe.shouldWipe(alice, aliceKey))
    }

    @Test
    fun `refuses a contact who was never nominated`() {
        wipe.enable()
        wipe.nominate(alice, aliceKey)
        assertFalse(wipe.shouldWipe(mallory, aliceKey))
    }

    @Test
    fun `refuses a nominated address presenting a different identity key`() {
        // The case H-07 was about. Under trust-on-first-use an address can come to belong to a
        // different key, and a permission to destroy everything must not survive that.
        wipe.enable()
        wipe.nominate(alice, aliceKey)
        assertFalse(wipe.shouldWipe(alice, otherKey))
    }

    @Test
    fun `refuses when no identity key is presented at all`() {
        wipe.enable()
        wipe.nominate(alice, aliceKey)
        assertFalse(wipe.shouldWipe(alice, null))
    }

    @Test
    fun `revoking takes effect immediately`() {
        wipe.enable()
        wipe.nominate(alice, aliceKey)
        wipe.revoke(alice)
        assertFalse(wipe.shouldWipe(alice, aliceKey))
    }

    @Test
    fun `revoking forgets the pinned key, so re-nominating does not resurrect the old one`() {
        wipe.enable()
        wipe.nominate(alice, aliceKey)
        wipe.revoke(alice)
        wipe.nominate(alice, otherKey)
        assertTrue(wipe.shouldWipe(alice, otherKey))
        assertFalse(wipe.shouldWipe(alice, aliceKey))
    }

    @Test
    fun `disabling forgets every nomination`() {
        // Re-enabling starts from nobody rather than silently restoring a list the user may no
        // longer agree with.
        wipe.enable()
        wipe.nominate(alice, aliceKey)
        wipe.disable()
        wipe.enable()
        assertTrue(wipe.nominated().isEmpty())
        assertFalse(wipe.shouldWipe(alice, aliceKey))
    }

    @Test
    fun `nominating the same contact twice does not duplicate them`() {
        wipe.enable()
        wipe.nominate(alice, aliceKey)
        wipe.nominate(alice, aliceKey)
        assertEquals(1, wipe.nominated().size)
    }

    @Test
    fun `the two lists are independent`() {
        // nominated() is who may wipe this device; canWipe() is whose device this one may wipe.
        // Conflating them would either offer to wipe strangers or honour a wipe from one.
        wipe.enable()
        wipe.nominate(alice, aliceKey)
        wipe.setCanWipe(mallory, true)

        assertTrue(wipe.nominated().contains(alice))
        assertFalse(wipe.nominated().contains(mallory))
        assertTrue(wipe.canWipe().contains(mallory))
        assertFalse(wipe.canWipe().contains(alice))

        // The important one: being on the canWipe list grants no authority over this device.
        assertFalse(wipe.shouldWipe(mallory, aliceKey))
    }

    @Test
    fun `withdrawing a canWipe entry removes it`() {
        wipe.setCanWipe(mallory, true)
        wipe.setCanWipe(mallory, false)
        assertTrue(wipe.canWipe().isEmpty())
    }
}
