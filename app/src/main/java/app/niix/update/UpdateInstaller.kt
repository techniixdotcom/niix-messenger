package app.niix.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import app.niix.core.model.DiagnosticLog
import java.io.File

/**
 * Installs an update using Android's [PackageInstaller] session API.
 *
 * This replaces the older approach of firing an `ACTION_VIEW` intent at a `content://` URI with
 * the APK MIME type. That approach is pre-Nougat, deprecated, increasingly unreliable on modern
 * Android, and -- the part that actually mattered here -- gives the app back *nothing* when it
 * fails. Every failure surfaced as the system installer's generic "app not installed as package
 * appears to be invalid", which is a catch-all covering a version downgrade, a signing-key
 * mismatch, a malformed archive and several unrelated conditions. With no way to tell those
 * apart, diagnosing a failure meant guessing.
 *
 * [PackageInstaller] streams the APK bytes straight into an install session (so FileProvider,
 * URI permissions, and any question of whether the installer can actually read the file all stop
 * being possible failure points), and reports the genuine result back through a broadcast --
 * including Android's own status code and message. [UpdateInstallReceiver] turns that into
 * something a person can act on.
 */
object UpdateInstaller {

    const val ACTION_INSTALL_STATUS = "app.niix.update.INSTALL_STATUS"

    /**
     * Starts installing [apkFile]. Returns null if the session was created and handed off
     * successfully, or a human-readable reason if it couldn't even be started -- the outcome of
     * the install itself arrives later via [UpdateInstallReceiver], since Android runs it
     * asynchronously and prompts the user for confirmation partway through.
     */
    fun install(context: Context, apkFile: File): String? {
        if (!apkFile.isFile || apkFile.length() == 0L) {
            return "The downloaded update is missing or empty."
        }
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            val sessionId = installer.createSession(params)

            installer.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    // Declaring the exact length lets Android detect a short write itself rather
                    // than committing a partial APK and failing later with a parse error.
                    session.openWrite(WRITE_NAME, 0, apkFile.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }

                val intent = Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName)
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // The system fills this intent in with the status extras, so it has to be
                    // mutable. Required from API 31 onward, where a flag is mandatory.
                    flags = flags or PendingIntent.FLAG_MUTABLE
                }
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
            DiagnosticLog.record("update", "install session committed (${apkFile.length()} bytes)")
            null
        } catch (e: Exception) {
            DiagnosticLog.record("update", "install session could not start: ${e::class.java.simpleName}")
            "Couldn't start the installation: ${e.message ?: e::class.java.simpleName}"
        }
    }

    private const val WRITE_NAME = "niix-update"
}

/**
 * Receives the real outcome of an install session. This is the piece that was missing before:
 * Android tells us exactly why an install failed, and that reason gets shown to the user
 * instead of the installer's uninformative catch-all.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Normal: Android wants the user to confirm. Launching the supplied intent is
                // what shows the confirmation dialog.
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> DiagnosticLog.record("update", "install SUCCESS")
            else -> {
                val systemMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val described = describe(status, systemMessage)
                // Record the raw status code alongside the friendly text: the code is what
                // actually identifies the cause, and it is the thing worth reporting.
                DiagnosticLog.record("update", "install FAILED status=$status msg=${systemMessage ?: "none"}")
                UpdateInstallStatus.lastFailure = described
            }
        }
    }

    /**
     * Turns Android's status code into something that identifies the actual problem and says
     * what to do about it. These are the conditions the old install path lumped together into
     * one meaningless message.
     */
    private fun describe(status: Int, systemMessage: String?): String {
        val detail = systemMessage?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                "Installation was cancelled."
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                "Android blocked the installation$detail. On some phones you need to allow this " +
                    "app to install unknown apps, in Settings > Apps > Special access."
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "This update conflicts with the installed version$detail. That usually means it " +
                    "was signed with a different key than the app already on this device, which " +
                    "Android never allows as an update. Export a backup from Settings first, " +
                    "then uninstall and install fresh -- after that, future updates will work."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "This update isn't compatible with this device$detail."
            PackageInstaller.STATUS_FAILURE_INVALID ->
                "Android rejected the update file as invalid$detail. If the version number isn't " +
                    "higher than the installed one, that's the usual cause."
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                "Not enough free storage to install the update$detail."
            else ->
                "Installation failed (code $status)$detail."
        }
    }
}

/** Holds the most recent install failure so the UI can show it after the broadcast arrives. */
object UpdateInstallStatus {
    @Volatile
    var lastFailure: String? = null
}
