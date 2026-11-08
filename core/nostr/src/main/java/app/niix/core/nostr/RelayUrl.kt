package app.niix.core.nostr

import java.net.URI

/**
 * A relay address from the user or a contact, validated before we ever dial it.
 *
 * Plain ws:// is only allowed for .onion hosts. On the clearnet it would let a Tor exit read the
 * tags we ask for and drop or inject events; an onion address is already end-to-end encrypted.
 */
data class RelayUrl(val host: String, val port: Int, val path: String, val tls: Boolean) {

    override fun toString(): String {
        val portPart = if (port == defaultPort(tls)) "" else ":$port"
        return "${if (tls) "wss" else "ws"}://$host$portPart$path"
    }

    companion object {
        // Matches the wire codec's per-item limit, so anything we accept can also be announced.
        const val MAX_LENGTH = 128

        private val HOST = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")
        private val IPV4 = Regex("^[0-9.]+$")

        fun defaultPort(tls: Boolean) = if (tls) 443 else 80

        fun parse(raw: String): RelayUrl? {
            val text = raw.trim()
            if (text.isEmpty() || text.length > MAX_LENGTH) return null
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            val tls = when (uri.scheme?.lowercase()) {
                "wss" -> true
                "ws" -> false
                else -> return null
            }
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
            // No IP literals (TLS needs a name to check) and no single-label names.
            if (!host.matches(HOST) || host.matches(IPV4)) return null
            if (!tls && !host.endsWith(".onion")) return null
            val port = if (uri.port == -1) defaultPort(tls) else uri.port
            if (port !in 1..65535) return null
            val path = uri.rawPath?.ifEmpty { null } ?: "/"
            if (!path.startsWith("/") || path.any { it.isWhitespace() }) return null
            return RelayUrl(host, port, path, tls)
        }
    }
}
