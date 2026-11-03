package app.niix.core.crypto

/**
 * Formatting for the safety number shown in the UI.
 *
 * The number itself comes from libsignal's NumericFingerprintGenerator, via
 * CryptoEngine.remoteFingerprint. This used to compute its own with a bespoke SHA-512
 * construction, which meant the app held two different answers to "what is this contact's
 * fingerprint": one shown to the user for comparison, one used everywhere else. Two people
 * comparing safety numbers need to be comparing the same thing, and a second construction is a
 * second thing to get wrong for no benefit.
 */
object SafetyNumber {

    /** Groups the digits so two people can read them aloud without losing their place. */
    fun formatted(safetyNumber: String): String =
        safetyNumber.replace(" ", "").chunked(5).joinToString(" ")
}
