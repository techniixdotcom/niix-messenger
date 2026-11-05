package app.niix.core.crypto

/** A copy of a message that was already decrypted. Nothing is wrong; it can just be dropped. */
class AlreadyReceivedException(cause: Throwable) : Exception(cause)

/**
 * The session with the sending device can't open its messages: the two sides no longer agree, say
 * after one restored a backup. Every retry would fail the same way until a new session starts.
 */
class SessionMismatchException(cause: Throwable) : Exception(cause)
