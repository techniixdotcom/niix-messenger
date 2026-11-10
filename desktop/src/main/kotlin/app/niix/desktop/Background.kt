package app.niix.desktop

import app.niix.core.model.DiagnosticLog
import java.io.File
import org.gnome.gio.Application
import org.gnome.gio.BusType
import org.gnome.gio.DBusCallFlags
import org.gnome.gio.Gio
import org.gnome.gio.Notification
import org.gnome.glib.Variant
import org.gnome.glib.VariantType

/**
 * Running in the background and starting at login. Closing the window leaves NiiX running, the
 * way the phone app keeps its service, so messages still arrive; it says so the first time.
 *
 * Starting at login goes through the Background portal inside a Flatpak, which also lets the
 * desktop know NiiX is meant to run without a window. Outside one, it's an XDG autostart entry.
 */
object Background {

    private const val NOTICE_FILE = "background-notice-shown"
    private const val AUTOSTART_NAME = "app.niix.NiiX.desktop"

    val inFlatpak: Boolean get() = File("/.flatpak-info").exists()

    /** Tells the user once that closing the window didn't quit NiiX. */
    fun onWindowHidden(app: Application, container: DesktopContainer) {
        val marker = File(container.paths.filesDir, NOTICE_FILE)
        if (marker.exists()) return
        runCatching { marker.createNewFile() }
        val notification = Notification(tr("app_name"))
        notification.setBody(tr("desktop_background_notice"))
        notification.setDefaultAction("app.show")
        notification.addButton(tr("desktop_quit"), "app.quit")
        app.sendNotification("background", notification)
    }

    /** Whether NiiX is set to start at login. Only known for the autostart entry outside a Flatpak. */
    fun autostartEnabled(container: DesktopContainer): Boolean =
        if (inFlatpak) File(container.paths.filesDir, AUTOSTART_MARKER).exists() else autostartFile().isFile

    /** Asks for NiiX to start (hidden) at login, or stops it. Blocks briefly; call off the UI thread. */
    fun setAutostart(container: DesktopContainer, enabled: Boolean): Boolean = runCatching {
        if (inFlatpak) {
            requestBackground(enabled)
            val marker = File(container.paths.filesDir, AUTOSTART_MARKER)
            if (enabled) marker.createNewFile() else marker.delete()
        } else {
            val file = autostartFile()
            if (enabled) {
                file.parentFile?.mkdirs()
                val exec = launcherCommand()
                file.writeText(
                    """
                    [Desktop Entry]
                    Type=Application
                    Name=NiiX
                    Exec=$exec --background
                    Icon=app.niix.NiiX
                    X-GNOME-Autostart-enabled=true
                    NoDisplay=true
                    """.trimIndent() + "\n",
                )
            } else {
                file.delete()
            }
        }
        true
    }.onFailure { DiagnosticLog.record("desktop", "autostart change failed (${it.javaClass.simpleName})") }.getOrDefault(false)

    private const val AUTOSTART_MARKER = "autostart-requested"

    private fun requestBackground(autostart: Boolean) {
        val bus = Gio.busGetSync(BusType.SESSION, null)
        val text = "('', {'reason': <${quote(tr("desktop_background_reason"))}>, " +
            "'autostart': <${if (autostart) "true" else "false"}>, " +
            "'commandline': <['niix', '--background']>, 'dbus-activatable': <false>})"
        val parameters = Variant.parse(VariantType("(sa{sv})"), text, null, null)
        bus.callSync(
            "org.freedesktop.portal.Desktop",
            "/org/freedesktop/portal/desktop",
            "org.freedesktop.portal.Background",
            "RequestBackground",
            parameters,
            VariantType("(o)"),
            DBusCallFlags.NONE,
            PORTAL_TIMEOUT_MILLIS,
            null,
        )
    }

    /** A GVariant text-format string literal. */
    private fun quote(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
        return "'$escaped'"
    }

    private fun autostartFile(): File {
        val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".config")
        return File(File(config, "autostart"), AUTOSTART_NAME)
    }

    /** The command that started NiiX, as recorded by its launcher script, or plain "niix". */
    private fun launcherCommand(): String {
        val launcher = System.getProperty("niix.launcher")?.takeIf { File(it).canExecute() } ?: "niix"
        return if (launcher.any { it.isWhitespace() || it == '"' }) "\"" + launcher.replace("\"", "\\\"") + "\"" else launcher
    }

    private const val PORTAL_TIMEOUT_MILLIS = 30_000
}
