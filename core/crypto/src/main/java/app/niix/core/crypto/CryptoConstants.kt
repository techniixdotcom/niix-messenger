package app.niix.core.crypto

import java.security.SecureRandom

internal object CryptoConstants {
    const val DEVICE_ID = 1

    const val ONE_TIME_PREKEY_BATCH = 100
    const val ONE_TIME_KYBER_PREKEY_BATCH = 100

    const val LOW_WATERMARK_ONE_TIME_PREKEYS = 20
    const val LOW_WATERMARK_KYBER_PREKEYS = 20

    // --- Key rotation schedule ---
    // A stolen (but not-yet-rotated) signed prekey lets an attacker complete a session
    // handshake as "us" until it rotates out; a short rotation window bounds that exposure.
    // Rotating rather than reusing forever is the whole point -- the old key still has to
    // survive a while after rotation, though, because a peer may have fetched our bundle just
    // before we rotated and still be mid-handshake with the now-previous key, or a
    // slow-to-arrive first message may reference it.
    const val SIGNED_PREKEY_ROTATION_INTERVAL_MILLIS = 2L * 24 * 60 * 60 * 1000 // 2 days
    const val SIGNED_PREKEY_RETENTION_MILLIS = 14L * 24 * 60 * 60 * 1000 // 14 days

    // The last-resort Kyber prekey is the PQXDH fallback used once a peer's session has burned
    // through every one-time Kyber prekey we published -- unlike those, it's reusable, so it
    // rotates on its own (much longer, since it's the least-sensitive of the two: X25519 alone
    // still protects the handshake even if a stale Kyber key were ever compromised) and keeps a
    // longer overlap window for the same "still-in-flight handshake" reason as above.
    const val KYBER_LAST_RESORT_ROTATION_INTERVAL_MILLIS = 30L * 24 * 60 * 60 * 1000 // 30 days
    const val KYBER_LAST_RESORT_RETENTION_MILLIS = 60L * 24 * 60 * 60 * 1000 // 60 days

    const val MEDIUM_MAX_VALUE = 0xFFFFFF

    private val secureRandom = SecureRandom()

    fun randomId(): Int = secureRandom.nextInt(MEDIUM_MAX_VALUE - 1) + 1
}
