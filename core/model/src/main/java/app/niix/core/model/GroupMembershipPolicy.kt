package app.niix.core.model

/**
 * The security decisions behind a group membership change, as pure functions.
 *
 * These rules are the most consequential in the app: they decide whether a remote peer is allowed
 * to alter who is in a group, and -- critically -- who has just been removed and therefore whose
 * sender-key material must be revoked. A mistake here doesn't crash or throw; it silently leaves
 * an ex-member able to read the group's traffic, which is exactly the failure nobody notices.
 *
 * They live here, separate from storage and Android, so they can actually be tested. Previously
 * this logic was interleaved with database calls inside a message handler, which meant verifying
 * it required three physical devices and a lot of hope. Everything below is deliberately free of
 * I/O: callers read the current state, ask for a decision, then apply it.
 */
object GroupMembershipPolicy {

    sealed class Decision {
        /**
         * The change is legitimate and should be applied. [removedMembers] is who must lose
         * access -- the callers' cue to revoke their sender keys. Empty when nobody was removed
         * (a pure addition or a rename).
         */
        data class Accept(val removedMembers: List<String>) : Decision()

        /** The change must be ignored entirely. [reason] exists for diagnostics, not for
         * showing to the sender -- telling a rejected peer why they were rejected just helps
         * them craft a better attempt. */
        data class Reject(val reason: String) : Decision()
    }

    /**
     * Decides whether an incoming membership change should be applied.
     *
     * @param senderIsAdmin whether the peer that sent this change is a *currently recorded*
     *   admin of this group. Never take the sender's word for this.
     * @param currentEpoch the epoch of the group state this device already holds.
     * @param incomingEpoch the epoch claimed by the incoming change.
     * @param currentMembers who this device currently believes is in the group.
     * @param incomingMembers who the change says should be in the group.
     */
    /**
     * A hash identifying an exact group state.
     *
     * Epochs establish that one state is newer than another, but not that it *descends from* it.
     * Two admins acting on the same state concurrently both produce epoch N+1, each valid on its
     * own terms, and members end up permanently disagreeing about who is in the group -- with no
     * way to notice, since both transitions look correct in isolation.
     *
     * Carrying the hash of the state being replaced turns membership into a chain: a transition
     * is only accepted if it was built on the state the recipient actually holds. Divergence
     * then surfaces as a rejected update rather than silently taking effect.
     *
     * Members and admins are sorted so the hash depends on the set, not on the order they
     * happened to be listed in, and each field is length-prefixed so no combination of values
     * can be rearranged into a different state with the same hash.
     */
    /** ASCII ':' -- the separator between a field's length and its contents. */
    private const val SEPARATOR_BYTE: Byte = 58

    fun stateHash(
        conversationId: String,
        epoch: Long,
        members: Collection<String>,
        admins: Collection<String>,
    ): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(SEPARATOR_BYTE)
            digest.update(bytes)
        }
        field(conversationId)
        field(epoch.toString())
        members.toSortedSet().forEach { field("m:$it") }
        admins.toSortedSet().forEach { field("a:$it") }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun evaluate(
        senderIsAdmin: Boolean,
        currentEpoch: Long,
        incomingEpoch: Long,
        currentMembers: Collection<String>,
        incomingMembers: Collection<String>,
        incomingAdmins: Collection<String> = emptyList(),
        sender: String? = null,
        /** Hash of the state this device currently holds, or null to skip the check. */
        currentStateHash: String? = null,
        /** Hash of the state the sender built this transition on. Null when the sender is on a
         * build that predates state chaining -- see the migration note below. */
        claimedPrevStateHash: String? = null,
        /**
         * Whether this group has already accepted a chained transition.
         *
         * Once it has, every member demonstrably runs a build that sends the hash, so an
         * unchained transition can only be an old replay or a deliberate downgrade -- and is
         * rejected permanently. This is a one-way ratchet per group: it turns on by itself the
         * first time a chained update arrives and never turns off, so the compatibility
         * exception disappears without anyone having to decide when to remove it.
         */
        chainEstablished: Boolean = false,
    ): Decision {
        // Only an admin may change membership. Without this, any member -- or anyone who can get
        // a message accepted at all -- could add themselves back after removal, or eject others.
        if (!senderIsAdmin) return Decision.Reject("sender is not an admin of this group")

        // Strictly greater, not >=. Replaying a previously valid change at the same epoch is
        // exactly how an attacker would try to roll membership back to a state that included
        // someone since removed, and equal epochs are indistinguishable from that replay.
        if (incomingEpoch <= currentEpoch) {
            return Decision.Reject("epoch $incomingEpoch is not newer than current $currentEpoch")
        }

        // A membership list that doesn't contain the admin who sent it is malformed at best and
        // an attempt to orphan the group at worst.
        if (incomingMembers.isEmpty()) return Decision.Reject("membership list is empty")

        // The transition must descend from the state this device actually holds. A null claimed
        // hash means the sender is on a build from before state chaining existed; that is
        // accepted so an upgrade does not partition existing groups, and it stops being possible
        // once everyone has updated. It is a deliberate, temporary weakening -- the epoch and
        // authorisation checks still apply to those transitions.
        // Once chaining is established for a group, a transition without a hash is refused
        // outright. Left permanently permissive, the compatibility path is a downgrade oracle:
        // an attacker only has to omit the field to get the weaker rules applied.
        if (chainEstablished && claimedPrevStateHash == null) {
            return Decision.Reject("unchained transition rejected: this group requires state chaining")
        }
        // A missing local baseline is a fail-closed condition once chaining is in force, not a
        // reason to fall back to comparing epochs. The invariant is "this transition descends
        // from the state I actually hold" -- if what I hold is unknown, that cannot be
        // established, and accepting anyway would silently reinstate exactly the weaker rule
        // chaining exists to replace.
        if (chainEstablished && currentStateHash == null) {
            return Decision.Reject("no local state baseline: cannot verify descent")
        }
        if (claimedPrevStateHash != null && currentStateHash != null &&
            claimedPrevStateHash != currentStateHash
        ) {
            return Decision.Reject("transition does not descend from the current group state")
        }

        val incoming = incomingMembers.toSet()

        // Every admin must also be a member. An admin who isn't in the member list is a
        // contradiction: they'd hold authority over a group they aren't part of, and would keep
        // that authority through later changes while being invisible as a participant.
        val orphanAdmins = incomingAdmins.filterNot { it in incoming }
        if (orphanAdmins.isNotEmpty()) {
            return Decision.Reject("admin list contains ${orphanAdmins.size} non-member(s)")
        }

        // An admin cannot use a membership change to remove themselves while still being the
        // authority for it -- that leaves the group in a state nobody present can account for.
        if (sender != null && sender !in incoming) {
            return Decision.Reject("sender is not present in the membership list they sent")
        }

        val removed = currentMembers.filterNot { it in incoming }.distinct()
        return Decision.Accept(removedMembers = removed)
    }

    /**
     * Whether an invitation to a group this device has never seen is coherent enough to store.
     *
     * A first invite is different from a membership change: there is no existing state to check
     * it against, and if the user accepts, whatever it contains becomes the group's membership
     * and admin list. So the lists themselves have to be internally consistent, or an invite can
     * hand authority to people who aren't participants and lock the recipient into a group whose
     * rules were written by the attacker.
     *
     * Checked here rather than at acceptance time so a malformed invite is never presented to
     * the user as a legitimate choice in the first place.
     */
    fun isAcceptableInvite(
        inviter: String,
        invitee: String,
        members: Collection<String>,
        admins: Collection<String>,
    ): Boolean {
        if (members.isEmpty()) return false
        val memberSet = members.toSet()
        // The inviter must be part of the group they are inviting into.
        if (inviter !in memberSet) return false
        // Only an admin may invite; an invite from a non-admin is either malformed or an attempt
        // to manufacture a group whose rules the sender doesn't actually hold authority over.
        if (inviter !in admins.toSet()) return false
        // Every admin must be a member -- otherwise the group would grant authority to someone
        // who isn't in it, and that authority would persist through later membership changes.
        if (admins.any { it !in memberSet }) return false
        // The recipient must actually be in the group they are being invited to.
        if (invitee !in memberSet) return false
        return true
    }

    /**
     * Whether [actor] may perform a membership-scoped action in a conversation -- changing the
     * disappearing timer, distributing sender keys, sending group ciphertext, or affecting
     * message expiry via a read receipt.
     *
     * For a direct conversation the id *is* the peer's onion, so the only party who can act is
     * that peer. For a group, the actor must be a current member: someone removed a moment ago
     * has no row and is correctly refused. An unknown conversation authorises nobody -- there is
     * nothing to be a member of.
     */
    fun isAuthorizedActor(
        conversationExists: Boolean,
        isGroup: Boolean,
        conversationId: String,
        actor: String,
        isCurrentMember: Boolean,
    ): Boolean {
        if (!conversationExists) return false
        return if (isGroup) isCurrentMember else conversationId == actor
    }
}
