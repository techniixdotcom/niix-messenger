package app.niix.core.storage

import java.security.MessageDigest

/**
 * Remote wipe settings.
 *
 * A wipe is accepted if it comes from a nominated contact over an authenticated session; that's the
 * whole check.
 *
 * There are two lists going in opposite directions: nominated() is who can wipe us, canWipe() is
 * who has said we can wipe them. Mixing them up would either offer to wipe strangers or refuse
 * people who asked.
 */
class RemoteWipeSettings internal constructor(private val settings: KeyValueStore) {

    fun isEnabled(): Boolean = settings.getBool(KEY_ENABLED, false)

    fun enable() {
        settings.setBool(KEY_ENABLED, true)
    }

    /** Turns it off and forgets every nomination, so turning it back on starts from nobody. */
    fun disable() {
        settings.setBool(KEY_ENABLED, false)
        nominated().forEach { settings.remove(identityKeyOf(it)) }
        settings.remove(KEY_NOMINATED)
    }

    fun nominated(): List<String> =
        settings.getString(KEY_NOMINATED)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    /** [identityKey] is stored with the nomination, so a later identity change revokes it. */
    fun nominate(onion: String, identityKey: ByteArray) {
        settings.setString(identityKeyOf(onion), identityKey.joinToString("") { "%02x".format(it) })
        val current = nominated().toMutableList()
        if (onion in current) return
        current.add(onion)
        settings.setString(KEY_NOMINATED, current.joinToString("\n"))
    }

    /**
     * Takes effect immediately; this is what you reach for when a contact's phone might be
     * compromised.
     */
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
     * Whether a wipe from [fromOnion] should be honoured.
     *
     * Being nominated is the authorisation: the request arrives over an authenticated Signal
     * session, so sending it already requires the nominee's device. [currentIdentityKey] must also
     * match the key recorded at nomination, because an identity can change while the onion address
     * stays the same, and permission to erase everything shouldn't carry over.
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
     * Contacts who have nominated this device to erase theirs. The opposite direction from
     * nominated().
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
