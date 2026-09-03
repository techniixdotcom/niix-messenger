package app.niix.update

import android.content.Context
import app.niix.core.model.DiagnosticLog
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(
    val versionName: String,
    val changelog: String,
    val apkUrl: String,
    val sigUrl: String,
    val expectedSha256Hex: String?,
)

sealed class UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

sealed class UpdateInstallResult {
    data class Ready(val apkFile: File) : UpdateInstallResult()
    data class Rejected(val reason: String) : UpdateInstallResult()
}

class UpdateChecker(
    private val context: Context,

    private val repoOwnerSlashName: String = "techniixdotcom/niix-messenger",
    /**
     * Route update traffic through Tor instead of straight out over the network. Off by default
     * -- see [openConnection] for the reasoning and exactly what the tradeoff is. When on, both
     * [socksHost] and [socksPort] must be supplied from
     * [app.niix.core.transport.TorTransport.socksAddress]; never hardcode them, since the port
     * Tor actually listens on isn't always the one that was requested.
     */
    private val useTor: Boolean = false,
    private val socksHost: String? = null,

    private val socksPort: Int? = null,
) {

    suspend fun checkForUpdate(currentVersionName: String): UpdateCheckResult {
        val body = httpGetText("https://api.github.com/repos/$repoOwnerSlashName/releases/latest")
            ?: run {
            DiagnosticLog.record("update", "metadata fetch failed (useTor=$useTor)")
            return UpdateCheckResult.Error("Could not reach the update server over Tor")
        }
        val release = try {
            JSONObject(body)
        } catch (_: Exception) {
            return UpdateCheckResult.Error("Unexpected response from the update server")
        }
        val tagName = release.optString("tag_name").removePrefix("v")
        if (tagName.isBlank() || tagName == currentVersionName) return UpdateCheckResult.UpToDate

        val assets = release.optJSONArray("assets") ?: return UpdateCheckResult.Error("Release has no assets")
        var apkUrl: String? = null
        var sigUrl: String? = null
        var checksumsUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")

            if (!isTrustedAssetUrl(url)) continue
            when {
                name.endsWith(".apk", ignoreCase = true) -> apkUrl = url
                name.endsWith(".sig", ignoreCase = true) -> sigUrl = url
                name.equals("SHA256SUMS.txt", ignoreCase = true) || name.equals("checksums.txt", ignoreCase = true) -> checksumsUrl = url
            }
        }
        if (apkUrl == null) {
            DiagnosticLog.record("update", "release has no .apk asset")
            return UpdateCheckResult.Error("Release has no .apk asset")
        }
        if (sigUrl == null) {
            DiagnosticLog.record("update", "release has no .sig asset")
            return UpdateCheckResult.Error("Release has no .sig asset -- refusing to offer an unsigned update")
        }

        val expectedHash = checksumsUrl?.let { url ->
            httpGetText(url)?.lineSequence()
                ?.mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 2)
                    if (parts.size == 2 && parts[1].trimStart('*').endsWith(".apk", ignoreCase = true)) parts[0] else null
                }
                ?.firstOrNull()
        }

        return UpdateCheckResult.Available(
            UpdateInfo(
                versionName = tagName,
                changelog = release.optString("body").ifBlank { "(no changelog provided)" },
                apkUrl = apkUrl,
                sigUrl = sigUrl,
                expectedSha256Hex = expectedHash,
            ),
        )
    }

    suspend fun downloadAndVerify(info: UpdateInfo): UpdateInstallResult {
        // The signature is tiny (64 bytes) so keeping it in memory is fine. The APK is not: at
        // ~68MB, loading it into a ByteArray meant holding it up to four times over at once (the
        // growing download buffer, the returned array, the write, and a read-back check), which
        // can exceed Android's per-app heap limit on plenty of real devices. An OutOfMemoryError
        // is an Error rather than an Exception, so it wasn't even caught by the surrounding
        // runCatching -- it would take the app down or surface as an unexplained failure. So the
        // APK is streamed straight to disk and every check runs against the file.
        val sigBytes = httpGetBytes(info.sigUrl) ?: return UpdateInstallResult.Rejected("Could not download the update's signature")

        val updatesDir = File(context.filesDir, "updates").apply { mkdirs() }
        // Remove any previous download first: a stale APK from an earlier version left lying
        // around is exactly the kind of thing that gets installed by accident later.
        updatesDir.listFiles()?.forEach { runCatching { it.delete() } }

        // Download to a temporary name and only promote it once every check has passed, so a
        // partial or unverified file can never be sitting at the path the installer uses.
        val partFile = File(updatesDir, "niix-update.apk.part")
        val outFile = File(updatesDir, "niix-update.apk")

        if (!downloadToFile(info.apkUrl, partFile)) {
            runCatching { partFile.delete() }
            DiagnosticLog.record("update", "apk download failed after retries")
            return UpdateInstallResult.Rejected("Could not download the update")
        }

        if (!verifyEd25519File(partFile, sigBytes)) {
            runCatching { partFile.delete() }
            DiagnosticLog.record("update", "signature verification FAILED on downloaded apk (${partFile.length()} bytes)")
            return UpdateInstallResult.Rejected("Signature verification failed -- this update was rejected and discarded")
        }
        info.expectedSha256Hex?.let { expected ->
            if (!sha256FileHex(partFile).equals(expected.trim(), ignoreCase = true)) {
                runCatching { partFile.delete() }
                DiagnosticLog.record("update", "sha256 mismatch against published checksum")
                return UpdateInstallResult.Rejected("Downloaded file's hash doesn't match the published checksum -- rejected")
            }
        }

        // Both checks above read the file back off disk, so they already prove what landed there
        // is intact -- a short write or storage that dropped bytes would fail the signature.
        if (!partFile.renameTo(outFile)) {
            runCatching { partFile.delete() }
            DiagnosticLog.record("update", "rename of verified apk into place failed")
            return UpdateInstallResult.Rejected("The update didn't save correctly -- nothing was installed. Please try again.")
        }
        DiagnosticLog.record("update", "apk downloaded and verified OK (${outFile.length()} bytes)")
        return UpdateInstallResult.Ready(outFile)
    }

    /** Streams [urlString] straight to [dest], never holding the whole body in memory. Retries
     * the same way [httpGetBytes] does, and treats a short read as failure rather than a
     * complete file. */
    private fun downloadToFile(urlString: String, dest: File, attempts: Int = 3): Boolean {
        repeat(attempts) {
            val connection = openValidatedConnection(urlString) ?: return@repeat
            try {
                if (connection.responseCode !in 200..299) return@repeat
                val declaredLength = connection.contentLengthLong
                if (declaredLength > MAX_RESPONSE_BYTES) return false

                var total = 0L
                connection.inputStream.use { input ->
                    dest.outputStream().use { output ->
                        val chunk = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(chunk)
                            if (read < 0) break
                            total += read
                            if (total > MAX_RESPONSE_BYTES) return false
                            output.write(chunk, 0, read)
                        }
                        output.flush()
                    }
                }
                // A dead circuit usually surfaces as a clean early EOF rather than an exception;
                // without this the partial file would be treated as a complete download.
                if (declaredLength >= 0 && total != declaredLength) return@repeat
                return true
            } catch (_: Exception) {
                // Retry: a transient network failure mid-transfer is normal, especially over Tor.
            } finally {
                connection.disconnect()
            }
        }
        return false
    }

    private fun verifyEd25519File(file: File, signature: ByteArray): Boolean {
        if (signature.size != 64) return false
        if (RELEASE_SIGNING_PUBLIC_KEY.all { it == 0.toByte() }) {
            return false
        }
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(RELEASE_SIGNING_PUBLIC_KEY))
            file.inputStream().use { input ->
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    verifier.update(chunk, 0, read)
                }
            }
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    private fun sha256FileHex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                digest.update(chunk, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Hands the downloaded APK to Android's installer. See [UpdateInstaller] for why this no
     * longer fires an ACTION_VIEW intent at a content:// URI: that path reported nothing back
     * when it failed, which made every failure look identical regardless of cause.
     */
    fun promptInstall(apkFile: File): String? = UpdateInstaller.install(context, apkFile)

    private fun openConnection(urlString: String): HttpURLConnection {
        val url = URL(urlString)
        // Clearnet is the default here, chosen deliberately: an update that won't download is an
        // update people don't install, and running a known-vulnerable version is a worse outcome
        // than the metadata this leaks. What it does leak is real and worth understanding -- a
        // network observer (ISP, or anyone watching the connection) can see this device fetching
        // from this specific GitHub repository, which identifies it as running this app. That
        // does not expose messages, contacts, or who anyone talks to; all of that stays over Tor
        // regardless of this setting. But for someone relying on the calculator disguise, the
        // fact that the app is present is itself the thing they're hiding, and this would give
        // that away. Anyone in that position should turn on "Check for updates over Tor" in
        // Settings, which routes this through Tor exactly as before.
        val connection = if (useTor && socksHost != null && socksPort != null) {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort))
            url.openConnection(proxy) as HttpURLConnection
        } else {
            url.openConnection() as HttpURLConnection
        }
        connection.connectTimeout = if (useTor) 60_000 else 20_000
        connection.readTimeout = if (useTor) 180_000 else 60_000
        // Redirects are followed manually (see openValidatedConnection) so every hop can be
        // checked against the trusted-host list. With automatic following only the first URL is
        // ever validated, and GitHub redirects asset downloads to a CDN -- so any hop after the
        // first would be followed blindly to wherever it pointed.
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "niix-update-checker")
        connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, text/plain, */*")
        return connection
    }

    /**
     * Opens a connection, following redirects manually and validating every hop.
     *
     * The signature check downstream still governs whether an update is installed, so this is
     * not the only thing standing between a hostile redirect and a bad install. But following a
     * redirect to an arbitrary host means making a request to it at all -- revealing this device
     * to whoever it points at, and handing them the chance to serve something large or slow.
     * There is no reason to follow a redirect that leaves GitHub's own hosts.
     */
    private fun openValidatedConnection(urlString: String): HttpURLConnection? {
        var current = urlString
        repeat(MAX_REDIRECTS) {
            if (!isTrustedAssetUrl(current) && !isTrustedApiUrl(current)) return null
            val connection = openConnection(current)
            val code = try {
                connection.responseCode
            } catch (_: Exception) {
                connection.disconnect()
                return null
            }
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) return null
            // Resolve relative redirects against the current URL rather than treating them as
            // absolute, which would silently fail the host check and reject a legitimate hop.
            current = runCatching { URL(URL(current), location).toString() }.getOrNull() ?: return null
        }
        return null
    }

    /** The GitHub API host the release metadata is fetched from. Kept separate from the asset
     * hosts so neither list is broader than it needs to be. */
    private fun isTrustedApiUrl(urlString: String): Boolean {
        val url = runCatching { URL(urlString) }.getOrNull() ?: return false
        return url.protocol.equals("https", ignoreCase = true) &&
            url.host.equals("api.github.com", ignoreCase = true)
    }

    private fun httpGetBytes(urlString: String, attempts: Int = 3): ByteArray? {
        repeat(attempts) {
            val connection = openValidatedConnection(urlString) ?: return@repeat
            try {
                if (connection.responseCode !in 200..299) return@repeat
                val declaredLength = connection.contentLengthLong
                // This path buffers the whole response in memory, so it only ever handles small
                // things: the release metadata JSON and the 64-byte signature. The APK is
                // streamed to disk by downloadToFile instead. Capping this at 8MB rather than
                // sharing the APK's 300MB limit means a hostile or broken server can't force a
                // large allocation here.
                if (declaredLength > MAX_IN_MEMORY_BYTES) return null

                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                connection.inputStream.use { input ->
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IN_MEMORY_BYTES) return null
                        buffer.write(chunk, 0, read)
                    }
                }

                // A Tor circuit that dies mid-transfer usually surfaces as a *clean* EOF, not an
                // exception -- read() simply returns -1 early. Without this check that partial
                // download would be returned as though it were the whole file: for the APK that
                // means a truncated archive the installer rejects with "package appears to be
                // invalid", and for JSON metadata it means a confusing parse error. When the
                // server told us how many bytes to expect, hold it to that and retry rather than
                // trusting a short read.
                if (declaredLength >= 0 && total != declaredLength) {
                    return@repeat
                }
                return buffer.toByteArray()
            } catch (_: Exception) {
                // Tor circuits legitimately stall or reset mid-transfer more often than a
                // typical clearnet connection, especially for a file this size -- a fresh
                // attempt succeeding where the first one didn't is normal, not a sign
                // anything is actually wrong. Nothing to log: this app deliberately has no
                // logging anywhere (see the rest of the codebase), so a failed attempt here
                // is silently retried rather than recorded.
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun httpGetText(urlString: String): String? = httpGetBytes(urlString)?.toString(Charsets.UTF_8)

    private fun isTrustedAssetUrl(urlString: String): Boolean {
        if (urlString.isBlank()) return false
        val url = try {
            URL(urlString)
        } catch (_: Exception) {
            return false
        }
        if (!url.protocol.equals("https", ignoreCase = true)) return false
        return TRUSTED_ASSET_HOSTS.any { trusted -> url.host.equals(trusted, ignoreCase = true) }
    }

    companion object {

        private val RELEASE_SIGNING_PUBLIC_KEY = byteArrayOf(
            0x9a.toByte(), 0x6e, 0x9d.toByte(), 0x9d.toByte(), 0x47, 0x5a, 0xfb.toByte(), 0x29, 0x45, 0xe5.toByte(),
            0xbb.toByte(), 0x8a.toByte(), 0xfa.toByte(), 0xb1.toByte(), 0x90.toByte(), 0x62, 0x05, 0x8e.toByte(), 0x13,
            0x1e, 0x4c, 0xaa.toByte(), 0xfc.toByte(), 0x44, 0xa7.toByte(), 0x6a, 0x41, 0x96.toByte(), 0x79, 0x73,
            0xd4.toByte(), 0xd8.toByte(),
        )
        /** Ceiling for the streamed APK download. */
        private const val MAX_RESPONSE_BYTES = 300L * 1024 * 1024

        /** Ceiling for responses buffered in memory -- release metadata and the signature.
         * Deliberately far smaller than [MAX_RESPONSE_BYTES]: nothing on that path is ever
         * legitimately large, and a generous limit there is just an invitation to allocate. */
        private const val MAX_IN_MEMORY_BYTES = 8L * 1024 * 1024

        /** Enough for GitHub's asset redirect chain, few enough to bound a redirect loop. */
        private const val MAX_REDIRECTS = 5

        private val TRUSTED_ASSET_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )
    }
}
