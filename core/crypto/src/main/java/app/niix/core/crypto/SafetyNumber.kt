package app.niix.core.crypto

/**
 * Formats the safety number for display. The number itself comes from libsignal's
 * NumericFingerprintGenerator via CryptoEngine.remoteFingerprint, so both people compare the same
 * fingerprint the rest of the app uses.
 */
object SafetyNumber {

    /** Groups the digits so two people can read them out without losing their place. */
    fun formatted(safetyNumber: String): String =
        safetyNumber.replace(" ", "").chunked(5).joinToString(" ")
}
