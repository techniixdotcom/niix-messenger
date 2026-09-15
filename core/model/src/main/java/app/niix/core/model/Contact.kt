package app.niix.core.model

enum class TrustState {
    UNVERIFIED,
    VERIFIED,
    REVOKED,
}

data class Contact(
    val onionAddress: OnionAddress,
    val displayName: String,
    val fingerprint: IdentityFingerprint,
    val trustState: TrustState = TrustState.UNVERIFIED,
    val addedAtEpochMillis: Long,
)
