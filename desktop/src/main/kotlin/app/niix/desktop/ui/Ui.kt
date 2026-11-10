package app.niix.desktop.ui

import app.niix.desktop.tr
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import org.gnome.adw.AlertDialog
import org.gnome.adw.ResponseAppearance
import org.gnome.adw.Toast
import org.gnome.adw.ToastOverlay
import org.gnome.glib.GLib
import org.gnome.gobject.GObject
import org.gnome.gobject.Value
import org.javagi.gobject.types.Types
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation
import org.gnome.gtk.Widget
import org.gnome.pango.WrapMode

/** Runs coroutines on the GTK main loop, where every widget call has to happen. */
object GtkDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        GLib.idleAddOnce { block.run() }
    }
}

/** The scope UI code launches in: the main loop, never cancelled by one failure. */
val uiScope: CoroutineScope = CoroutineScope(SupervisorJob() + GtkDispatcher)

/** Where toasts go; set by the window once it exists. */
object Toasts {
    var overlay: ToastOverlay? = null

    fun show(message: String) {
        val toast = Toast(message)
        // Toasts show error messages and names, so their text is never read as markup.
        toast.useMarkup = false
        toast.timeout = 4
        overlay?.addToast(toast)
    }
}

fun vbox(spacing: Int = 0): Box = Box(Orientation.VERTICAL, spacing)

fun hbox(spacing: Int = 0): Box = Box(Orientation.HORIZONTAL, spacing)

fun Widget.margins(all: Int): Widget {
    marginTop = all
    marginBottom = all
    marginStart = all
    marginEnd = all
    return this
}

fun Widget.margins(vertical: Int, horizontal: Int): Widget {
    marginTop = vertical
    marginBottom = vertical
    marginStart = horizontal
    marginEnd = horizontal
    return this
}

/** A label that wraps and can be selected, for explanations and message text. */
fun wrappingLabel(text: String, vararg classes: String): Label {
    val label = Label(text)
    label.wrap = true
    label.wrapMode = WrapMode.WORD_CHAR
    label.xalign = 0f
    label.halign = Align.FILL
    classes.forEach(label::addCssClass)
    return label
}

/**
 * Asks a yes/no question and suspends until it's answered. True only for the confirming button;
 * closing the dialog any other way counts as no.
 */
suspend fun confirm(
    parent: Widget,
    heading: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean = false,
    cancelLabel: String = tr("dialog_cancel"),
): Boolean = suspendCancellableCoroutine { continuation ->
    val dialog = AlertDialog(heading, body)
    dialog.addResponse("cancel", cancelLabel)
    dialog.addResponse("confirm", confirmLabel)
    dialog.setResponseAppearance("confirm", if (destructive) ResponseAppearance.DESTRUCTIVE else ResponseAppearance.SUGGESTED)
    dialog.defaultResponse = "cancel"
    dialog.closeResponse = "cancel"
    var answered = false
    dialog.onResponse(null) { response ->
        if (!answered) {
            answered = true
            if (continuation.isActive) continuation.resume(response == "confirm")
        }
    }
    continuation.invokeOnCancellation { GLib.idleAddOnce { dialog.forceClose() } }
    dialog.present(parent)
}

/**
 * Sets a string property through a GValue. Java-GI's setProperty(String, Object) shortcut casts
 * the class structure, which fails for widgets whose class structure is private (PasswordEntry).
 */
fun GObject.setStringProperty(name: String, value: String) {
    val v = Value()
    v.init(Types.STRING)
    v.setString(value)
    setProperty(name, v)
    v.unset()
}

/** A message with a single OK button. */
fun inform(parent: Widget, heading: String, body: String) {
    val dialog = AlertDialog(heading, body)
    dialog.addResponse("ok", tr("dialog_ok"))
    dialog.defaultResponse = "ok"
    dialog.closeResponse = "ok"
    dialog.present(parent)
}
