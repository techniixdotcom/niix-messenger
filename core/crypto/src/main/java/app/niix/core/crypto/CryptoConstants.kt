package app.niix.core.crypto

import java.security.SecureRandom

internal object CryptoConstants {
    const val DEVICE_ID = 1

    const val ONE_TIME_PREKEY_BATCH = 100
    const val ONE_TIME_KYBER_PREKEY_BATCH = 100

    const val LOW_WATERMARK_ONE_TIME_PREKEYS = 20
    const val LOW_WATERMARK_KYBER_PREKEYS = 20

    const val MEDIUM_MAX_VALUE = 0xFFFFFF

    private val secureRandom = SecureRandom()

    fun randomId(): Int = secureRandom.nextInt(MEDIUM_MAX_VALUE - 1) + 1
}
