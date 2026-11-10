package app.niix.desktop.ui

import app.niix.core.crypto.SafetyNumber
import app.niix.core.model.TrustState
import app.niix.desktop.DesktopContainer
import app.niix.desktop.tr
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.gnome.adw.AlertDialog
import org.gnome.adw.ResponseAppearance
import org.gnome.gtk.Align
import org.gnome.gtk.ContentFit
import org.gnome.gtk.Entry
import org.gnome.gtk.PasswordEntry
import org.gnome.gtk.Picture
import org.gnome.gtk.Widget

/** Asks for one line of text. Null if cancelled. */
suspend fun askText(
    parent: Widget,
    heading: String,
    body: String,
    initial: String = "",
    placeholder: String = "",
    confirmLabel: String = tr("dialog_save"),
    maxLength: Int = 0,
): String? = suspendCancellableCoroutine { continuation ->
    val dialog = AlertDialog(heading, body)
    val entry = Entry()
    entry.text = initial
    entry.placeholderText = placeholder
    if (maxLength > 0) entry.maxLength = maxLength
    entry.activatesDefault = true
    dialog.extraChild = entry
    dialog.addResponse("cancel", tr("dialog_cancel"))
    dialog.addResponse("ok", confirmLabel)
    dialog.setResponseAppearance("ok", ResponseAppearance.SUGGESTED)
    dialog.defaultResponse = "ok"
    dialog.closeResponse = "cancel"
    var answered = false
    dialog.onResponse(null) { response ->
        if (answered) return@onResponse
        answered = true
        if (continuation.isActive) continuation.resume(if (response == "ok") entry.text.trim() else null)
    }
    dialog.present(parent)
    entry.grabFocus()
}

/** Asks for a passphrase twice when [confirmTwice]. Null if cancelled; the caller wipes the result. */
suspend fun askSecret(parent: Widget, heading: String, body: String, confirmTwice: Boolean, confirmLabel: String): CharArray? =
    suspendCancellableCoroutine { continuation ->
        val dialog = AlertDialog(heading, body)
        val first = PasswordEntry()
        first.showPeekIcon = true
        first.setStringProperty("placeholder-text", tr("backup_passphrase_hint"))
        val second = PasswordEntry()
        second.showPeekIcon = true
        second.setStringProperty("placeholder-text", tr("backup_passphrase_confirm"))
        val box = vbox(8)
        box.append(first)
        if (confirmTwice) box.append(second)
        dialog.extraChild = box
        dialog.addResponse("cancel", tr("dialog_cancel"))
        dialog.addResponse("ok", confirmLabel)
        dialog.setResponseAppearance("ok", ResponseAppearance.SUGGESTED)
        dialog.defaultResponse = "ok"
        dialog.closeResponse = "cancel"
        var answered = false
        fun finish(response: String) {
            if (answered) return
            answered = true
            val a = first.text
            val b = second.text
            first.text = ""
            second.text = ""
            val result = when {
                response != "ok" -> null
                a.isEmpty() -> null
                confirmTwice && a != b -> {
                    Toasts.show(tr("backup_passphrase_mismatch"))
                    null
                }
                else -> a.toCharArray()
            }
            if (continuation.isActive) continuation.resume(result)
        }
        dialog.onResponse(null) { response -> finish(response) }
        // Enter moves on to the second field, then confirms, as a form would.
        first.onActivate { if (confirmTwice) second.grabFocus() else { finish("ok"); dialog.forceClose() } }
        second.onActivate { finish("ok"); dialog.forceClose() }
        dialog.present(parent)
        first.grabFocus()
    }

/** One of several choices, or null if cancelled. */
suspend fun choose(parent: Widget, heading: String, body: String, options: List<String>): Int? =
    suspendCancellableCoroutine { continuation ->
        val dialog = AlertDialog(heading, body)
        options.forEachIndexed { index, label -> dialog.addResponse("o$index", label) }
        dialog.addResponse("cancel", tr("dialog_cancel"))
        dialog.closeResponse = "cancel"
        dialog.preferWideLayout = false
        var answered = false
        dialog.onResponse(null) { response ->
            if (answered) return@onResponse
            answered = true
            val index = response.removePrefix("o").toIntOrNull()?.takeIf { response.startsWith("o") }
            if (continuation.isActive) continuation.resume(index)
        }
        dialog.present(parent)
    }

/**
 * The safety number with a contact, as digits and as a QR code, with the option to mark them
 * verified once it's been compared in person.
 */
fun showSafetyNumber(parent: Widget, container: DesktopContainer, onion: String, onVerified: () -> Unit = {}) {
    uiScope.launch {
        val (number, trust) = withContext(Dispatchers.IO) {
            val n = runCatching { container.conversations.safetyNumber(onion) }.getOrNull()
            val t = runCatching { container.conversations.trustState(onion) }.getOrNull()
            n to t
        }
        if (number == null) {
            Toasts.show(tr("toast_no_session"))
            return@launch
        }
        val dialog = AlertDialog(tr("action_safety_number"), tr("safety_number_explainer"))
        val box = vbox(12)
        val digits = wrappingLabel(SafetyNumber.formatted(number), "monospace", "title-4")
        digits.selectable = true
        digits.halign = Align.CENTER
        digits.justify = org.gnome.gtk.Justification.CENTER
        box.append(digits)
        QrCodes.texture(number, 6)?.let { texture ->
            val picture = Picture.forPaintable(texture)
            picture.contentFit = ContentFit.CONTAIN
            picture.setSizeRequest(220, 220)
            picture.halign = Align.CENTER
            picture.addCssClass("qr-frame")
            box.append(picture)
        }
        if (trust == TrustState.VERIFIED) box.append(wrappingLabel(tr("contact_profile_verified"), "success"))
        dialog.extraChild = box
        dialog.addResponse("close", tr("dialog_close"))
        if (trust != TrustState.VERIFIED) {
            dialog.addResponse("verify", tr("action_mark_verified"))
            dialog.setResponseAppearance("verify", ResponseAppearance.SUGGESTED)
        }
        dialog.closeResponse = "close"
        dialog.onResponse(null) { response ->
            if (response != "verify") return@onResponse
            uiScope.launch {
                val pinned = withContext(Dispatchers.IO) { runCatching { container.conversations.markVerified(onion) }.getOrDefault(false) }
                Toasts.show(tr(if (pinned) "toast_verified" else "toast_verify_no_identity"))
                if (pinned) onVerified()
            }
        }
        dialog.present(parent)
    }
}
