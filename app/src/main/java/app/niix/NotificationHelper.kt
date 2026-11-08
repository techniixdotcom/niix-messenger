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
    private const val CALL_CHANNEL_ID = "niix_calls"
    private const val ONGOING_CALL_CHANNEL_ID = "niix_call_ongoing"
    private const val INCOMING_CALL_NOTIFICATION_ID = 2002
    private const val MISSED_CALL_NOTIFICATION_ID = 2003
    const val CALL_NOTIFICATION_ID = 1002

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
        // Rings like a phone: the ringtone, repeated until answered or gone, and vibration. Android
        // applies Do Not Disturb and silent mode to it as it would to any call.
        val calls = NotificationChannel(
            CALL_CHANNEL_ID,
            context.getString(R.string.notif_calls_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setSound(
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 800)
        }
        val ongoingCall = NotificationChannel(
            ONGOING_CALL_CHANNEL_ID,
            context.getString(R.string.notif_call_ongoing_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannels(listOf(connection, messages, calls, ongoingCall))
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

    private fun callScreen(context: Context, requestCode: Int, action: String? = null): PendingIntent {
        val intent = Intent(context, app.niix.ui.CallActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun callAction(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, app.niix.call.CallActionReceiver::class.java).setAction(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Rings for an incoming call. Like message notifications it never names the caller: anyone
     * near the phone would see who is calling. The call screen shows it once the phone is unlocked.
     *
     * Where NiiX may use full-screen notifications it's a call-style notification, which Android
     * keeps on screen until it's answered or declined, and which opens the call screen over the
     * lock screen. Where it may not, it's an ordinary high-priority one, which Android tucks into
     * the notification shade after a few seconds; the caller of this re-posts it so it comes back.
     */
    fun postIncomingCall(context: Context) {
        ensureChannels(context)
        val decline = callAction(context, 32, app.niix.call.CallActionReceiver.ACTION_DECLINE)
        val answer = callScreen(context, 33, app.niix.ui.CallActivity.ACTION_ANSWER)
        val builder = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(NiixTheme.current(context).accent(context))
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_incoming_call))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(callScreen(context, 30))
            .setFullScreenIntent(callScreen(context, 31), true)
        if (app.niix.ui.CallReadiness.canRingFullScreen(context)) {
            // Android requires a call-style notification to carry a full-screen intent (or come
            // from a foreground service), so it's only used where that intent is allowed through.
            val caller = androidx.core.app.Person.Builder()
                .setName(context.getString(R.string.call_hidden_name))
                .setImportant(true)
                .build()
            builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
        } else {
            builder
                .addAction(0, context.getString(R.string.call_decline), decline)
                .addAction(0, context.getString(R.string.call_answer), answer)
        }
        val notification = builder.build()
        // Keeps ringing until it's answered, declined or the caller gives up.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        runCatching { NotificationManagerCompat.from(context).notify(INCOMING_CALL_NOTIFICATION_ID, notification) }
            .onFailure { app.niix.core.model.DiagnosticLog.record("call", "couldn't show the incoming call (${it::class.java.simpleName})") }
    }

    fun cancelIncomingCall(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(INCOMING_CALL_NOTIFICATION_ID) }
    }

    /** A call that wasn't picked up. Says who only inside the app, like everything else here. */
    fun postMissedCall(context: Context) {
        ensureChannels(context)
        val launch = Intent(context, app.niix.ui.HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context, MISSED_CALL_NOTIFICATION_ID, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(NiixTheme.current(context).accent(context))
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_missed_call))
            .setCategory("missed_call")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(MISSED_CALL_NOTIFICATION_ID, notification) }
    }

    /** Shown while a call is connected, by CallService. */
    fun ongoingCall(context: Context): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, ONGOING_CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(NiixTheme.current(context).accent(context))
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_call_in_progress))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(callScreen(context, 34))
            .addAction(0, context.getString(R.string.call_hang_up), callAction(context, 35, app.niix.call.CallActionReceiver.ACTION_HANG_UP))
            .build()
    }
}
