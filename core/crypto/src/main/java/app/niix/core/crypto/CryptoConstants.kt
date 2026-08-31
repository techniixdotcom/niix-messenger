package app.niix.core.crypto

import java.security.SecureRandom

internal object CryptoConstants {
    const val DEVICE_ID = 1

    const val ONE_TIME_PREKEY_BATCH = 100
    const val ONE_TIME_KYBER_PREKEY_BATCH = 100

    const val LOW_WATERMARK_ONE_TIME_PREKEYS = 20
    const val LOW_WATERMARK_KYBER_PREKEYS = 20

    const val SIGNED_PREKEY_ROTATION_INTERVAL_MILLIS = 2L * 24 * 60 * 60 * 1000
    const val SIGNED_PREKEY_RETENTION_MILLIS = 14L * 24 * 60 * 60 * 1000

    const val KYBER_LAST_RESORT_ROTATION_INTERVAL_MILLIS = 30L * 24 * 60 * 60 * 1000
    const val KYBER_LAST_RESORT_RETENTION_MILLIS = 60L * 24 * 60 * 60 * 1000

    const val MEDIUM_MAX_VALUE = 0xFFFFFF

    private val secureRandom = SecureRandom()

    fun randomId(): Int = secureRandom.nextInt(MEDIUM_MAX_VALUE - 1) + 1
}
