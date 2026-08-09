package app.niix.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
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

/**
 * Item 10: opt-in ("check for updates" in Settings, off by default) update checker.
 *
 * Every network call here -- both the GitHub Releases API metadata fetch and the asset
 * downloads -- goes through the app's own Tor SOCKS proxy via a plain [Proxy.Type.SOCKS]
 * [HttpURLConnection], never a direct clearnet connection. TLS validation itself relies on the
 * platform's standard certificate trust store rather than a hardcoded pin: a static pin with no
 * rotation mechanism tends to eventually break this exact feature when the pinned certificate
 * expires, which is a worse failure mode here than standard CA validation, since the actual
 * trust anchor for whether an update is ever installed is the Ed25519 signature below, not TLS.
 *
 * *** SIGNING KEY STATUS ***
 * [RELEASE_SIGNING_PUBLIC_KEY] is set to the real niix release-signing public key. Every
 * release's `.apk` must be signed with the matching private key using a detached Ed25519
 * signature over the raw file bytes, and published alongside it as a `.sig` release asset (that
 * private key is separate from -- and must never be confused with -- the Android APK signing
 * keystore used by build-niix.sh; losing or leaking either one is a real problem, but they are
 * not interchangeable and compromising one does not compromise the other). If you also publish
 * a `SHA256SUMS.txt` asset (`<hex>  <filename>` per line, sha256sum's default format), its
 * value is cross-checked as a sanity check only -- never as a substitute for the signature
 * check. If [RELEASE_SIGNING_PUBLIC_KEY] is ever reset to all zero bytes (e.g. reverted, or a
 * fresh checkout before re-configuring), every signature check fails and no update is ever
 * offered -- deliberately fail-closed, not fail-open.
 */
class UpdateChecker(
    private val context: Context,
    private val repoOwnerSlashName: String = "techniixdotcom/niix-messenger",
    private val socksHost: String = "127.0.0.1",
    // Must match KmpTorTransport's SOCKS_PORT (the app's actual embedded Tor SOCKS listener --
    // not the conventional system-Tor default of 9050, which nothing here is listening on).
    private val socksPort: Int = 9055,
) {

    suspend fun checkForUpdate(currentVersionName: String): UpdateCheckResult {
        val body = httpGetText("https://api.github.com/repos/$repoOwnerSlashName/releases/latest")
            ?: return UpdateCheckResult.Error("Could not reach the update server over Tor")
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
            when {
                name.endsWith(".apk", ignoreCase = true) -> apkUrl = url
                name.endsWith(".sig", ignoreCase = true) -> sigUrl = url
                name.equals("SHA256SUMS.txt", ignoreCase = true) || name.equals("checksums.txt", ignoreCase = true) -> checksumsUrl = url
            }
        }
        if (apkUrl == null) return UpdateCheckResult.Error("Release has no .apk asset")
        if (sigUrl == null) return UpdateCheckResult.Error("Release has no .sig asset -- refusing to offer an unsigned update")

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

    /** Downloads the APK and its signature, verifies the signature (mandatory) and the sha256
     * sanity check (if a checksums asset was published), and stages the file for install. Never
     * returns [UpdateInstallResult.Ready] unless the signature check actually passed. */
    suspend fun downloadAndVerify(info: UpdateInfo): UpdateInstallResult {
        val apkBytes = httpGetBytes(info.apkUrl) ?: return UpdateInstallResult.Rejected("Could not download the update")
        val sigBytes = httpGetBytes(info.sigUrl) ?: return UpdateInstallResult.Rejected("Could not download the update's signature")

        if (!verifyEd25519(apkBytes, sigBytes)) {
            return UpdateInstallResult.Rejected("Signature verification failed -- this update was rejected and discarded")
        }
        info.expectedSha256Hex?.let { expected ->
            if (!sha256Hex(apkBytes).equals(expected.trim(), ignoreCase = true)) {
                return UpdateInstallResult.Rejected("Downloaded file's hash doesn't match the published checksum -- rejected")
            }
        }

        val outFile = File(context.cacheDir, "niix-update.apk")
        outFile.writeBytes(apkBytes)
        return UpdateInstallResult.Ready(outFile)
    }

    /** Launches the system installer for an already-verified APK. Never call this on a file
     * that didn't come from [downloadAndVerify]'s [UpdateInstallResult.Ready]. */
    fun promptInstall(apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun verifyEd25519(data: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != 64) return false
        if (RELEASE_SIGNING_PUBLIC_KEY.all { it == 0.toByte() }) {
            // All-zero fallback safety net (e.g. a fresh checkout before this key is
            // configured) -- fail closed rather than silently accepting nothing as valid.
            return false
        }
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(RELEASE_SIGNING_PUBLIC_KEY))
            verifier.update(data, 0, data.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun openConnection(urlString: String): HttpURLConnection {
        val url = URL(urlString)
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort))
        val connection = url.openConnection(proxy) as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "niix-update-checker")
        connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, text/plain, */*")
        return connection
    }

    private fun httpGetBytes(urlString: String): ByteArray? {
        val connection = openConnection(urlString)
        return try {
            if (connection.responseCode !in 200..299) return null
            if (connection.contentLengthLong > MAX_RESPONSE_BYTES) return null
            connection.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun httpGetText(urlString: String): String? = httpGetBytes(urlString)?.toString(Charsets.UTF_8)

    companion object {
        // See the class doc comment: this MUST be replaced with a real key before this feature
        // is enabled for real users. All zero bytes = every signature check fails, on purpose.
        private val RELEASE_SIGNING_PUBLIC_KEY = byteArrayOf(
            0xf2.toByte(), 0xdc.toByte(), 0xc1.toByte(), 0xfa.toByte(), 0x34, 0x93.toByte(), 0xd7.toByte(), 0xcc.toByte(),
            0xdd.toByte(), 0xc3.toByte(), 0xbd.toByte(), 0xbc.toByte(), 0xd8.toByte(), 0x17, 0xa3.toByte(), 0xbd.toByte(),
            0x1e, 0xe8.toByte(), 0xf8.toByte(), 0x6d, 0x46, 0xad.toByte(), 0x14, 0xed.toByte(),
            0xe8.toByte(), 0xc4.toByte(), 0xe5.toByte(), 0x85.toByte(), 0x66, 0x17, 0x97.toByte(), 0x05,
        )
        private const val MAX_RESPONSE_BYTES = 300L * 1024 * 1024
    }
}
