package app.niix.core.model

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial tests for [GroupMembershipPolicy]. The main thing they protect: when someone is
 * removed from a group they're reported as removed, so their sender keys get revoked. If that
 * fails, an ex-member keeps reading the group and nothing complains.
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
        // This list drives sender-key revocation. If carol isn't in it, she can still decrypt the
        // group after being kicked out.
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
        // Attack: an ordinary member kicks everyone else out, or re-adds themselves after removal.
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
        // Attack: replay an old invite from before someone was removed to put them back.
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
        // An equal epoch looks exactly like a replay, so >= would be a hole.
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
        // carol is in both lists; rewriting the list mustn't make her look removed.
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
    fun `an admin who is not in the member list is rejected`() {
        // Otherwise an admin could control a group they're not visibly in.
        val d = GroupMembershipPolicy.evaluate(
            senderIsAdmin = true,
            currentEpoch = 1,
            incomingEpoch = 2,
            currentMembers = listOf(alice, bob),
            incomingMembers = listOf(alice, bob),
            incomingAdmins = listOf(mallory),
            sender = alice,
        )
        assertTrue(d is GroupMembershipPolicy.Decision.Reject)
    }

    @Test
    fun `a sender who omits themselves from the membership list is rejected`() {
        val d = GroupMembershipPolicy.evaluate(
            senderIsAdmin = true,
            currentEpoch = 1,
            incomingEpoch = 2,
            currentMembers = listOf(alice, bob, carol),
            incomingMembers = listOf(bob, carol),
            incomingAdmins = listOf(bob),
            sender = alice,
        )
        assertTrue(d is GroupMembershipPolicy.Decision.Reject)
    }

    @Test
    fun `a consistent admin list is accepted`() {
        val d = accept(
            GroupMembershipPolicy.evaluate(
                senderIsAdmin = true,
                currentEpoch = 1,
                incomingEpoch = 2,
                currentMembers = listOf(alice, bob, carol),
                incomingMembers = listOf(alice, bob),
                incomingAdmins = listOf(alice),
                sender = alice,
            ),
        )
        assertEquals(listOf(carol), d.removedMembers)
    }

    @Test
    fun `a coherent invite is accepted`() {
        assertTrue(
            GroupMembershipPolicy.isAcceptableInvite(
                inviter = alice, invitee = bob,
                members = listOf(alice, bob), admins = listOf(alice),
            ),
        )
    }

    @Test
    fun `an invite from someone not in the group is rejected`() {
        assertFalse(
            GroupMembershipPolicy.isAcceptableInvite(
                inviter = mallory, invitee = bob,
                members = listOf(alice, bob), admins = listOf(alice),
            ),
        )
    }

    @Test
    fun `an invite from a non-admin is rejected`() {
        // Otherwise any member could forge invites that define who's in charge.
        assertFalse(
            GroupMembershipPolicy.isAcceptableInvite(
                inviter = bob, invitee = carol,
                members = listOf(alice, bob, carol), admins = listOf(alice),
            ),
        )
    }

    @Test
    fun `an invite granting admin to a non-member is rejected`() {
        assertFalse(
            GroupMembershipPolicy.isAcceptableInvite(
                inviter = alice, invitee = bob,
                members = listOf(alice, bob), admins = listOf(alice, mallory),
            ),
        )
    }

    @Test
    fun `an invite that does not include the recipient is rejected`() {
        assertFalse(
            GroupMembershipPolicy.isAcceptableInvite(
                inviter = alice, invitee = carol,
                members = listOf(alice, bob), admins = listOf(alice),
            ),
        )
    }

    @Test
    fun `state hash ignores ordering but changes with membership`() {
        val a = GroupMembershipPolicy.stateHash("g", 3, listOf(alice, bob), listOf(alice))
        val reordered = GroupMembershipPolicy.stateHash("g", 3, listOf(bob, alice), listOf(alice))
        assertEquals("ordering must not change the hash", a, reordered)
        assertTrue("adding a member must change it", a != GroupMembershipPolicy.stateHash("g", 3, listOf(alice, bob, carol), listOf(alice)))
        assertTrue("changing admins must change it", a != GroupMembershipPolicy.stateHash("g", 3, listOf(alice, bob), listOf(alice, bob)))
        assertTrue("changing epoch must change it", a != GroupMembershipPolicy.stateHash("g", 4, listOf(alice, bob), listOf(alice)))
        assertTrue("a member and an admin entry must not be interchangeable",
            GroupMembershipPolicy.stateHash("g", 1, listOf("x"), emptyList()) !=
                GroupMembershipPolicy.stateHash("g", 1, emptyList(), listOf("x")))
    }

    @Test
    fun `a transition that does not descend from the held state is rejected`() {
        // Two admins acting at once both produce epoch N+1, but only one descends from our state.
        // Without the chain both would be accepted and members would drift apart.
        val held = GroupMembershipPolicy.stateHash("g", 5, listOf(alice, bob, carol), listOf(alice))
        val divergent = GroupMembershipPolicy.stateHash("g", 5, listOf(alice, bob), listOf(alice))
        val d = GroupMembershipPolicy.evaluate(
            senderIsAdmin = true, currentEpoch = 5, incomingEpoch = 6,
            currentMembers = listOf(alice, bob, carol), incomingMembers = listOf(alice, bob),
            incomingAdmins = listOf(alice), sender = alice,
            currentStateHash = held, claimedPrevStateHash = divergent,
        )
        assertTrue(d is GroupMembershipPolicy.Decision.Reject)
    }

    @Test
    fun `a transition that does descend is accepted`() {
        val held = GroupMembershipPolicy.stateHash("g", 5, listOf(alice, bob, carol), listOf(alice))
        val d = accept(
            GroupMembershipPolicy.evaluate(
                senderIsAdmin = true, currentEpoch = 5, incomingEpoch = 6,
                currentMembers = listOf(alice, bob, carol), incomingMembers = listOf(alice, bob),
                incomingAdmins = listOf(alice), sender = alice,
                currentStateHash = held, claimedPrevStateHash = held,
            ),
        )
        assertEquals(listOf(carol), d.removedMembers)
    }

    @Test
    fun `a sender on an older build with no hash is still accepted`() {
        // During migration changes carry no hash and still have to work; epoch and admin checks do
        // the job.
        val held = GroupMembershipPolicy.stateHash("g", 5, listOf(alice, bob), listOf(alice))
        val d = GroupMembershipPolicy.evaluate(
            senderIsAdmin = true, currentEpoch = 5, incomingEpoch = 6,
            currentMembers = listOf(alice, bob), incomingMembers = listOf(alice, bob),
            incomingAdmins = listOf(alice), sender = alice,
            currentStateHash = held, claimedPrevStateHash = null,
        )
        assertTrue(d is GroupMembershipPolicy.Decision.Accept)
    }

    @Test
    fun `once chaining is established an unchained transition is rejected`() {
        // Downgrade attempt: leave out prevStateHash to get the weaker rules.
        val held = GroupMembershipPolicy.stateHash("g", 5, listOf(alice, bob), listOf(alice))
        val d = GroupMembershipPolicy.evaluate(
            senderIsAdmin = true, currentEpoch = 5, incomingEpoch = 6,
            currentMembers = listOf(alice, bob), incomingMembers = listOf(alice, bob),
            incomingAdmins = listOf(alice), sender = alice,
            currentStateHash = held, claimedPrevStateHash = null,
            chainEstablished = true,
        )
        assertTrue(d is GroupMembershipPolicy.Decision.Reject)
    }

    @Test
    fun `before chaining is established an unchained transition is still accepted`() {
        // Groups that have never seen a chained update keep working.
        val held = GroupMembershipPolicy.stateHash("g", 5, listOf(alice, bob), listOf(alice))
        val d = GroupMembershipPolicy.evaluate(
            senderIsAdmin = true, currentEpoch = 5, incomingEpoch = 6,
            currentMembers = listOf(alice, bob), incomingMembers = listOf(alice, bob),
            incomingAdmins = listOf(alice), sender = alice,
            currentStateHash = held, claimedPrevStateHash = null,
            chainEstablished = false,
        )
        assertTrue(d is GroupMembershipPolicy.Decision.Accept)
    }

    @Test
    fun `a missing local baseline fails closed once chaining is established`() {
        // If we don't know our own state we can't confirm descent, and accepting would quietly fall
        // back to epoch-only.
        val d = GroupMembershipPolicy.evaluate(
            senderIsAdmin = true, currentEpoch = 5, incomingEpoch = 6,
            currentMembers = listOf(alice, bob), incomingMembers = listOf(alice, bob),
            incomingAdmins = listOf(alice), sender = alice,
            currentStateHash = null,
            claimedPrevStateHash = GroupMembershipPolicy.stateHash("g", 5, listOf(alice, bob), listOf(alice)),
            chainEstablished = true,
        )
        assertTrue(d is GroupMembershipPolicy.Decision.Reject)
    }

    @Test
    fun `an admin cannot remove every other admin and keep only themselves without being one`() {
        // Sender-in-list and admins-are-members together. Either check alone misses a list that
        // names the sender as the only admin but leaves them out of the members.
        val d = GroupMembershipPolicy.evaluate(
            senderIsAdmin = true, currentEpoch = 1, incomingEpoch = 2,
            currentMembers = listOf(alice, bob, carol), incomingMembers = listOf(bob, carol),
            incomingAdmins = listOf(alice), sender = alice,
        )
        assertTrue(d is GroupMembershipPolicy.Decision.Reject)
    }

    @Test
    fun `epoch far in the future is still accepted if it descends correctly`() {
        // A big jump isn't suspicious in itself (a device that was offline skips epochs). Descent
        // is what matters.
        val held = GroupMembershipPolicy.stateHash("g", 5, listOf(alice, bob), listOf(alice))
        val d = GroupMembershipPolicy.evaluate(
            senderIsAdmin = true, currentEpoch = 5, incomingEpoch = Long.MAX_VALUE,
            currentMembers = listOf(alice, bob), incomingMembers = listOf(alice, bob),
            incomingAdmins = listOf(alice), sender = alice,
            currentStateHash = held, claimedPrevStateHash = held, chainEstablished = true,
        )
        assertTrue(d is GroupMembershipPolicy.Decision.Accept)
    }

    @Test
    fun `a negative epoch is rejected`() {
        val d = GroupMembershipPolicy.evaluate(
            senderIsAdmin = true, currentEpoch = 5, incomingEpoch = -1,
            currentMembers = listOf(alice, bob), incomingMembers = listOf(alice, bob),
            incomingAdmins = listOf(alice), sender = alice,
        )
        assertTrue(d is GroupMembershipPolicy.Decision.Reject)
    }

    @Test
    fun `state hash is not confused by a member whose name contains the separator`() {
        // Length prefixes stop a crafted member name from making two different states hash the
        // same.
        val a = GroupMembershipPolicy.stateHash("g", 1, listOf("x:y"), listOf("x"))
        val b = GroupMembershipPolicy.stateHash("g", 1, listOf("x", "y"), listOf("x"))
        assertTrue("distinct states must not collide", a != b)
    }

    @Test
    fun `state hash distinguishes conversation ids`() {
        val a = GroupMembershipPolicy.stateHash("group-1", 1, listOf(alice), listOf(alice))
        val b = GroupMembershipPolicy.stateHash("group-2", 1, listOf(alice), listOf(alice))
        assertTrue(a != b)
    }

    @Test
    fun `an invite naming the same contact twice still resolves consistently`() {
        // Duplicates aren't an attack, but they mustn't produce a member set different from what
        // the hash covered.
        val d = accept(
            GroupMembershipPolicy.evaluate(
                senderIsAdmin = true, currentEpoch = 1, incomingEpoch = 2,
                currentMembers = listOf(alice, bob, carol),
                incomingMembers = listOf(alice, alice, bob),
                incomingAdmins = listOf(alice), sender = alice,
            ),
        )
        assertEquals(listOf(carol), d.removedMembers)
    }

    @Test
    fun `randomised membership changes always revoke exactly the set difference`() {
        // Checked against an independent computation over thousands of random cases: everyone who
        // was in and isn't any more is reported as removed, no more and no fewer.
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

/**
 * Who may act on a conversation. This is what stops a removed member changing a group's timer or
 * having receipts accepted.
 */
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
        // Nobody else can act on a direct conversation that isn't theirs.
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
        // Removed member: no membership row, so no authority.
        assertFalse(
            GroupMembershipPolicy.isAuthorizedActor(
                conversationExists = true, isGroup = true, conversationId = groupId,
                actor = peer, isCurrentMember = false,
            ),
        )
    }
}
