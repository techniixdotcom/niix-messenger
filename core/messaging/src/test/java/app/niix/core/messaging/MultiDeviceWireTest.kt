package app.niix.core.messaging

import app.niix.core.model.Device
import app.niix.core.model.DeviceList
import app.niix.core.model.OnionAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiDeviceWireTest {

    private val a = OnionAddress.parse("a".repeat(56) + ".onion")
    private val b = OnionAddress.parse("b".repeat(56) + ".onion")

    private fun roundTrip(message: WireMessage) = WireCodec.decode(WireCodec.encode(message))

    @Test
    fun `a device list survives the wire`() {
        val list = DeviceList(3, listOf(Device(1, a), Device(4, b)))
        assertEquals(WireMessage.Devices(list), roundTrip(WireMessage.Devices(list)))
    }

    @Test
    fun `a sent message is carried to our other devices intact`() {
        val inner = WireMessage.Text("conv", "id", a.value, "hello", 0, null)
        assertEquals(WireMessage.SyncSent("conv", inner), roundTrip(WireMessage.SyncSent("conv", inner)))
    }

    @Test
    fun `a sync message can't carry another`() {
        val nested = WireMessage.SyncSent("c", WireMessage.SyncSent("c", WireMessage.Dummy(ByteArray(1))))
        assertTrue(runCatching { WireCodec.encode(nested) }.isFailure)
    }

    @Test
    fun `contact updates keep the pin, verification and block`() {
        val update = WireMessage.ContactUpdate(a.value, "Ann", ByteArray(33) { 5 }, verified = true, blocked = true)
        val decoded = roundTrip(update) as WireMessage.ContactUpdate
        assertEquals(update.onion, decoded.onion)
        assertArrayEquals(update.pinnedIdentityKey, decoded.pinnedIdentityKey)
        assertTrue(decoded.verified)
        assertTrue(decoded.blocked)
        assertTrue(!decoded.unblocked)
    }

    @Test
    fun `unblocking is sent as its own flag`() {
        val update = WireMessage.ContactUpdate(a.value, "", null, verified = false, blocked = false, unblocked = true)
        val decoded = roundTrip(update) as WireMessage.ContactUpdate
        assertTrue(decoded.unblocked)
        assertTrue(!decoded.blocked)
    }

    @Test
    fun `a wipe key must be 32 bytes and carries its epoch`() {
        val decoded = roundTrip(WireMessage.WipeKey(ByteArray(32) { 1 }, epoch = 7)) as WireMessage.WipeKey
        assertArrayEquals(ByteArray(32) { 1 }, decoded.key)
        assertEquals(7L, decoded.epoch)
        assertTrue(runCatching { roundTrip(WireMessage.WipeKey(ByteArray(8), epoch = 1)) }.isFailure)
        assertTrue(runCatching { roundTrip(WireMessage.WipeKey(ByteArray(32), epoch = -1)) }.isFailure)
    }

    @Test
    fun `device lists refuse duplicates, bad ids and too many devices`() {
        assertTrue(runCatching { DeviceList(1, listOf(Device(1, a), Device(1, b))) }.isFailure)
        assertTrue(runCatching { DeviceList(1, listOf(Device(1, a), Device(2, a))) }.isFailure)
        assertTrue(runCatching { DeviceList(1, listOf(Device(0, a))) }.isFailure)
        assertTrue(runCatching { DeviceList(0, listOf(Device(1, a))) }.isFailure)
        val six = (1..6).map { Device(it, OnionAddress.parse(('a' + it).toString().repeat(56) + ".onion")) }
        assertTrue(runCatching { DeviceList(1, six) }.isFailure)
    }

    @Test
    fun `the next free device number fills gaps`() {
        assertEquals(2, DeviceList(1, listOf(Device(1, a), Device(3, b))).nextFreeId())
    }

    @Test
    fun `link codes round-trip and other codes are told apart`() {
        val invite = LinkInvite.create(a)
        val parsed = LinkInvite.parseOrNull(invite.encode())!!
        assertEquals(a, parsed.onion)
        assertArrayEquals(invite.secret, parsed.secret)
        assertNull(LinkInvite.parseOrNull("${a.value}|abc"))
        assertNull(LinkInvite.parseOrNull("niix-link:1:${a.value}:short"))
    }

    private fun bundle() = LinkBundle(
        account = a.value,
        identityKeyPair = ByteArray(64) { 7 },
        deviceId = 2,
        devices = DeviceList(2, listOf(Device(1, a), Device(2, b))),
        wipeKey = ByteArray(32) { 9 },
        username = "Ann",
        contacts = listOf(LinkBundle.Contact(b.value, "Bo", true, ByteArray(33) { 3 }, true, null)),
        groups = listOf(LinkBundle.Group("g", "Team", 4, listOf(a.value, b.value), listOf(a.value), 0, true)),
        blocked = emptyList(),
    )

    @Test
    fun `a link bundle opens with the code it was sealed for`() {
        val invite = LinkInvite.create(b)
        val opened = LinkBundle.open(invite, bundle().seal(invite))
        assertEquals(2, opened.deviceId)
        assertEquals("Bo", opened.contacts.single().name)
        assertEquals(listOf(a.value), opened.groups.single().admins)
        assertArrayEquals(ByteArray(32) { 9 }, opened.wipeKey)
    }

    @Test
    fun `a link bundle doesn't open with another secret or for another device`() {
        val invite = LinkInvite.create(b)
        val sealed = bundle().seal(invite)
        assertTrue(runCatching { LinkBundle.open(LinkInvite.create(b), sealed) }.isFailure)
        assertTrue(runCatching { LinkBundle.open(LinkInvite(a, invite.secret), sealed) }.isFailure)
        sealed[sealed.size - 1] = (sealed.last() + 1).toByte()
        assertTrue(runCatching { LinkBundle.open(invite, sealed) }.isFailure)
    }
}
