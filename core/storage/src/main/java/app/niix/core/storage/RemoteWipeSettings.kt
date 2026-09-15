package app.niix.core.storage

import java.security.MessageDigest


/**
 * Remote wipe config.
 *
 * A wipe is accepted if it comes from a nominated contact over an authenticated session. That
 * is the whole check. There used to be a shared token as well, dropped once nominees get told
 * they are nominated: sending the request already needs their device, and so does the token.
 *
 * Two lists, opposite directions. nominated() = who can wipe us. canWipe() = who has said we
 * can wipe them. Mixing them up would either offer to wipe strangers or refuse people who asked.
 */
class RemoteWipeSettings internal constructor(private val settings: SettingsStore) {

    fun isEnabled(): Boolean = settings.getBool(KEY_ENABLED, false)

    fun enable() {
        settings.setBool(KEY_ENABLED, true)
    }

    /** Turns it off and forgets every nomination, so re-enabling starts from nobody rather than
     * silently restoring a list the user may no longer agree with. */
    fun disable() {
        settings.setBool(KEY_ENABLED, false)
        settings.remove(KEY_NOMINATED)
    }

    fun nominated(): List<String> =
        settings.getString(KEY_NOMINATED)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    /** [identityKey] is pinned with the nomination so a later identity change revokes it. */
    fun nominate(onion: String, identityKey: ByteArray) {
        settings.setString(identityKeyOf(onion), identityKey.joinToString("") { "%02x".format(it) })
        val current = nominated().toMutableList()
        if (onion in current) return
        current.add(onion)
        settings.setString(KEY_NOMINATED, current.joinToString("\n"))
    }

    /** Removing a nomination has to take effect immediately, it is what someone reaches for
     * when a contact's device may be compromised. */
    fun revoke(onion: String) {
        settings.remove(identityKeyOf(onion))
        val remaining = nominated().filter { it != onion }
        if (remaining.isEmpty()) {
            settings.remove(KEY_NOMINATED)
        } else {
            settings.setString(KEY_NOMINATED, remaining.joinToString("\n"))
        }
    }

    /**
     * Whether a wipe request should be honoured.
     *
     * The sender being nominated *is* the authorisation. A shared code used to be required as
     * well, but it added nothing once the nominee is told they are nominated: the request already
     * arrives over an authenticated Signal session, so presenting it requires control of the
     * nominated contact's device, and anyone with that also has whatever code was stored on it.
     * A second factor that travels with the first is not a second factor.
     */
    /**
     * Whether a wipe from [fromOnion] should be honoured.
     *
     * [currentIdentityKey] is the contact's identity key as the session sees it now. It must
     * match the key recorded when they were nominated. An onion address alone is not enough:
     * under trust-on-first-use a contact's identity can change while the address stays the same,
     * and a permission to destroy every message on the device should not survive that. If the
     * person on the other end is no longer who was nominated, the nomination does not apply to
     * them.
     */
    fun shouldWipe(fromOnion: String, currentIdentityKey: ByteArray?): Boolean {
        if (!isEnabled()) return false
        if (fromOnion !in nominated()) return false
        val recorded = nominatedIdentityKey(fromOnion) ?: return false
        if (currentIdentityKey == null) return false
        return MessageDigest.isEqual(recorded, currentIdentityKey)
    }

    private fun identityKeyOf(onion: String) = "remote_wipe_idkey:$onion"

    private fun nominatedIdentityKey(onion: String): ByteArray? =
        settings.getString(identityKeyOf(onion))?.let { hex ->
            runCatching { hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
        }

    /**
     * Contacts who have nominated *this* device to erase theirs.
     *
     * Distinct from nominated(), which is the other direction. Kept separately because they
     * answer different questions and confusing them would either offer to wipe devices that
     * never asked, or refuse ones that did.
     */
    fun canWipe(): List<String> =
        settings.getString(KEY_CAN_WIPE)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun setCanWipe(onion: String, allowed: Boolean) {
        val current = canWipe().toMutableList()
        if (allowed) {
            if (onion in current) return
            current.add(onion)
        } else {
            if (!current.remove(onion)) return
        }
        if (current.isEmpty()) {
            settings.remove(KEY_CAN_WIPE)
        } else {
            settings.setString(KEY_CAN_WIPE, current.joinToString("\n"))
        }
    }

    private companion object {
        const val KEY_ENABLED = "remote_wipe_enabled"
        const val KEY_CAN_WIPE = "remote_wipe_can_wipe"
        const val KEY_NOMINATED = "remote_wipe_nominated"
    }
}
