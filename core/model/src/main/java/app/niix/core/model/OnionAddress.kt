package app.niix.core.model

@JvmInline
value class OnionAddress private constructor(val value: String) {

    val hostname: String get() = value
    val withoutSuffix: String get() = value.removeSuffix(SUFFIX)

    companion object {
        const val SUFFIX = ".onion"
        private const val V3_BASE32_LENGTH = 56
        private val V3_PATTERN = Regex("^[a-z2-7]{$V3_BASE32_LENGTH}\\.onion$")

        fun parse(raw: String): OnionAddress {
            val normalized = raw.trim().lowercase()
            require(V3_PATTERN.matches(normalized)) {
                "Not a valid Tor v3 onion address"
            }
            return OnionAddress(normalized)
        }

        fun parseOrNull(raw: String): OnionAddress? =
            runCatching { parse(raw) }.getOrNull()
    }
}
