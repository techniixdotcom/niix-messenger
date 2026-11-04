package app.niix.update

import android.content.Context
import app.niix.BuildConfig
import app.niix.R
import app.niix.core.model.DiagnosticLog
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val changelog: String,
    val apkUrl: String,
    val sigUrl: String,
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
    /**
     * Send update traffic through Tor, which the app does unless the user turned it off; see
     * [openConnection]. When on, [socksHost] and [socksPort] must come from
     * [app.niix.core.transport.TorTransport.socksAddress], because Tor doesn't always listen on the
     * port that was requested.
     */
    private val useTor: Boolean = false,
    private val socksHost: String? = null,
    private val socksPort: Int? = null,
) {

    suspend fun checkForUpdate(currentVersionName: String): UpdateCheckResult {
        val body = httpGetText(LATEST_RELEASE_URL) ?: run {
            DiagnosticLog.record("update", "metadata fetch failed (useTor=$useTor)")
            return UpdateCheckResult.Error(
                context.getString(if (useTor) R.string.update_err_unreachable_tor else R.string.update_err_unreachable),
            )
        }
        val release = try {
            JSONObject(body)
        } catch (_: Exception) {
            return UpdateCheckResult.Error(context.getString(R.string.update_err_bad_response))
        }
        val tagName = release.optString("tag_name").removePrefix("v")
        if (tagName.isBlank()) return UpdateCheckResult.UpToDate

        // Must be strictly newer, not just different. Every release is properly signed, so someone
        // controlling which one we see (a stale mirror, a tampered response) could otherwise roll
        // us back to a version with a known bug. The signature proves who built it, not that it's
        // current.
        if (!isStrictlyNewer(tagName, currentVersionName)) return UpdateCheckResult.UpToDate

        val assets = release.optJSONArray("assets") ?: return UpdateCheckResult.Error(context.getString(R.string.update_err_no_apk))
        var apkUrl: String? = null
        var sigUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (!isTrustedAssetUrl(url)) continue
            when {
                name.endsWith(".apk", ignoreCase = true) -> apkUrl = url
                name.endsWith(".sig", ignoreCase = true) -> sigUrl = url
            }
        }
        if (apkUrl == null) {
            DiagnosticLog.record("update", "release has no .apk asset")
            return UpdateCheckResult.Error(context.getString(R.string.update_err_no_apk))
        }
        if (sigUrl == null) {
            DiagnosticLog.record("update", "release has no .sig asset")
            return UpdateCheckResult.Error(context.getString(R.string.update_err_unsigned))
        }

        // No separate checksum: the signature covers every byte of the APK, which is a stronger check
        // than a hash published next to it.
        return UpdateCheckResult.Available(
            UpdateInfo(
                versionName = tagName,
                changelog = release.optString("body").ifBlank { context.getString(R.string.update_no_changelog) },
                apkUrl = apkUrl,
                sigUrl = sigUrl,
            ),
        )
    }

    /**
     * Downloads, verifies and stages an update.
     *
     * [onProgress] gets bytes received and the total (-1 if the server didn't send a length).
     * [onVerifying] is called once the download finishes and the signature check starts.
     * [isCancelled] is checked between chunks; returning true stops the download, deletes the
     * partial file and returns [UpdateInstallResult.Rejected].
     */
    suspend fun downloadAndVerify(
        info: UpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        onVerifying: () -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): UpdateInstallResult {
        // The 64-byte signature can live in memory; the APK (around 68 MB) can't. Buffering it
        // would hold several copies at once and can blow the heap on real devices, and an
        // OutOfMemoryError isn't caught by runCatching. So the APK goes straight to disk and every
        // check reads the file.
        val sigBytes = httpGetBytes(info.sigUrl) ?: return UpdateInstallResult.Rejected(context.getString(R.string.update_err_download))

        val updatesDir = File(context.filesDir, "updates").apply { mkdirs() }
        // Delete any earlier download first so a stale APK can't be installed by accident later.
        updatesDir.listFiles()?.forEach { runCatching { it.delete() } }

        // Download under a temporary name and only rename it once every check passes, so nothing
        // unverified ever sits at the path the installer uses.
        val partFile = File(updatesDir, "niix-update.apk.part")
        val outFile = File(updatesDir, "niix-update.apk")

        if (!downloadToFile(info.apkUrl, partFile, onProgress = onProgress, isCancelled = isCancelled)) {
            runCatching { partFile.delete() }
            if (isCancelled()) {
                DiagnosticLog.record("update", "download cancelled by the user")
                return UpdateInstallResult.Rejected(context.getString(R.string.update_err_cancelled))
            }
            DiagnosticLog.record("update", "apk download failed after retries")
            return UpdateInstallResult.Rejected(context.getString(R.string.update_err_download))
        }

        onVerifying()

        if (!verifyEd25519File(partFile, sigBytes)) {
            runCatching { partFile.delete() }
            DiagnosticLog.record("update", "signature verification FAILED on downloaded apk (${partFile.length()} bytes)")
            return UpdateInstallResult.Rejected(context.getString(R.string.update_err_signature))
        }
        // The check read the file back from disk, so a short or corrupted write would already have
        // failed it.
        if (!partFile.renameTo(outFile)) {
            runCatching { partFile.delete() }
            DiagnosticLog.record("update", "rename of verified apk into place failed")
            return UpdateInstallResult.Rejected(context.getString(R.string.update_err_save))
        }
        DiagnosticLog.record("update", "apk downloaded and verified OK (${outFile.length()} bytes)")
        return UpdateInstallResult.Ready(outFile)
    }

    /**
     * Streams [urlString] to [dest] without holding the body in memory. Retries like [httpGetBytes]
     * and treats a short read as a failure.
     */
    private fun downloadToFile(
        urlString: String,
        dest: File,
        attempts: Int = 3,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Boolean {
        repeat(attempts) {
            if (isCancelled()) return false
            val connection = openValidatedConnection(urlString) ?: return@repeat
            try {
                if (connection.responseCode !in 200..299) return@repeat
                val declaredLength = connection.contentLengthLong
                if (declaredLength > MAX_RESPONSE_BYTES) return false

                var total = 0L
                onProgress(0L, declaredLength)
                connection.inputStream.use { input ->
                    dest.outputStream().use { output ->
                        val chunk = ByteArray(64 * 1024)
                        while (true) {
                            // Checked every chunk, so Cancel works within one 64 KB read.
                            if (isCancelled()) return false
                            val read = input.read(chunk)
                            if (read < 0) break
                            total += read
                            if (total > MAX_RESPONSE_BYTES) return false
                            output.write(chunk, 0, read)
                            onProgress(total, declaredLength)
                        }
                        output.flush()
                    }
                }
                // A dead Tor circuit often shows up as an early EOF rather than an exception;
                // without this the partial file would look complete.
                if (declaredLength >= 0 && total != declaredLength) return@repeat
                return true
            } catch (_: Exception) {
                // Retry. Transfers failing halfway are normal, especially over Tor.
            } finally {
                connection.disconnect()
            }
        }
        return false
    }

    /** True if any trusted key signed [file]. One pass over the file feeds every key's check. */
    private fun verifyEd25519File(file: File, signature: ByteArray): Boolean {
        if (signature.size != 64) return false
        return try {
            val verifiers = RELEASE_SIGNING_PUBLIC_KEYS.map { key ->
                Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(key)) }
            }
            file.inputStream().use { input ->
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    verifiers.forEach { it.update(chunk, 0, read) }
                }
            }
            verifiers.any { it.verifySignature(signature) }
        } catch (_: Exception) {
            false
        }
    }

    fun promptInstall(apkFile: File): String? = UpdateInstaller.install(context, apkFile)

    private fun openConnection(urlString: String): HttpURLConnection {
        val url = URL(urlString)
        // Over Tor unless the user switched it off. A normal connection is faster and more likely to
        // get through, but anyone watching it can see this device fetching from this GitHub repo,
        // which tells them the app is installed. Messages, contacts and who you talk to stay on Tor
        // either way.
        val connection = if (useTor && socksHost != null && socksPort != null) {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort))
            url.openConnection(proxy) as HttpURLConnection
        } else {
            url.openConnection() as HttpURLConnection
        }
        connection.connectTimeout = if (useTor) 60_000 else 20_000
        connection.readTimeout = if (useTor) 180_000 else 60_000
        // Redirects are followed by hand (see openValidatedConnection) so every hop is checked
        // against the trusted hosts. GitHub sends asset downloads to a CDN, and automatic following
        // would only ever check the first URL.
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "niix-update-checker")
        connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, text/plain, */*")
        return connection
    }

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
            // Resolve relative redirects against the current URL; treating them as absolute would
            // fail the host check on a legitimate hop.
            current = runCatching { URL(URL(current), location).toString() }.getOrNull() ?: return null
        }
        return null
    }

    /**
     * GitHub API host for release metadata. Kept apart from the asset hosts so neither list is
     * wider than it needs to be.
     */
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
                // This buffers the whole response, so it's only used for small things: the release
                // JSON and the 64-byte signature. The 8 MB cap stops a hostile server forcing a
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

                // A dying Tor circuit usually ends with a clean EOF, so read() just returns -1
                // early. If the server told us the length, hold it to that and retry instead of
                // returning a truncated APK or half a JSON document.
                if (declaredLength >= 0 && total != declaredLength) {
                    return@repeat
                }
                return buffer.toByteArray()
            } catch (_: Exception) {
                // Tor circuits stall or reset mid-transfer more often than clearnet connections; a
                // second attempt working is normal, so just retry.
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
        private const val REPO = "techniixdotcom/niix-messenger"
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"

        /**
         * Time until the next automatic check: 20 to 28 hours, picked at random so the checks
         * don't line up with when the app is usually opened.
         */
        internal fun nextCheckDelayMillis(random: java.util.Random): Long =
            CHECK_INTERVAL_MIN_MILLIS + (random.nextDouble() * CHECK_INTERVAL_SPREAD_MILLIS).toLong()

        private const val CHECK_INTERVAL_MIN_MILLIS = 20L * 60 * 60 * 1000
        private const val CHECK_INTERVAL_SPREAD_MILLIS = 8L * 60 * 60 * 1000

        /**
         * True if [offered] is strictly later than [current].
         *
         * Compared numerically per segment, since "0.8.10" sorts before "0.8.9" as text. Anything
         * unparseable counts as not newer: refusing an update can be fixed later, installing a
         * downgrade can't. In the companion so it can be tested without a network.
         */
        internal fun isStrictlyNewer(offered: String, current: String): Boolean {
            fun parse(v: String): List<Int>? {
                val cleaned = v.trim().removePrefix("v")
                if (cleaned.isEmpty()) return null
                val parts = cleaned.split('.')
                val nums = parts.map { part -> part.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
                return nums.takeIf { it.isNotEmpty() }
            }
            val a = parse(offered) ?: return false
            val b = parse(current) ?: return false
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        // From update-key.pub in the project root: the key releases are signed with, and the
        // standby that can replace it without everyone reinstalling.
        private val RELEASE_SIGNING_PUBLIC_KEYS = BuildConfig.UPDATE_PUBLIC_KEYS.split(',').map { hex ->
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        /** Upper limit for the streamed APK. */
        private const val MAX_RESPONSE_BYTES = 300L * 1024 * 1024

        private const val MAX_IN_MEMORY_BYTES = 8L * 1024 * 1024

        /** Enough for GitHub's asset redirects, few enough to stop a redirect loop. */
        private const val MAX_REDIRECTS = 5

        private val TRUSTED_ASSET_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )
    }
}
