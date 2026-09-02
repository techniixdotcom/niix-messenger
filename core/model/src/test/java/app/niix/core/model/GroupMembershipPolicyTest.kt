package app.niix.core.model

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial tests for [GroupMembershipPolicy].
 *
 * These cover the property the whole group-security rebuild rests on: **when someone is removed
 * from a group, they are identified as removed, so their sender keys get revoked.** If that
 * silently fails, an ex-member keeps reading the group's messages and nothing anywhere reports a
 * problem. Until this logic was extracted it could not be tested at all.
 */
class GroupMembershipPolicyTest {

    private val alice = "a".repeat(56) + ".onion"
    private val bob = "b".repeat(56) + ".onion"
    private val carol = "c".repeat(56) + ".onion"
    private val mallory = "m".repeat(56) + ".onion"

    private fun accept(d: GroupMembershipPolicy.Decision): GroupMembershipPolicy.Decision.Accept {
        assertTrue("expected Accept but got $d", d is GroupMembershipPolicy.Decision.Accept)
        return d as GroupMembershipPolicy.Decision.Accept
    }

    @Test
    fun `removing a member reports exactly that member as removed`() {
        val d = accept(
            GroupMembershipPolicy.evaluate(
                senderIsAdmin = true,
                currentEpoch = 4,
                incomingEpoch = 5,
                currentMembers = listOf(alice, bob, carol),
                incomingMembers = listOf(alice, bob),
            ),
        )
        // This list is what drives sender-key revocation. If carol is missing from it, carol
        // keeps being able to decrypt group traffic after being kicked out.
        assertEquals(listOf(carol), d.removedMembers)
    }

    @Test
    fun `removing several members reports all of them`() {
        val d = accept(
            GroupMembershipPolicy.evaluate(
                senderIsAdmin = true,
                currentEpoch = 1,
                incomingEpoch = 2,
                currentMembers = listOf(alice, bob, carol, mallory),
                incomingMembers = listOf(alice),
            ),
        )
        assertEquals(setOf(bob, carol, mallory), d.removedMembers.toSet())
    }

    @Test
    fun `adding a member removes nobody`() {
        val d = accept(
            GroupMembershipPolicy.evaluate(
                senderIsAdmin = true,
                currentEpoch = 1,
                incomingEpoch = 2,
                currentMembers = listOf(alice, bob),
                incomingMembers = listOf(alice, bob, carol),
            ),
        )
        assertTrue("an addition must not revoke anyone's keys", d.removedMembers.isEmpty())
    }

    @Test
    fun `a non-admin cannot change membership at all`() {
        // The attack: an ordinary member ejects everyone else, or re-adds themselves after
        // being removed.
        val d = GroupMembershipPolicy.evaluate(
            senderIsAdmin = false,
            currentEpoch = 1,
            incomingEpoch = 99,
            currentMembers = listOf(alice, bob, carol),
            incomingMembers = listOf(mallory),
        )
        assertTrue(d is GroupMembershipPolicy.Decision.Reject)
    }

    @Test
    fun `an old epoch is rejected -- no rolling membership back`() {
        // The attack: replay a captured older invite from before someone was removed, to put
        // them back in the group.
        val d = GroupMembershipPolicy.evaluate(
            senderIsAdmin = true,
            currentEpoch = 10,
            incomingEpoch = 3,
            currentMembers = listOf(alice, bob),
            incomingMembers = listOf(alice, bob, mallory),
        )
        assertTrue(d is GroupMembershipPolicy.Decision.Reject)
    }

    @Test
    fun `an equal epoch is rejected -- replay of the current state is not accepted`() {
        // Equal epochs are indistinguishable from a replay, so >= would be a real hole.
        val d = GroupMembershipPolicy.evaluate(
            senderIsAdmin = true,
            currentEpoch = 7,
            incomingEpoch = 7,
            currentMembers = listOf(alice, bob),
            incomingMembers = listOf(alice, bob, mallory),
        )
        assertTrue(d is GroupMembershipPolicy.Decision.Reject)
    }

    @Test
    fun `an empty membership list is rejected`() {
        val d = GroupMembershipPolicy.evaluate(
            senderIsAdmin = true,
            currentEpoch = 1,
            incomingEpoch = 2,
            currentMembers = listOf(alice, bob),
            incomingMembers = emptyList(),
        )
        assertTrue(d is GroupMembershipPolicy.Decision.Reject)
    }

    @Test
    fun `duplicate entries in the current list never produce duplicate revocations`() {
        val d = accept(
            GroupMembershipPolicy.evaluate(
                senderIsAdmin = true,
                currentEpoch = 1,
                incomingEpoch = 2,
                currentMembers = listOf(alice, carol, carol, bob),
                incomingMembers = listOf(alice, bob),
            ),
        )
        assertEquals(listOf(carol), d.removedMembers)
    }

    @Test
    fun `re-adding a removed member in the same change does not revoke them`() {
        // carol is present in both lists; she must not appear as removed just because the
        // membership list was rewritten.
        val d = accept(
            GroupMembershipPolicy.evaluate(
                senderIsAdmin = true,
                currentEpoch = 1,
                incomingEpoch = 2,
                currentMembers = listOf(alice, bob, carol),
                incomingMembers = listOf(carol, bob, alice),
            ),
        )
        assertTrue("reordering is not a membership change", d.removedMembers.isEmpty())
    }

    @Test
    fun `randomised membership changes always revoke exactly the set difference`() {
        // The invariant that actually matters, checked against an independent computation over
        // thousands of random shapes: everyone who was in the group and is no longer in it must
        // be reported as removed -- no more, no fewer.
        val random = Random(20260901L)
        val pool = (1..12).map { "%056d".format(it) + ".onion" }
        repeat(20_000) {
            val current = pool.filter { random.nextBoolean() }
            val incoming = pool.filter { random.nextBoolean() }
            val d = GroupMembershipPolicy.evaluate(
                senderIsAdmin = true,
                currentEpoch = 1,
                incomingEpoch = 2,
                currentMembers = current,
                incomingMembers = incoming,
            )
            if (incoming.isEmpty()) {
                assertTrue(d is GroupMembershipPolicy.Decision.Reject)
                return@repeat
            }
            val expected = current.toSet() - incoming.toSet()
            assertEquals(expected, accept(d).removedMembers.toSet())
        }
    }
}

/** Tests for who may perform membership-scoped actions -- the check that stops a removed member
 * changing a group's disappearing timer or having their traffic accepted. */
class AuthorizedActorTest {

    private val peer = "p".repeat(56) + ".onion"
    private val other = "o".repeat(56) + ".onion"
    private val groupId = "group-1"

    @Test
    fun `unknown conversation authorises nobody`() {
        assertFalse(
            GroupMembershipPolicy.isAuthorizedActor(
                conversationExists = false, isGroup = true, conversationId = groupId,
                actor = peer, isCurrentMember = true,
            ),
        )
    }

    @Test
    fun `direct conversation only authorises the peer it belongs to`() {
        assertTrue(
            GroupMembershipPolicy.isAuthorizedActor(
                conversationExists = true, isGroup = false, conversationId = peer,
                actor = peer, isCurrentMember = false,
            ),
        )
        // Someone else cannot act on a conversation that isn't theirs, even if they somehow
        // got a message accepted.
        assertFalse(
            GroupMembershipPolicy.isAuthorizedActor(
                conversationExists = true, isGroup = false, conversationId = peer,
                actor = other, isCurrentMember = true,
            ),
        )
    }

    @Test
    fun `group authorises current members and refuses everyone else`() {
        assertTrue(
            GroupMembershipPolicy.isAuthorizedActor(
                conversationExists = true, isGroup = true, conversationId = groupId,
                actor = peer, isCurrentMember = true,
            ),
        )
        // The removed-member case: no membership row, so no authority -- this is what stops an
        // ex-member's timer changes and receipts being honoured.
        assertFalse(
            GroupMembershipPolicy.isAuthorizedActor(
                conversationExists = true, isGroup = true, conversationId = groupId,
                actor = peer, isCurrentMember = false,
            ),
        )
    }
}
