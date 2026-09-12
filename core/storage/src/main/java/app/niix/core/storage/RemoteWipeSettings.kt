package app.niix.core.storage

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Settings for remotely erasing this device.
 *
 * Two independent things must both hold before a wipe is honoured: the request has to arrive
 * from a contact the user nominated in advance, over an authenticated Signal session, and it has
 * to carry the correct token. Either alone does nothing. A stolen token is useless without also
 * compromising a nominated contact, and a compromised contact is useless without the token.
 *
 * The token is stored as a SHA-256 hash, not in the clear. The plaintext is shown once when it
 * is generated and never again -- so a copy of this database does not yield a working wipe
 * command for the devices it came from.
 *
 * Off entirely by default. This is a feature that destroys data on request; it should exist only
 * for people who deliberately turned it on.
 */
class RemoteWipeSettings internal constructor(private val settings: SettingsStore) {

    fun isEnabled(): Boolean = settings.getBool(KEY_ENABLED, false)

    /**
     * Turns the feature on and returns a fresh token to be shared out of band.
     *
     * 256 bits from SecureRandom. At that size guessing is not a consideration, which is what
     * allows the token to be verified without a rate limit standing between an attacker and the
     * destruction of everything.
     */
    fun enableAndGenerateToken(): String {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        settings.setString(KEY_TOKEN_HASH, hash(token))
        settings.setBool(KEY_ENABLED, true)
        return token
    }

    /** Turns it off and forgets the token, so re-enabling issues a new one rather than
     * resurrecting a token that may have been exposed. */
    fun disable() {
        settings.setBool(KEY_ENABLED, false)
        settings.remove(KEY_TOKEN_HASH)
    }

    fun nominated(): List<String> =
        settings.getString(KEY_NOMINATED)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun nominate(onion: String) {
        val current = nominated().toMutableList()
        if (onion in current) return
        current.add(onion)
        settings.setString(KEY_NOMINATED, current.joinToString("\n"))
    }

    /** Removing a nomination has to take effect immediately -- it is what someone reaches for
     * when a contact's device may be compromised. */
    fun revoke(onion: String) {
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
     * Compared with MessageDigest.isEqual, which is constant-time. A plain string comparison
     * returns as soon as two bytes differ, and that timing difference is measurable -- it would
     * let an attacker who can send repeated requests recover the token one byte at a time,
     * which is exactly the attack the token's length is meant to make impossible.
     */
    fun shouldWipe(fromOnion: String, token: String): Boolean {
        if (!isEnabled()) return false
        if (fromOnion !in nominated()) return false
        val expected = settings.getString(KEY_TOKEN_HASH) ?: return false
        return MessageDigest.isEqual(
            hash(token).toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val KEY_ENABLED = "remote_wipe_enabled"
        const val KEY_TOKEN_HASH = "remote_wipe_token_hash"
        const val KEY_NOMINATED = "remote_wipe_nominated"
    }
}
