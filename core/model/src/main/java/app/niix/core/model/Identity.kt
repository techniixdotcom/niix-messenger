package app.niix.core.model

@JvmInline
value class IdentityFingerprint(val displayable: String) {
    companion object {
        fun fromPublicKeyBytes(publicKey: ByteArray): IdentityFingerprint {
            val hex = publicKey.joinToString("") { "%02x".format(it) }
            val grouped = hex.chunked(5).joinToString(" ")
            return IdentityFingerprint(grouped)
        }
    }
}

data class LocalIdentity(
    val registrationId: Int,
    val fingerprint: IdentityFingerprint,
    val onionAddress: OnionAddress?,
)
