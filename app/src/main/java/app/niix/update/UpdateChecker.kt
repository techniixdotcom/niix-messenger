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

class UpdateChecker(
    private val context: Context,

    private val repoOwnerSlashName: String = "techniixdotcom/niix-messenger",
    /**
     * Where Tor's SOCKS proxy actually is, supplied by the caller from
     * [app.niix.core.transport.TorTransport.socksAddress] -- never a hardcoded default. The
     * configured port and the port Tor ends up listening on are not always the same (see that
     * method's doc), and assuming they are produced intermittent "could not reach the update
     * server over Tor" failures that looked like network flakiness but were the app connecting
     * to a port nothing was behind.
     */
    private val socksHost: String,

    private val socksPort: Int,
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

            if (!isTrustedAssetUrl(url)) continue
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

        // Deliberately NOT cacheDir. Android deletes files there whenever it wants disk space,
        // with no warning and no callback -- and the window that matters is wide: we write the
        // file, launch the installer, and then the user spends several seconds tapping through
        // confirmation prompts before the installer actually reads it. A cache clear anywhere in
        // that window leaves the installer reading a missing or half-deleted file, which it
        // reports as "package appears to be invalid". Some OEM builds (MIUI in particular) clear
        // cache aggressively enough to hit this regularly. filesDir is never auto-cleared.
        val updatesDir = File(context.filesDir, "updates").apply { mkdirs() }
        val outFile = File(updatesDir, "niix-update.apk")
        // Remove any previous download first: a stale APK from an earlier version left lying
        // around is exactly the kind of thing that gets installed by accident later.
        updatesDir.listFiles()?.forEach { runCatching { it.delete() } }
        outFile.writeBytes(apkBytes)

        // Re-read what actually landed on disk and check it against the bytes we just verified.
        // Everything above this point validates the bytes in memory; this is the only thing that
        // catches a short write, a full disk, or storage that silently dropped part of the file.
        // Handing the installer an APK we haven't confirmed is intact on disk is precisely how
        // an "invalid package" error reaches the user with nothing in the app to explain it.
        val written = runCatching { outFile.readBytes() }.getOrNull()
        if (written == null || !written.contentEquals(apkBytes)) {
            runCatching { outFile.delete() }
            return UpdateInstallResult.Rejected("The update didn't save correctly -- nothing was installed. Please try again.")
        }
        return UpdateInstallResult.Ready(outFile)
    }

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
        connection.connectTimeout = 60_000
        connection.readTimeout = 180_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "niix-update-checker")
        connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, text/plain, */*")
        return connection
    }

    private fun httpGetBytes(urlString: String, attempts: Int = 3): ByteArray? {
        repeat(attempts) {
            val connection = openConnection(urlString)
            try {
                if (connection.responseCode !in 200..299) return@repeat
                val declaredLength = connection.contentLengthLong
                if (declaredLength > MAX_RESPONSE_BYTES) return null

                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                connection.inputStream.use { input ->
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        total += read
                        if (total > MAX_RESPONSE_BYTES) return null
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
        private const val MAX_RESPONSE_BYTES = 300L * 1024 * 1024

        private val TRUSTED_ASSET_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )
    }
}
