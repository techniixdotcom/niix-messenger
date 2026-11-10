package app.niix.desktop.ui

import app.niix.core.messaging.IncomingNotice
import app.niix.desktop.tr
import org.gnome.gio.Application
import org.gnome.gio.Notification
import org.gnome.gio.NotificationPriority

/**
 * Desktop notifications, through GLib (the notification portal inside a Flatpak). With
 * "Private notifications" on, which is the default, a message notification says only that there
 * is a new message, never who it's from or what it says.
 */
class Notifier(private val app: Application, private val privateNotifications: () -> Boolean) {

    private val sent = LinkedHashSet<String>()

    private fun send(id: String, notification: Notification) {
        sent.add(id)
        app.sendNotification(id, notification)
    }

    fun message(notice: IncomingNotice) {
        val private = privateNotifications()
        val notification = Notification(if (private) tr("app_name") else notice.title)
        notification.setBody(if (private) tr("notif_new_message_generic") else notice.preview)
        notification.setPriority(NotificationPriority.HIGH)
        notification.setCategory("im.received")
        if (private) {
            notification.setDefaultAction("app.show")
        } else {
            notification.setDefaultAction("app.open-conversation::${notice.conversationId}")
        }
        send(if (private) MESSAGE_ID else "message-${notice.conversationId}", notification)
    }

    fun incomingCall() {
        // Never says who: that shows in the window, once it's unlocked.
        val notification = Notification(tr("app_name"))
        notification.setBody(tr("notif_incoming_call"))
        notification.setPriority(NotificationPriority.URGENT)
        notification.setCategory("call.incoming")
        notification.setDefaultAction("app.show")
        notification.addButton(tr("call_answer"), "app.answer-call")
        notification.addButton(tr("call_decline"), "app.decline-call")
        send(CALL_ID, notification)
    }

    fun cancelIncomingCall() {
        app.withdrawNotification(CALL_ID)
    }

    fun missedCall() {
        val notification = Notification(tr("notif_missed_call"))
        notification.setPriority(NotificationPriority.NORMAL)
        notification.setCategory("call.unanswered")
        notification.setDefaultAction("app.show")
        send(MISSED_ID, notification)
    }

    /** Takes back everything shown, when the app locks or quits. */
    fun withdrawAll() {
        sent.forEach(app::withdrawNotification)
        sent.clear()
    }

    private companion object {
        const val MESSAGE_ID = "message"
        const val CALL_ID = "call"
        const val MISSED_ID = "missed-call"
    }
}
