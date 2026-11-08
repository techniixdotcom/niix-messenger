package app.niix.ui

import android.os.SystemClock
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.niix.R
import app.niix.update.UpdateChecker
import app.niix.update.UpdateInfo
import app.niix.update.UpdateInstallResult
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads an update behind a dialog with percentage, size, a progress bar and Cancel. Used by
 * both the manual check in Settings and the automatic one on the home screen, so they behave the
 * same.
 */
object UpdateDownloadDialog {

    /**
     * Limits UI updates. Progress comes in every 64 KB, and redrawing that often would flood the
     * main thread on a fast connection.
     */
    private const val UI_INTERVAL_MILLIS = 100L

    fun start(
        activity: AppCompatActivity,
        checker: UpdateChecker,
        info: UpdateInfo,
        onReady: (File) -> Unit,
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_update_progress, null)
        val status = view.findViewById<TextView>(R.id.update_status)
        val size = view.findViewById<TextView>(R.id.update_size)
        val bar = view.findViewById<LinearProgressIndicator>(R.id.update_progress)
        status.setText(R.string.update_connecting)

        // Read on the download thread, written on the UI thread.
        val cancelled = AtomicBoolean(false)

        val dialog = niixDialog(activity)
            .setTitle(R.string.update_downloading_title)
            .setView(view)
            .setCancelable(false)
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> cancelled.set(true) }
            .show()

        var lastUiUpdate = 0L
        var lastPercent = -1

        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    checker.downloadAndVerify(
                        info,
                        onProgress = { downloaded, total ->
                            val now = SystemClock.elapsedRealtime()
                            val percent = if (total > 0) (downloaded * 100 / total).toInt() else -1
                            // Always show a new whole percent; otherwise throttle.
                            if (percent != lastPercent || now - lastUiUpdate >= UI_INTERVAL_MILLIS) {
                                lastPercent = percent
                                lastUiUpdate = now
                                activity.runOnUiThread {
                                    if (!dialog.isShowing) return@runOnUiThread
                                    if (total > 0) {
                                        status.text = activity.getString(R.string.update_downloading_percent, percent)
                                        size.text = activity.getString(
                                            R.string.update_downloading_size,
                                            megabytes(downloaded),
                                            megabytes(total),
                                        )
                                        // Indeterminate to determinate works while visible, but not
                                        // the other way round, so the bar never goes back.
                                        bar.isIndeterminate = false
                                        bar.setProgressCompat(percent, true)
                                    } else {
                                        // No length from the server: show what's arrived and keep
                                        // the bar indeterminate.
                                        status.setText(R.string.update_downloading_unknown)
                                        size.text = megabytes(downloaded)
                                    }
                                }
                            }
                        },
                        onVerifying = {
                            activity.runOnUiThread {
                                if (!dialog.isShowing) return@runOnUiThread
                                status.setText(R.string.update_verifying)
                                if (!bar.isIndeterminate) bar.setProgressCompat(100, true)
                                // Too late to cancel once the file is here.
                                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
                            }
                        },
                        // Leaving the screen stops the download as well.
                        isCancelled = { cancelled.get() || activity.isFinishing || activity.isDestroyed },
                    )
                }.getOrElse { UpdateInstallResult.Rejected(it.message ?: activity.getString(R.string.error_unknown)) }
            }

            if (dialog.isShowing) dialog.dismiss()
            // Cancelling isn't an error, so no message.
            if (cancelled.get() || activity.isFinishing || activity.isDestroyed) return@launch

            when (result) {
                is UpdateInstallResult.Rejected ->
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.toast_failed, result.reason),
                        Toast.LENGTH_LONG,
                    ).show()
                is UpdateInstallResult.Ready -> onReady(result.apkFile)
            }
        }
    }

    private fun megabytes(bytes: Long): String =
        String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
}
