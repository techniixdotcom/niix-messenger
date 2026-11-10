package app.niix.desktop.ui

import app.niix.core.messaging.CallState
import app.niix.core.model.ConversationType
import app.niix.core.model.Message
import app.niix.core.model.MessageDirection
import app.niix.core.model.MessageType
import app.niix.core.storage.PendingGroupInvite
import app.niix.core.storage.SettingsStore
import app.niix.core.transport.TransportState
import app.niix.desktop.DesktopContainer
import app.niix.desktop.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gnome.adw.ActionRow
import org.gnome.adw.Avatar
import org.gnome.adw.Banner
import org.gnome.adw.HeaderBar
import org.gnome.adw.NavigationPage
import org.gnome.adw.NavigationSplitView
import org.gnome.adw.ToolbarView
import org.gnome.adw.WindowTitle
import org.gnome.gdk.Texture
import org.gnome.gio.Menu
import org.gnome.gio.SimpleAction
import org.gnome.gtk.Align
import org.gnome.gtk.Button
import org.gnome.gtk.Label
import org.gnome.gtk.ListBox
import org.gnome.gtk.ListBoxRow
import org.gnome.gtk.MenuButton
import org.gnome.gtk.PolicyType
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.GestureClick
import org.gnome.gtk.GestureLongPress
import org.gnome.gtk.Popover
import org.gnome.gtk.PositionType
import org.gnome.glib.GLib
import org.gnome.gtk.SelectionMode
import org.gnome.gtk.Widget
import org.gnome.pango.EllipsizeMode

/**
 * The chats: a sidebar with requests, group invites, groups and contacts, and the open chat beside
 * it (or in place of it, when the window is narrow).
 */
@OptIn(FlowPreview::class)
class MainPage(private val container: DesktopContainer, private val app: NiixApp) {

    val widget: Widget
    private val split = NavigationSplitView()
    private val list = ListBox()
    private val title = WindowTitle(tr("app_name"), "")
    private val connectionBanner = Banner("")
    private val callArea = vbox(0)
    private val contentPage = NavigationPage(vbox(0), tr("app_name"))
    private var chat: ChatPage? = null
    private val jobs = mutableListOf<Job>()
    private val actionNames = listOf("new-chat", "my-code", "settings")
    /** Profile pictures, kept a minute so a reload doesn't read them all again, then refreshed. */
    private val avatarCache = HashMap<String, Pair<Long, Texture?>>()
    private var callBanner: CallBanner? = null
    private var rowOptions: Popover? = null
    private var disposed = false

    init {
        val sidebarView = ToolbarView()
        val header = HeaderBar()
        header.titleWidget = title
        val newChat = Button.fromIconName("list-add-symbolic")
        newChat.tooltipText = tr("new_message_title")
        newChat.actionName = "app.new-chat"
        header.packStart(newChat)
        val menu = Menu()
        menu.append(tr("menu_my_code"), "app.my-code")
        menu.append(tr("action_settings"), "app.settings")
        menu.append(tr("desktop_lock_now"), "app.lock")
        menu.append(tr("desktop_quit"), "app.quit")
        val menuButton = MenuButton()
        menuButton.iconName = "open-menu-symbolic"
        menuButton.menuModel = menu
        menuButton.tooltipText = tr("menu_more")
        header.packEnd(menuButton)
        sidebarView.addTopBar(header)
        connectionBanner.revealed = false
        sidebarView.addTopBar(connectionBanner)
        sidebarView.addTopBar(callArea)

        list.selectionMode = SelectionMode.SINGLE
        list.addCssClass("navigation-sidebar")
        list.onRowActivated { row -> (row?.name?.takeIf { it.startsWith(ROW_PREFIX) })?.let { openConversation(it.removePrefix(ROW_PREFIX)) } }
        // Right-click (or a long press on a touchscreen) on a chat: what its own menu offers. Held
        // by the list rather than a row, since rows are replaced whenever the list reloads.
        val rightClick = GestureClick()
        rightClick.button = 3
        rightClick.onPressed { _, x, y -> showRowOptions(x, y) }
        list.addController(rightClick)
        val longPress = GestureLongPress()
        longPress.touchOnly = true
        longPress.onPressed { x, y -> showRowOptions(x, y) }
        list.addController(longPress)
        val scroller = ScrolledWindow()
        scroller.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
        scroller.child = list
        scroller.vexpand = true
        sidebarView.content = scroller

        split.sidebar = NavigationPage(sidebarView, tr("app_name"))
        split.minSidebarWidth = 280.0
        split.maxSidebarWidth = 380.0
        showPlaceholder()
        split.content = contentPage
        widget = split

        registerActions()
        jobs += uiScope.launch {
            reload()
            container.conversations.changes.debounce(CHANGE_DEBOUNCE_MILLIS).collect {
                reload()
                chat?.reload()
            }
        }
        jobs += uiScope.launch {
            while (isActive) {
                updateConnection()
                delay(CONNECTION_POLL_MILLIS)
            }
        }
        showCallState(container.conversations.calls.state.value)
    }

    private fun registerActions() {
        val actions = mapOf<String, () -> Unit>(
            "new-chat" to { NewChatDialog(container) { id -> openConversation(id) }.present(widget) },
            "my-code" to { MyCodeDialog(container).present(widget) },
            "settings" to { SettingsDialog(container, app).present(widget) },
        )
        actions.forEach { (name, handler) ->
            app.app.removeAction(name)
            val action = SimpleAction(name, null)
            action.onActivate { if (!disposed) handler() }
            app.app.addAction(action)
        }
        app.app.setAccelsForAction("app.new-chat", arrayOf("<Control>n"))
        app.app.setAccelsForAction("app.settings", arrayOf("<Control>comma"))
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        jobs.forEach { it.cancel() }
        chat?.dispose()
        chat = null
        callBanner?.dispose()
        closeRowOptions()
        actionNames.forEach { app.app.removeAction(it) }
    }

    fun isShowing(conversationId: String): Boolean = chat?.conversationId == conversationId && split.showContent || (chat?.conversationId == conversationId && !split.collapsed)

    fun openConversation(conversationId: String) {
        if (disposed) return
        if (chat?.conversationId != conversationId) {
            chat?.dispose()
            val page = ChatPage(container, conversationId, app, onGone = {
                chat?.dispose()
                chat = null
                showPlaceholder()
                uiScope.launch { reload() }
            })
            chat = page
            contentPage.child = page.widget
            contentPage.title = tr("app_name")
            page.onTitle = { contentPage.title = it }
        }
        split.showContent = true
        selectRow(conversationId)
    }

    private fun closeRowOptions() {
        rowOptions?.let { popover ->
            rowOptions = null
            runCatching { popover.popdown() }
            runCatching { popover.unparent() }
        }
    }

    /** The options for the chat under ([x], [y]) in the list, if there is one there. */
    private fun showRowOptions(x: Double, y: Double) {
        if (disposed) return
        val row = list.getRowAtY(y.toInt()) ?: return
        // Requests have their own buttons; headers and empty notes have nothing to offer.
        if (row.hasCssClass("request-row")) return
        val id = row.name?.takeIf { it.startsWith(ROW_PREFIX) }?.removePrefix(ROW_PREFIX) ?: return
        val ops = ConversationActions(
            container,
            id,
            parent = { widget },
            onChanged = {
                uiScope.launch { reload() }
                chat?.takeIf { it.conversationId == id }?.reload()
            },
            onGone = {
                if (chat?.conversationId == id) {
                    chat?.dispose()
                    chat = null
                    showPlaceholder()
                }
                uiScope.launch { reload() }
            },
        )
        uiScope.launch {
            val state = ops.load() ?: return@launch
            // The list may have gone while that was read; a popover needs a parent on screen.
            if (disposed || list.root == null || !list.mapped) return@launch
            closeRowOptions()
            val popover = Popover()
            val column = vbox(0)
            ops.items(state).forEach { item ->
                val button = Button.withLabel(item.label)
                button.addCssClass("flat")
                if (item.destructive) button.addCssClass("destructive-action")
                (button.child as? Label)?.xalign = 0f
                button.onClicked {
                    popover.popdown()
                    item.run()
                }
                column.append(button)
            }
            popover.child = column
            popover.setParent(list)
            popover.position = PositionType.BOTTOM
            popover.setPointingTo(org.gnome.gdk.Rectangle(x.toInt(), y.toInt(), 1, 1))
            popover.onClosed {
                GLib.idleAddOnce {
                    if (rowOptions === popover) rowOptions = null
                    if (popover.parent != null) popover.unparent()
                }
            }
            rowOptions = popover
            popover.popup()
        }
    }

    private fun showPlaceholder() {
        contentPage.child = statusPage("app.niix.NiiX-symbolic", tr("app_name"), tr("desktop_pick_chat"))
        contentPage.title = tr("app_name")
    }

    fun showCallState(state: CallState) {
        if (disposed) return
        val banner = callBanner ?: CallBanner(container).also {
            callBanner = it
            callArea.append(it.widget)
        }
        banner.update(state)
    }

    private suspend fun updateConnection() {
        val state = container.transport.state.value
        val progress = container.transport.bootstrapProgress.value
        val (text, show) = when {
            !container.network.isAvailable() -> tr("desktop_offline") to true
            state == TransportState.RUNNING && container.selfOnion != null -> "" to false
            state == TransportState.ERROR -> tr("desktop_connection_failed") to true
            else -> tr("notif_connecting", progress) to true
        }
        title.subtitle = if (show) "" else tr("desktop_connected")
        connectionBanner.title = text
        connectionBanner.revealed = show
    }

    private data class Row(val id: String, val title: String, val preview: String, val time: Long, val hidden: Boolean, val avatarKey: String?)

    private suspend fun reload() {
        if (disposed) return
        val data = withContext(Dispatchers.IO) {
            runCatching {
                val blur = container.storage.settings.getBool(SettingsStore.KEY_BLUR_MESSAGES, true)
                val all = container.conversations.listConversations().filterNot { it.pending }.map { c ->
                    val last = container.conversations.lastMessage(c.id)
                    c.type to Row(
                        id = c.id,
                        title = c.title,
                        preview = last?.let(::previewOf) ?: tr("no_messages"),
                        time = last?.createdAtEpochMillis ?: c.createdAtEpochMillis,
                        hidden = blur && last != null && last.direction == MessageDirection.INCOMING && !last.deleted && !last.revealed,
                        avatarKey = c.id,
                    )
                }
                val groups = all.filter { it.first == ConversationType.GROUP }.map { it.second }.sortedByDescending { it.time }
                val direct = all.filter { it.first == ConversationType.DIRECT }.map { it.second }.sortedByDescending { it.time }
                val requests = container.conversations.pendingRequests().map { req ->
                    val last = container.conversations.lastMessage(req.id)
                    Row(req.id, req.title, tr("request_unverified_prefix", last?.let(::previewOf) ?: tr("no_messages")), last?.createdAtEpochMillis ?: req.createdAtEpochMillis, false, null)
                }.sortedByDescending { it.time }
                val invites = container.conversations.pendingGroupInvites()
                Quad(groups, direct, requests, invites)
            }.getOrNull()
        } ?: return
        if (disposed) return
        render(data)
    }

    private data class Quad(val groups: List<Row>, val direct: List<Row>, val requests: List<Row>, val invites: List<PendingGroupInvite>)

    private fun render(data: Quad) {
        val selected = chat?.conversationId
        list.removeAll()
        if (data.requests.isNotEmpty() || data.invites.isNotEmpty()) {
            list.append(sectionHeader(tr("section_requests")))
            data.requests.forEach { row ->
                list.append(requestRow(row.id, row.title, row.preview, openable = true,
                    onAccept = { container.conversations.acceptRequest(row.id) },
                    onReject = { container.conversations.blockRequest(row.id) },
                    rejectLabel = tr("request_block")))
            }
            data.invites.forEach { invite ->
                list.append(requestRow(invite.conversationId, invite.title, tr("group_invite_preview", invite.members.size, invite.inviterOnion.take(10)), openable = false,
                    onAccept = { container.conversations.acceptGroupInvite(invite.conversationId) },
                    onReject = { container.conversations.rejectGroupInvite(invite.conversationId) },
                    rejectLabel = tr("request_block")))
            }
        }
        list.append(sectionHeader(tr("section_groups")))
        if (data.groups.isEmpty()) list.append(emptyRow(tr("groups_empty")))
        data.groups.forEach { list.append(conversationRow(it, group = true)) }
        list.append(sectionHeader(tr("section_contacts")))
        if (data.direct.isEmpty()) list.append(emptyRow(tr("contacts_empty")))
        data.direct.forEach { list.append(conversationRow(it, group = false)) }
        selected?.let(::selectRow)
    }

    private fun selectRow(conversationId: String) {
        var child = list.firstChild
        while (child != null) {
            if (child is ListBoxRow && child.name == ROW_PREFIX + conversationId) {
                list.selectRow(child)
                return
            }
            child = child.nextSibling
        }
    }

    private fun sectionHeader(text: String): ListBoxRow {
        val row = ListBoxRow()
        row.activatable = false
        row.selectable = false
        val label = Label(text)
        label.xalign = 0f
        label.addCssClass("heading")
        label.addCssClass("dim-label")
        label.margins(10, 8)
        row.child = label
        return row
    }

    private fun emptyRow(text: String): ListBoxRow {
        val row = ListBoxRow()
        row.activatable = false
        row.selectable = false
        val label = wrappingLabel(text, "dim-label", "caption")
        label.margins(6, 12)
        row.child = label
        return row
    }

    private fun conversationRow(row: Row, group: Boolean): ListBoxRow {
        val action = ActionRow()
        action.useMarkup = false
        action.title = row.title
        action.subtitle = if (row.hidden) tr("message_tap_to_reveal") else row.preview.replace('\n', ' ')
        action.subtitleLines = 1
        action.titleLines = 1
        action.activatable = true
        val avatar = Avatar(36, row.title, true)
        if (group) avatar.addCssClass("group-avatar")
        action.addPrefix(avatar)
        row.avatarKey?.let { key -> loadAvatar(key, avatar) }
        action.name = ROW_PREFIX + row.id
        return action
    }

    private fun loadAvatar(key: String, avatar: Avatar) {
        val now = System.nanoTime() / 1_000_000
        avatarCache[key]?.let { (loadedAt, texture) ->
            texture?.let(avatar::setCustomImage)
            if (now - loadedAt < AVATAR_CACHE_MILLIS) return
        }
        uiScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { container.conversations.profileBytes(key) }.getOrNull() }
            val texture = bytes?.let { runCatching { Texture.fromBytes(it) }.getOrNull() }
            avatarCache[key] = now to texture
            avatar.setCustomImage(texture)
        }
    }

    private fun requestRow(
        id: String,
        titleText: String,
        preview: String,
        openable: Boolean,
        onAccept: suspend () -> Boolean,
        onReject: suspend () -> Boolean,
        rejectLabel: String,
    ): ListBoxRow {
        val action = ActionRow()
        action.useMarkup = false
        action.title = titleText
        action.subtitle = preview.replace('\n', ' ')
        action.subtitleLines = 2
        action.addCssClass("request-row")
        val accept = Button.withLabel(tr("request_accept"))
        accept.addCssClass("suggested-action")
        accept.valign = Align.CENTER
        val reject = Button.withLabel(rejectLabel)
        reject.addCssClass("destructive-action")
        reject.valign = Align.CENTER
        fun respond(work: suspend () -> Boolean) {
            accept.sensitive = false
            reject.sensitive = false
            uiScope.launch {
                withContext(Dispatchers.IO) { runCatching { work() } }
                reload()
            }
        }
        accept.onClicked { respond(onAccept) }
        reject.onClicked { respond(onReject) }
        action.addSuffix(accept)
        action.addSuffix(reject)
        if (openable) {
            action.activatable = true
            action.name = ROW_PREFIX + id
        } else {
            action.activatable = false
            action.selectable = false
        }
        return action
    }

    companion object {
        private const val ROW_PREFIX = "conversation:"
        private const val CHANGE_DEBOUNCE_MILLIS = 250L
        private const val CONNECTION_POLL_MILLIS = 1_000L
        private const val AVATAR_CACHE_MILLIS = 60_000L

        fun previewOf(m: Message): String = when {
            m.deleted -> tr("message_deleted")
            m.type == MessageType.ATTACHMENT -> tr("attachment_label")
            else -> m.body
        }
    }
}
