package app.niix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.niix.ui.NiixTheme

object NotificationHelper {

    private const val CHANNEL_ID = "niix_connection"
    private const val MESSAGE_NOTIFICATION_ID = 2001
    const val FOREGROUND_NOTIFICATION_ID = 1001
    private const val MESSAGE_CHANNEL_ID = "niix_messages"

    @Volatile
    private var channelsCreated = false

    // Once per process. Creating a channel that already exists only updates its name, which is
    // how installs from before the rename pick up the new names; importance and anything the user
    // changed are kept.
    private fun ensureChannels(context: Context) {
        if (channelsCreated) return
        val connection = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        val messages = NotificationChannel(
            MESSAGE_CHANNEL_ID,
            context.getString(R.string.notif_messages_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(connection, messages))
        channelsCreated = true
    }

    fun foregroundNotification(context: Context, privacy: Boolean, bootstrapProgress: Int? = null): Notification {
        ensureChannels(context)
        // In privacy mode the notification only ever says "Running". Progress only appears if the
        // user turned privacy off.
        val text = if (privacy) {
            context.getString(R.string.notif_running_private)
        } else if (bootstrapProgress != null && bootstrapProgress < 100) {
            context.getString(R.string.notif_connecting, bootstrapProgress)
        } else {
            context.getString(R.string.notif_running)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(NiixTheme.current(context).accent(context))
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setVisibility(
                if (privacy) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE,
            )
            .build()
    }

    fun postMessage(context: Context, text: String) {
        ensureChannels(context)
        // Sender and message text are never included.
        val launch = Intent(context, app.niix.ui.HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        // One notification id for everything. Separate ids per conversation would show how many
        // people are messaging you to anyone glancing at the phone.
        val id = MESSAGE_NOTIFICATION_ID
        val pending = PendingIntent.getActivity(
            context, id, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(NiixTheme.current(context).accent(context))
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
