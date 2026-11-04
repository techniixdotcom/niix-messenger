package app.niix.core.model

/**
 * The security rules for group membership changes, as pure functions.
 *
 * These decide whether a peer may change who's in a group and, more importantly, who was just
 * removed and needs their sender keys revoked. Getting it wrong doesn't crash anything; it quietly
 * leaves an ex-member able to read the group. Keeping it free of storage and Android means it can
 * be unit tested: callers read the current state, ask for a decision, and apply it.
 */
object GroupMembershipPolicy {

    sealed class Decision {
        data class Accept(val removedMembers: List<String>) : Decision()

        data class Reject(val reason: String) : Decision()
    }

    /** ASCII ':' between a field's length and its contents. */
    private const val SEPARATOR_BYTE: Byte = 58

    /**
     * A hash identifying an exact group state.
     *
     * Epochs say one state is newer than another, not that it descends from it. Two admins editing
     * the same state at once would both produce epoch N+1, and members could end up permanently
     * disagreeing without noticing. Including the hash of the state being replaced turns membership
     * into a chain: a change is only accepted if it was built on the state the recipient actually
     * has.
     *
     * Members and admins are sorted so the hash depends on the set, not the order, and every field
     * is length-prefixed so values can't be rearranged into a different state with the same hash.
     */
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

    /**
     * Decides whether an incoming membership change should be applied.
     *
     * @param senderIsAdmin whether the sender is currently recorded as an admin (never their own
     * claim)
     * @param currentEpoch epoch of the state this device holds
     * @param incomingEpoch epoch the change claims
     * @param currentMembers who this device thinks is in the group
     * @param incomingMembers who the change says should be in it
     */
    fun evaluate(
        senderIsAdmin: Boolean,
        currentEpoch: Long,
        incomingEpoch: Long,
        currentMembers: Collection<String>,
        incomingMembers: Collection<String>,
        incomingAdmins: Collection<String> = emptyList(),
        sender: String? = null,
        /** Hash of the state this device holds, or null to skip the check. */
        currentStateHash: String? = null,
        /** Hash of the state the sender built on. Null from builds older than state chaining. */
        claimedPrevStateHash: String? = null,
        /**
         * Whether this group has already accepted a chained transition.
         *
         * Once it has, every member is on a build that sends the hash, so an unchained change can
         * only be a replay or a downgrade attempt. It switches on by itself and never switches off.
         */
        chainEstablished: Boolean = false,
    ): Decision {
        // Only admins may change membership, otherwise a removed member could add themselves back
        // or kick others out.
        if (!senderIsAdmin) return Decision.Reject("sender is not an admin of this group")

        // Strictly greater. A replay of an old change at the same epoch is how someone would roll
        // membership back to include a removed member.
        if (incomingEpoch <= currentEpoch) {
            return Decision.Reject("epoch $incomingEpoch is not newer than current $currentEpoch")
        }

        // A list that leaves out the admin who sent it is malformed, or an attempt to orphan the
        // group.
        if (incomingMembers.isEmpty()) return Decision.Reject("membership list is empty")

        // The change has to descend from the state we hold. A missing hash means an older build,
        // which is allowed so upgrading doesn't split existing groups (epoch and admin checks still
        // apply). Once chaining is established for a group, a missing hash is refused; otherwise an
        // attacker could just leave it out to get the weaker rules.
        if (chainEstablished && claimedPrevStateHash == null) {
            return Decision.Reject("unchained transition rejected: this group requires state chaining")
        }
        // Once chaining is on, an unknown local baseline means we can't confirm descent, so refuse
        // rather than falling back to comparing epochs.
        if (chainEstablished && currentStateHash == null) {
            return Decision.Reject("no local state baseline: cannot verify descent")
        }
        if (claimedPrevStateHash != null && currentStateHash != null &&
            claimedPrevStateHash != currentStateHash
        ) {
            return Decision.Reject("transition does not descend from the current group state")
        }

        val incoming = incomingMembers.toSet()

        // Every admin must be a member. An admin outside the member list would hold authority over
        // a group they're invisibly not part of.
        val orphanAdmins = incomingAdmins.filterNot { it in incoming }
        if (orphanAdmins.isNotEmpty()) {
            return Decision.Reject("admin list contains ${orphanAdmins.size} non-member(s)")
        }

        // An admin can't remove themselves with a change they're the authority for; nobody left
        // could account for it.
        if (sender != null && sender !in incoming) {
            return Decision.Reject("sender is not present in the membership list they sent")
        }

        val removed = currentMembers.filterNot { it in incoming }.distinct()
        return Decision.Accept(removedMembers = removed)
    }

    /**
     * Whether an invite to a group we've never seen is consistent enough to store.
     *
     * There's no existing state to compare against, and if the user accepts, the invite's lists
     * become the group. So the lists have to make sense on their own, or an invite could give
     * authority to non-members. Checked on arrival so a bad invite is never shown to the user.
     */
    fun isAcceptableInvite(
        inviter: String,
        invitee: String,
        members: Collection<String>,
        admins: Collection<String>,
    ): Boolean {
        if (members.isEmpty()) return false
        val memberSet = members.toSet()
        // The inviter has to be in the group.
        if (inviter !in memberSet) return false
        // Only an admin can invite.
        if (inviter !in admins.toSet()) return false
        // Every admin must be a member.
        if (admins.any { it !in memberSet }) return false
        // And the recipient has to be in it too.
        if (invitee !in memberSet) return false
        return true
    }

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
