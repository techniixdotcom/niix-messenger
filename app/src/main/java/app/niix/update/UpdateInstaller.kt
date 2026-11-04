package app.niix.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import app.niix.R
import app.niix.core.model.DiagnosticLog
import java.io.File

/**
 * Installs an update through Android's [PackageInstaller] session API.
 *
 * The old ACTION_VIEW-on-a-content-URI approach is deprecated and tells the app nothing when it
 * fails; everything shows up as the installer's generic "package appears to be invalid". A session
 * streams the APK straight in (no FileProvider or URI permissions to go wrong) and reports
 * Android's real status code and message, which [UpdateInstallReceiver] turns into something
 * readable.
 */
object UpdateInstaller {

    const val ACTION_INSTALL_STATUS = "app.niix.update.INSTALL_STATUS"

    fun install(context: Context, apkFile: File): String? {
        if (!apkFile.isFile || apkFile.length() == 0L) {
            return context.getString(R.string.install_err_missing)
        }
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            val sessionId = installer.createSession(params)

            installer.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    // With the exact length declared, Android catches a short write itself instead
                    // of failing later on a partial APK.
                    session.openWrite(WRITE_NAME, 0, apkFile.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }

                // Addressed to the one receiver that handles it. The intent is mutable, so it
                // should say exactly where it goes.
                val intent = Intent(ACTION_INSTALL_STATUS)
                    .setPackage(context.packageName)
                    .setClass(context, UpdateInstallReceiver::class.java)
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // The system writes the status into this intent, so it has to be mutable; API
                    // 31+ requires the flag.
                    flags = flags or PendingIntent.FLAG_MUTABLE
                }
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
            DiagnosticLog.record("update", "install session committed (${apkFile.length()} bytes)")
            null
        } catch (e: Exception) {
            DiagnosticLog.record("update", "install session could not start: ${e::class.java.simpleName}")
            context.getString(R.string.install_err_start, e.message ?: e::class.java.simpleName)
        }
    }

    private const val WRITE_NAME = "niix-update"
}

class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Normal case: Android wants the user to confirm, and launching this intent shows
                // the dialog.
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
                val described = describe(context, status, systemMessage)
                // Keep the raw status code next to the friendly text; the code is what identifies
                // the cause.
                DiagnosticLog.record("update", "install FAILED status=$status msg=${systemMessage ?: "none"}")
                UpdateInstallStatus.lastFailure = described
            }
        }
    }

    private fun describe(context: Context, status: Int, systemMessage: String?): String {
        // Android's own message is in English whatever the app's language, but it's still the most
        // precise thing to show, so it goes in brackets.
        val detail = systemMessage?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        // Samsung's Auto Blocker refuses installs and updates from outside the Galaxy Store and Play
        // Store, and is on by default on phones with One UI 6.1.1 or later. Nothing an app can do
        // gets around it, so the useful thing is to say what it is and where to turn it off. Added
        // to both the blocked and the generic failure, since which one it reports isn't documented.
        val samsungHint = if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            " " + context.getString(R.string.install_samsung_auto_blocker)
        } else {
            ""
        }
        return when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> context.getString(R.string.install_cancelled)
            PackageInstaller.STATUS_FAILURE_BLOCKED -> context.getString(R.string.install_blocked, detail) + samsungHint
            PackageInstaller.STATUS_FAILURE_CONFLICT -> context.getString(R.string.install_conflict, detail)
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> context.getString(R.string.install_incompatible, detail)
            PackageInstaller.STATUS_FAILURE_INVALID -> context.getString(R.string.install_invalid, detail)
            PackageInstaller.STATUS_FAILURE_STORAGE -> context.getString(R.string.install_storage, detail)
            else -> context.getString(R.string.install_failed, status, detail) + samsungHint
        }
    }
}

/** The latest install failure, so the UI can show it once the broadcast arrives. */
object UpdateInstallStatus {
    @Volatile
    var lastFailure: String? = null
}
