package app.niix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {

    const val CHANNEL_ID = "niix_connection"
    const val FOREGROUND_NOTIFICATION_ID = 1001
    const val MESSAGE_CHANNEL_ID = "niix_messages"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun foregroundNotification(context: Context, privacy: Boolean, bootstrapProgress: Int? = null): Notification {
        ensureChannel(context)
        // In privacy mode the notification must never reveal anything beyond the static
        // "Running" text -- a calculator that starts showing a live connection percentage
        // is exactly the kind of detail that gives the disguise away. Progress is only ever
        // shown when the user has explicitly turned notification privacy off themselves.
        val text = if (privacy) {
            context.getString(R.string.notif_running_private)
        } else if (bootstrapProgress != null && bootstrapProgress < 100) {
            context.getString(R.string.notif_connecting, bootstrapProgress)
        } else {
            context.getString(R.string.notif_running)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(context.getString(R.string.calc_label))
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setVisibility(
                if (privacy) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE,
            )
            .build()
    }

    private fun ensureMessageChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(MESSAGE_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    MESSAGE_CHANNEL_ID,
                    context.getString(R.string.notif_messages_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    setShowBadge(true)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun postMessage(context: Context, title: String, text: String, conversationId: String) {
        ensureMessageChannel(context)
        val launch = Intent(context, app.niix.ui.HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val id = 2000 + (conversationId.hashCode() and 0xFFFF)
        val pending = PendingIntent.getActivity(
            context, id, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
