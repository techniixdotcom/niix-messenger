package app.niix.core.storage


/**
 * Settings for remotely erasing this device.
 *
 * A wipe is honoured when it arrives from a contact the user nominated, over an authenticated
 * Signal session. Nomination is the authorisation; there is no separate code.
 *
 * An earlier design also required a shared token. It was dropped once nominees are told they are
 * nominated, because it protected against nothing the session did not already cover: presenting
 * the request requires control of the nominated contact's device, and anyone with that also has
 * whatever token was stored there. A second factor that travels with the first is not one.
 *
 * Two lists are held, in opposite directions:
 *   - nominated(), contacts who may erase THIS device
 *   - canWipe(), contacts who have nominated this device to erase THEIRS
 *
 * They are separate because confusing them would either offer to wipe devices that never asked,
 * or refuse ones that did.
 *
 * Off entirely by default. This destroys data on request; it should exist only for people who
 *  turned it on.
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

    fun nominate(onion: String) {
        val current = nominated().toMutableList()
        if (onion in current) return
        current.add(onion)
        settings.setString(KEY_NOMINATED, current.joinToString("\n"))
    }

    /** Removing a nomination has to take effect immediately, it is what someone reaches for
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
     * The sender being nominated *is* the authorisation. A shared code used to be required as
     * well, but it added nothing once the nominee is told they are nominated: the request already
     * arrives over an authenticated Signal session, so presenting it requires control of the
     * nominated contact's device, and anyone with that also has whatever code was stored on it.
     * A second factor that travels with the first is not a second factor.
     */
    fun shouldWipe(fromOnion: String): Boolean {
        if (!isEnabled()) return false
        return fromOnion in nominated()
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
