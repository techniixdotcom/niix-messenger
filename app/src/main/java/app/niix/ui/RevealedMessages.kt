package app.niix.ui

/**
 * Which incoming messages the user has tapped to reveal.
 *
 * Shared rather than held per-adapter because the blur has to be consistent in two places: the
 * conversation list shows a preview of the newest message, and blurring it only inside the chat
 * left the text readable on the main screen -- which is the screen someone glancing at an
 * unlocked phone actually sees first. Two separate sets would drift apart the moment a message
 * was revealed in one place and rendered in the other.
 *
 * In memory only. Everything re-blurs when the process restarts, which is deliberate: persisting
 * it would mean an unlocked device eventually shows the whole history in the clear, and that is
 * the situation this exists to prevent.
 */
object RevealedMessages {

    private val revealed = mutableSetOf<String>()

    @Synchronized
    fun isRevealed(messageId: String): Boolean = messageId in revealed

    /** Returns true if this call is what revealed it, so callers can start a disappearing timer
     * exactly once rather than on every re-bind. */
    @Synchronized
    fun reveal(messageId: String): Boolean = revealed.add(messageId)

    /** Called on lock and on wipe. A locked device must not be holding a record of what had been
     * read, and after a wipe the ids refer to messages that no longer exist. */
    @Synchronized
    fun clear() {
        revealed.clear()
    }
}
