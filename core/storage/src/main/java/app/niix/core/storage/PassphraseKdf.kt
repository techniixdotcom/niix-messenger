package app.niix.core.storage

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters

internal object PassphraseKdf {

    private const val KEY_BYTES = 32
    private const val MEMORY_KB = 65536
    private const val ITERATIONS = 3
    private const val PARALLELISM = 2
    private val INFO = "niix-db-key-v1".toByteArray()
    private val DEVICE_ONLY_INFO = "niix-db-key-device-only-v1".toByteArray()

    fun derivePasscodeKey(passcode: ByteArray, salt: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(MEMORY_KB)
            .withIterations(ITERATIONS)
            .withParallelism(PARALLELISM)
            .build()
        val generator = Argon2BytesGenerator().apply { init(params) }
        val out = ByteArray(KEY_BYTES)
        generator.generateBytes(passcode, out)
        return out
    }

    fun combine(deviceSecret: ByteArray, passcodeKey: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(deviceSecret, passcodeKey, INFO))
        val out = ByteArray(KEY_BYTES)
        hkdf.generateBytes(out, 0, out.size)
        return out
    }

    /**
     * The database key used when passcode protection is turned off: derived from the
     * hardware-backed device secret alone, with a distinct HKDF "info" value so this key is
     * cryptographically unrelated to (and not derivable from, or a stepping stone toward) any
     * passcode-derived key ever used on this device.
     */
    fun deviceOnlyKey(deviceSecret: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(deviceSecret, null, DEVICE_ONLY_INFO))
        val out = ByteArray(KEY_BYTES)
        hkdf.generateBytes(out, 0, out.size)
        return out
    }
}
