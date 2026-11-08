package app.niix.core.storage

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import java.nio.ByteBuffer
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
    private const val SELF_TEST_MEMORY_KB = 64
    private val INFO = "niix-db-key-v1".toByteArray()
    private val DEVICE_ONLY_INFO = "niix-db-key-device-only-v1".toByteArray()

    /**
     * Argon2id over the passcode. Native code when it's available and checks out, which is several
     * times faster than BouncyCastle (C with a thread per lane, against Java working through the
     * lanes one at a time) and keeps the 64 MB off the Java heap. Same algorithm and parameters, so
     * the output is identical and nothing has to be migrated.
     */
    fun derivePasscodeKey(passcode: ByteArray, salt: ByteArray): ByteArray {
        nativeArgon2Kt?.let { argon2 ->
            runCatching { return nativeArgon2(argon2, passcode, salt, MEMORY_KB, ITERATIONS) }
        }
        return javaArgon2(passcode, salt, MEMORY_KB, ITERATIONS)
    }

    /** Which implementation derivePasscodeKey uses, for the diagnostics log. */
    val implementation: String get() = if (nativeArgon2Kt != null) "native" else "BouncyCastle"

    /**
     * The native library, or null to use BouncyCastle. Before it's trusted, it has to give the
     * same answer as BouncyCastle on a small input (same mode, version and lane count, a fraction of
     * the memory, so a millisecond or two). A wrong answer here would mean a passcode that never
     * opens the database again, so any doubt at all means falling back.
     */
    private val nativeArgon2Kt: Argon2Kt? by lazy {
        runCatching {
            val argon2 = Argon2Kt()
            val probe = "niix-self-test".toByteArray()
            val salt = ByteArray(16) { it.toByte() }
            val fromNative = nativeArgon2(argon2, probe, salt, SELF_TEST_MEMORY_KB, 1)
            val fromJava = javaArgon2(probe, salt, SELF_TEST_MEMORY_KB, 1)
            argon2.takeIf { fromNative.contentEquals(fromJava) }
        }.getOrNull()
    }

    private fun nativeArgon2(argon2: Argon2Kt, passcode: ByteArray, salt: ByteArray, memoryKb: Int, iterations: Int): ByteArray {
        val result = argon2.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = passcode,
            salt = salt,
            tCostInIterations = iterations,
            mCostInKibibyte = memoryKb,
            parallelism = PARALLELISM,
            hashLengthInBytes = KEY_BYTES,
        )
        return try {
            result.rawHashAsByteArray().also { check(it.size == KEY_BYTES) }
        } finally {
            // Both buffers hold the hash (the encoded one as text) in native memory.
            runCatching { result.rawHash.wipe() }
            runCatching { result.encodedOutput.wipe() }
        }
    }

    private fun ByteBuffer.wipe() {
        clear()
        while (hasRemaining()) put(0)
    }

    private fun javaArgon2(passcode: ByteArray, salt: ByteArray, memoryKb: Int, iterations: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKb)
            .withIterations(iterations)
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

    fun deviceOnlyKey(deviceSecret: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(deviceSecret, null, DEVICE_ONLY_INFO))
        val out = ByteArray(KEY_BYTES)
        hkdf.generateBytes(out, 0, out.size)
        return out
    }
}
