package app.niix.core.storage

import java.nio.CharBuffer

enum class UnlockResult {
    SUCCESS,
    FAILED,
    DURESS,
}

class AppLockManager internal constructor(
    private val secureDatabase: SecureDatabase,
    private val secretProvider: DatabaseSecretProvider,
) {

    fun isPasscodeSet(): Boolean = secretProvider.isInitialized()

    fun isUnlocked(): Boolean = secureDatabase.isOpen()

    fun isDuressSet(): Boolean = secretProvider.isDuressSet()

    fun setPasscode(passcode: CharArray): Boolean {
        if (isPasscodeSet()) return false
        val deviceSecret = secretProvider.deviceSecret()
        val salt = secretProvider.passcodeSalt()
        val dbKey = deriveDbKey(passcode, salt, deviceSecret)
        return try {
            secureDatabase.openWith(dbKey)
            true
        } finally {
            dbKey.fill(0)
            deviceSecret.fill(0)
        }
    }

    fun setDuressPasscode(passcode: CharArray): Boolean {
        if (!isPasscodeSet()) return false
        val salt = ByteArray(SALT_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        val passcodeBytes = encodeUtf8(passcode)
        return try {
            val verifier = PassphraseKdf.derivePasscodeKey(passcodeBytes, salt)
            secretProvider.writeDuress(salt, verifier)
            verifier.fill(0)
            true
        } finally {
            passcodeBytes.fill(0)
        }
    }

    fun unlock(passcode: CharArray): UnlockResult {
        if (!isPasscodeSet()) return UnlockResult.FAILED
        if (isUnlocked()) return UnlockResult.SUCCESS
        if (matchesDuress(passcode)) return UnlockResult.DURESS
        val deviceSecret = secretProvider.deviceSecret()
        val salt = secretProvider.passcodeSalt()
        val dbKey = deriveDbKey(passcode, salt, deviceSecret)
        return try {
            secureDatabase.openWith(dbKey)
            UnlockResult.SUCCESS
        } catch (_: Exception) {
            UnlockResult.FAILED
        } finally {
            dbKey.fill(0)
            deviceSecret.fill(0)
        }
    }

    fun changePasscode(current: CharArray, next: CharArray): Boolean {
        if (unlock(current) != UnlockResult.SUCCESS) return false
        val deviceSecret = secretProvider.deviceSecret()
        val newSalt = secretProvider.rotatePasscodeSalt()
        val newKey = deriveDbKey(next, newSalt, deviceSecret)
        return try {
            secureDatabase.changePassphrase(newKey)
            true
        } finally {
            newKey.fill(0)
            deviceSecret.fill(0)
        }
    }

    fun lock() {
        secureDatabase.close()
    }

    private fun matchesDuress(passcode: CharArray): Boolean {
        if (!secretProvider.isDuressSet()) return false
        val salt = secretProvider.duressSalt() ?: return false
        val expected = secretProvider.duressVerifier() ?: return false
        val passcodeBytes = encodeUtf8(passcode)
        return try {
            val candidate = PassphraseKdf.derivePasscodeKey(passcodeBytes, salt)
            val equal = constantTimeEquals(candidate, expected)
            candidate.fill(0)
            equal
        } finally {
            passcodeBytes.fill(0)
        }
    }

    private fun deriveDbKey(passcode: CharArray, salt: ByteArray, deviceSecret: ByteArray): ByteArray {
        val passcodeBytes = encodeUtf8(passcode)
        return try {
            val passcodeKey = PassphraseKdf.derivePasscodeKey(passcodeBytes, salt)
            try {
                PassphraseKdf.combine(deviceSecret, passcodeKey)
            } finally {
                passcodeKey.fill(0)
            }
        } finally {
            passcodeBytes.fill(0)
        }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].toInt() xor b[i].toInt())
        return result == 0
    }

    private fun encodeUtf8(chars: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    companion object {
        private const val SALT_BYTES = 16
    }
}
