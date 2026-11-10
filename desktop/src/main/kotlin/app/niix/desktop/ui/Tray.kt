package app.niix.desktop.ui

import app.niix.core.model.DiagnosticLog
import java.lang.foreign.Arena
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gnome.gio.DBusCallFlags
import org.gnome.gio.DBusConnection
import org.gnome.gio.DBusInterfaceVTable
import org.gnome.gio.DBusMethodInvocation
import org.gnome.gio.DBusNodeInfo
import org.gnome.gio.DBusSignalFlags
import org.gnome.glib.Variant
import org.gnome.glib.VariantType

/**
 * The system tray icon: clicking it brings the window back, and its menu opens, locks or quits
 * NiiX. GTK 4 has no tray of its own, so this speaks the desktop's protocol for one directly: a
 * StatusNotifierItem on the session bus, with its menu as a com.canonical.dbusmenu object. KDE,
 * Cinnamon, XFCE, MATE, Budgie and others show it as it is; GNOME shows it with the AppIndicator
 * extension. Where nothing shows tray icons, [available] stays false and NiiX carries on without.
 *
 * It never shows anything about messages: no counts, no names, no change when one arrives.
 */
class Tray(
    private val connection: DBusConnection,
    private val iconName: String,
    /** Where the icon is when it isn't installed for the desktop to find, or null. */
    private val iconThemePath: String?,
    private val title: String,
    private val items: () -> List<Item>,
    private val onActivate: () -> Unit,
    /** Called when a tray appears or goes away. */
    private val onAvailability: (Boolean) -> Unit,
) {

    /** One menu entry. Ids are stable for the life of the tray, and never 0, which is the menu itself. */
    class Item(val id: Int, val label: String, val run: () -> Unit)

    /** True while a desktop tray has this icon. */
    @Volatile
    var available: Boolean = false
        private set

    private val registrations = ArrayList<Int>()
    private val subscriptions = ArrayList<Int>()
    private var revision = 1
    private var servedSignature = ""
    private var disposed = false

    fun start() {
        if (!register()) return
        // The tray can start after NiiX does, or restart: it's offered the icon whenever it appears.
        subscriptions += connection.signalSubscribe(
            "org.freedesktop.DBus", "org.freedesktop.DBus", "NameOwnerChanged", "/org/freedesktop/DBus",
            WATCHER, DBusSignalFlags.NONE,
        ) { _, _, _, _, _, parameters ->
            val owner = runCatching { parameters.getChildValue(2).getString(null) }.getOrDefault("")
            if (owner.isNullOrEmpty()) setAvailable(false) else announce()
        }
        subscriptions += connection.signalSubscribe(
            WATCHER, WATCHER, null, "/StatusNotifierWatcher", null, DBusSignalFlags.NONE,
        ) { _, _, _, _, signal, _ ->
            if (signal == "StatusNotifierHostRegistered" || signal == "StatusNotifierHostUnregistered") checkHost()
        }
        announce()
    }

    /** The menu's labels changed (the lock entry comes and goes): tells the tray to read it again. */
    fun refreshMenu() {
        if (disposed || registrations.isEmpty()) return
        revision++
        runCatching { connection.emitSignal(null, MENU_PATH, MENU, "LayoutUpdated", variant("(uint32 $revision, 0)")) }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        available = false
        subscriptions.forEach { runCatching { connection.signalUnsubscribe(it) } }
        registrations.forEach { runCatching { connection.unregisterObject(it) } }
        subscriptions.clear()
        registrations.clear()
    }

    private fun register(): Boolean = runCatching {
        val node = DBusNodeInfo.forXml(INTROSPECTION)
        val arena = Arena.global()
        val itemTable = DBusInterfaceVTable(
            { _, _, _, _, method, _, invocation -> guarded(invocation) { itemCall(method, invocation) } },
            { _, _, _, _, property, _ -> runCatching { itemProperty(property)?.ref() }.getOrNull() },
            null,
            arena,
        )
        val menuTable = DBusInterfaceVTable(
            { _, _, _, _, method, parameters, invocation -> guarded(invocation) { menuCall(method, parameters, invocation) } },
            { _, _, _, _, property, _ -> runCatching { menuProperty(property)?.ref() }.getOrNull() },
            null,
            arena,
        )
        registrations += connection.registerObject(ITEM_PATH, node.lookupInterface(ITEM) ?: error("no item interface"), itemTable, null, null)
        registrations += connection.registerObject(MENU_PATH, node.lookupInterface(MENU) ?: error("no menu interface"), menuTable, null, null)
        true
    }.getOrElse {
        DiagnosticLog.record("tray", "the tray icon could not be set up (${it.javaClass.simpleName})")
        registrations.forEach { id -> runCatching { connection.unregisterObject(id) } }
        registrations.clear()
        false
    }

    /** A handler that throws still answers, so the tray is never left waiting on a call. */
    private fun guarded(invocation: DBusMethodInvocation, handler: () -> Unit) {
        runCatching(handler).onFailure {
            runCatching { invocation.returnDbusError("org.freedesktop.DBus.Error.Failed", "failed") }
        }
    }

    /** Offers the icon to whatever keeps the tray, if anything does. */
    private fun announce() {
        if (disposed) return
        val name = connection.uniqueName ?: return
        uiScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    connection.callSync(
                        WATCHER, "/StatusNotifierWatcher", WATCHER, "RegisterStatusNotifierItem",
                        variant("(${quote(name)},)"), null, DBusCallFlags.NONE, CALL_TIMEOUT_MILLIS, null,
                    )
                }.isSuccess
            }
            if (ok) checkHost() else setAvailable(false)
        }
    }

    /** A watcher with nothing drawing the icons (GNOME without the extension) is no tray at all. */
    private fun checkHost() {
        if (disposed) return
        uiScope.launch {
            val hosted = withContext(Dispatchers.IO) {
                runCatching {
                    connection.callSync(
                        WATCHER, "/StatusNotifierWatcher", "org.freedesktop.DBus.Properties", "Get",
                        variant("(${quote(WATCHER)}, 'IsStatusNotifierHostRegistered')"), VariantType("(v)"),
                        DBusCallFlags.NONE, CALL_TIMEOUT_MILLIS, null,
                    ).getChildValue(0).getVariant().getBoolean()
                }.getOrDefault(false)
            }
            setAvailable(hosted)
        }
    }

    private fun setAvailable(value: Boolean) {
        if (disposed || available == value) return
        available = value
        onAvailability(value)
    }

    private fun itemCall(method: String, invocation: DBusMethodInvocation) {
        when (method) {
            "Activate", "SecondaryActivate" -> onActivate()
            // "ContextMenu" and "Scroll": the tray shows the menu itself; scrolling does nothing.
        }
        invocation.returnValue(null)
    }

    private fun itemProperty(property: String): Variant? = when (property) {
        "Category" -> variant("'Communications'")
        "Id" -> variant(quote(iconName))
        "Title" -> variant(quote(title))
        "Status" -> variant("'Active'")
        "WindowId" -> variant("int32 0")
        "IconName" -> variant(quote(iconName))
        "IconThemePath" -> variant(quote(iconThemePath ?: ""))
        "OverlayIconName", "AttentionIconName", "AttentionMovieName" -> variant("''")
        "IconPixmap", "OverlayIconPixmap", "AttentionIconPixmap" -> variant("@a(iiay) []")
        "ToolTip" -> variant("('', @a(iiay) [], ${quote(title)}, '')")
        "ItemIsMenu" -> variant("false")
        "Menu" -> variant("objectpath '$MENU_PATH'")
        else -> null
    }

    private fun menuProperty(property: String): Variant? = when (property) {
        "Version" -> variant("uint32 3")
        "TextDirection" -> variant("'ltr'")
        "Status" -> variant("'normal'")
        "IconThemePath" -> variant("@as []")
        else -> null
    }

    private fun menuCall(method: String, parameters: Variant, invocation: DBusMethodInvocation) {
        when (method) {
            "GetLayout" -> {
                val parent = parameters.getChildValue(0).getInt32()
                val entries = items()
                servedSignature = signature(entries)
                val layout = when {
                    parent == 0 -> "(0, {'children-display': <'submenu'>}, [${entries.joinToString(", ") { "<${entry(it)}>" }}])"
                    else -> entries.firstOrNull { it.id == parent }?.let(::entry) ?: "(int32 $parent, @a{sv} {}, @av [])"
                }
                invocation.returnValue(variant("(uint32 $revision, @(ia{sv}av) $layout)"))
            }
            "GetGroupProperties" -> {
                val wanted = intsOf(parameters.getChildValue(0))
                val entries = items().filter { wanted.isEmpty() || it.id in wanted }
                invocation.returnValue(variant("(@a(ia{sv}) [${entries.joinToString(", ") { "(${it.id}, ${properties(it)})" }}],)"))
            }
            "GetProperty" -> {
                val id = parameters.getChildValue(0).getInt32()
                val name = parameters.getChildValue(1).getString(null)
                val item = items().firstOrNull { it.id == id }
                val value = when {
                    item == null -> "''"
                    name == "label" -> quote(item.label.replace("_", "__"))
                    name == "type" && item.label.isEmpty() -> "'separator'"
                    name == "enabled" || name == "visible" -> "true"
                    else -> "''"
                }
                invocation.returnValue(variant("(<$value>,)"))
            }
            "Event" -> {
                val id = parameters.getChildValue(0).getInt32()
                val event = parameters.getChildValue(1).getString(null)
                invocation.returnValue(null)
                if (event == "clicked") clicked(id)
            }
            "EventGroup" -> {
                val events = parameters.getChildValue(0)
                val clicks = (0 until events.nChildren().toInt()).mapNotNull { index ->
                    val one = events.getChildValue(index.toLong())
                    one.getChildValue(0).getInt32().takeIf { one.getChildValue(1).getString(null) == "clicked" }
                }
                invocation.returnValue(variant("(@ai [],)"))
                clicks.forEach(::clicked)
            }
            "AboutToShow" -> {
                // Entries come and go (Lock, with a passcode): a menu that changed is read again.
                val changed = signature(items()) != servedSignature
                invocation.returnValue(variant("(${changed},)"))
                if (changed) refreshMenu()
            }
            "AboutToShowGroup" -> invocation.returnValue(variant("(@ai [], @ai [])"))
            else -> invocation.returnDbusError("org.freedesktop.DBus.Error.UnknownMethod", "unknown")
        }
    }

    private fun signature(entries: List<Item>): String = entries.joinToString("|") { "${it.id}:${it.label}" }

    private fun clicked(id: Int) {
        items().firstOrNull { it.id == id && it.label.isNotEmpty() }?.run?.invoke()
    }

    private fun entry(item: Item): String = "(int32 ${item.id}, ${properties(item)}, @av [])"

    /** An item with no label is a separator. */
    private fun properties(item: Item): String =
        if (item.label.isEmpty()) "@a{sv} {'type': <'separator'>}"
        // An underscore marks a keyboard shortcut in this protocol; doubled, it's just an underscore.
        else "@a{sv} {'label': <${quote(item.label.replace("_", "__"))}>, 'enabled': <true>, 'visible': <true>}"

    private fun intsOf(array: Variant): Set<Int> =
        (0 until array.nChildren().toInt()).map { array.getChildValue(it.toLong()).getInt32() }.toSet()

    private fun variant(text: String): Variant = Variant.parse(null, text, null, null)

    /** A string as GVariant text. */
    private fun quote(text: String): String = buildString {
        append('\'')
        text.forEach { c ->
            when {
                c == '\\' -> append("\\\\")
                c == '\'' -> append("\\'")
                c == '\n' || c == '\r' || c == '\t' -> append(' ')
                c.code < 0x20 -> Unit
                else -> append(c)
            }
        }
        append('\'')
    }

    private companion object {
        const val WATCHER = "org.kde.StatusNotifierWatcher"
        const val ITEM = "org.kde.StatusNotifierItem"
        const val MENU = "com.canonical.dbusmenu"
        const val ITEM_PATH = "/StatusNotifierItem"
        const val MENU_PATH = "/MenuBar"
        const val CALL_TIMEOUT_MILLIS = 5_000

        const val INTROSPECTION = """
<node>
  <interface name="org.kde.StatusNotifierItem">
    <property name="Category" type="s" access="read"/>
    <property name="Id" type="s" access="read"/>
    <property name="Title" type="s" access="read"/>
    <property name="Status" type="s" access="read"/>
    <property name="WindowId" type="i" access="read"/>
    <property name="IconThemePath" type="s" access="read"/>
    <property name="Menu" type="o" access="read"/>
    <property name="ItemIsMenu" type="b" access="read"/>
    <property name="IconName" type="s" access="read"/>
    <property name="IconPixmap" type="a(iiay)" access="read"/>
    <property name="OverlayIconName" type="s" access="read"/>
    <property name="OverlayIconPixmap" type="a(iiay)" access="read"/>
    <property name="AttentionIconName" type="s" access="read"/>
    <property name="AttentionIconPixmap" type="a(iiay)" access="read"/>
    <property name="AttentionMovieName" type="s" access="read"/>
    <property name="ToolTip" type="(sa(iiay)ss)" access="read"/>
    <method name="ContextMenu"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
    <method name="Activate"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
    <method name="SecondaryActivate"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
    <method name="Scroll"><arg name="delta" type="i" direction="in"/><arg name="orientation" type="s" direction="in"/></method>
    <signal name="NewTitle"/>
    <signal name="NewIcon"/>
    <signal name="NewAttentionIcon"/>
    <signal name="NewOverlayIcon"/>
    <signal name="NewToolTip"/>
    <signal name="NewStatus"><arg name="status" type="s"/></signal>
  </interface>
  <interface name="com.canonical.dbusmenu">
    <property name="Version" type="u" access="read"/>
    <property name="TextDirection" type="s" access="read"/>
    <property name="Status" type="s" access="read"/>
    <property name="IconThemePath" type="as" access="read"/>
    <method name="GetLayout">
      <arg name="parentId" type="i" direction="in"/><arg name="recursionDepth" type="i" direction="in"/><arg name="propertyNames" type="as" direction="in"/>
      <arg name="revision" type="u" direction="out"/><arg name="layout" type="(ia{sv}av)" direction="out"/>
    </method>
    <method name="GetGroupProperties">
      <arg name="ids" type="ai" direction="in"/><arg name="propertyNames" type="as" direction="in"/>
      <arg name="properties" type="a(ia{sv})" direction="out"/>
    </method>
    <method name="GetProperty">
      <arg name="id" type="i" direction="in"/><arg name="name" type="s" direction="in"/><arg name="value" type="v" direction="out"/>
    </method>
    <method name="Event">
      <arg name="id" type="i" direction="in"/><arg name="eventId" type="s" direction="in"/><arg name="data" type="v" direction="in"/><arg name="timestamp" type="u" direction="in"/>
    </method>
    <method name="EventGroup">
      <arg name="events" type="a(isvu)" direction="in"/><arg name="idErrors" type="ai" direction="out"/>
    </method>
    <method name="AboutToShow">
      <arg name="id" type="i" direction="in"/><arg name="needUpdate" type="b" direction="out"/>
    </method>
    <method name="AboutToShowGroup">
      <arg name="ids" type="ai" direction="in"/><arg name="updatesNeeded" type="ai" direction="out"/><arg name="idErrors" type="ai" direction="out"/>
    </method>
    <signal name="ItemsPropertiesUpdated"><arg type="a(ia{sv})"/><arg type="a(ias)"/></signal>
    <signal name="LayoutUpdated"><arg type="u"/><arg type="i"/></signal>
    <signal name="ItemActivationRequested"><arg type="i"/><arg type="u"/></signal>
  </interface>
</node>
"""
    }
}
