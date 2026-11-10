package app.niix.core.storage

import java.security.MessageDigest

/**
 * Who voice calls are allowed with. Off for everyone until the user turns it on for a contact.
 *
 * Two directions, kept apart on purpose, like RemoteWipeSettings:
 *
 * - [allowedByMe]: the user allowed calls with this contact. Recorded with the contact's identity
 *   key at that moment, so a contact whose key changes is no longer allowed until the user says so
 *   again. Permission to reach someone's microphone shouldn't carry over to a different key.
 * - [allowedByThem]: the contact told us, over the Signal session, that they allow calls with us.
 *
 * A call can only be placed when both are true, and an incoming call is only rung when the first
 * is. Either person turning it off is enough to stop calls between them.
 */
class CallPermissions internal constructor(private val settings: KeyValueStore) {

    /** Whether the user allowed calls with [onion], under the identity key they have now. */
    fun allowedByMe(onion: String, currentIdentityKey: ByteArray?): Boolean {
        if (currentIdentityKey == null) return false
        val recorded = recordedKey(onion) ?: return false
        return MessageDigest.isEqual(recorded, currentIdentityKey)
    }

    /** Whether the user turned calls on for [onion] at all, whatever key they have now. */
    fun isAllowedByMeRecorded(onion: String): Boolean = settings.getString(allowKey(onion)) != null

    /** Allows calls with [onion], bound to [identityKey]. */
    fun allow(onion: String, identityKey: ByteArray) {
        require(identityKey.isNotEmpty()) { "An identity key is needed to allow calls" }
        settings.setString(allowKey(onion), identityKey.toHex())
    }

    /** Stops allowing calls with [onion]. Takes effect immediately. */
    fun disallow(onion: String) {
        settings.remove(allowKey(onion))
    }

    /** Whether [onion] told us they allow calls with us. */
    fun allowedByThem(onion: String): Boolean = settings.getBool(theirKey(onion), false)

    fun setAllowedByThem(onion: String, allowed: Boolean) {
        if (allowed) {
            settings.setBool(theirKey(onion), true)
        } else {
            settings.remove(theirKey(onion))
            settings.remove(ringKey_(onion))
        }
    }

    /** Both sides allow it, and [onion] still has the key the user allowed. */
    /** The key [onion] gave us to ring their locked devices with, if they sent one. */
    fun ringKey(onion: String): ByteArray? =
        settings.getString(ringKey_(onion))?.let { hex ->
            if (hex.length != 64) return null
            runCatching { hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
        }

    fun setRingKey(onion: String, key: ByteArray?) {
        if (key == null || key.size != 32) settings.remove(ringKey_(onion)) else settings.setString(ringKey_(onion), key.toHex())
    }

    fun mutual(onion: String, currentIdentityKey: ByteArray?): Boolean =
        allowedByMe(onion, currentIdentityKey) && allowedByThem(onion)

    /** Forgets both directions, for a contact who was blocked or deleted. */
    fun forget(onion: String) {
        settings.remove(allowKey(onion))
        settings.remove(theirKey(onion))
        settings.remove(ringKey_(onion))
    }

    private fun recordedKey(onion: String): ByteArray? =
        settings.getString(allowKey(onion))?.let { hex ->
            if (hex.isEmpty() || hex.length % 2 != 0) return null
            runCatching { hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun allowKey(onion: String) = "$PREFIX_ALLOW$onion"
    private fun theirKey(onion: String) = "$PREFIX_THEIRS$onion"
    private fun ringKey_(onion: String) = "$PREFIX_RING$onion"

    companion object {
        private const val PREFIX_ALLOW = "call_allow:"
        private const val PREFIX_THEIRS = "call_allowed_by:"
        private const val PREFIX_RING = "call_ring_key:"
    }
}
