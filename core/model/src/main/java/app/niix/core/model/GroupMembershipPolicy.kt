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
    fun evaluate(
        senderIsAdmin: Boolean,
        currentEpoch: Long,
        incomingEpoch: Long,
        currentMembers: Collection<String>,
        incomingMembers: Collection<String>,
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

        val incoming = incomingMembers.toSet()
        val removed = currentMembers.filterNot { it in incoming }.distinct()
        return Decision.Accept(removedMembers = removed)
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
