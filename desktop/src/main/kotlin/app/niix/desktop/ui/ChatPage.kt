package app.niix.desktop.ui

import app.niix.core.messaging.CallAvailability
import app.niix.core.model.Attachment
import app.niix.core.model.AttachmentState
import app.niix.core.model.Conversation
import app.niix.core.model.ConversationType
import app.niix.core.model.DeliveryState
import app.niix.core.model.Message
import app.niix.core.model.MessageDirection
import app.niix.core.model.MessageType
import app.niix.core.storage.SettingsStore
import app.niix.desktop.DesktopContainer
import app.niix.desktop.Strings
import app.niix.desktop.tr
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gnome.adw.HeaderBar
import org.gnome.adw.ToolbarView
import org.gnome.adw.WindowTitle
import org.gnome.gdk.ModifierType
import org.gnome.gdk.Texture
import org.gnome.gio.Menu
import org.gnome.gio.SimpleAction
import org.gnome.gio.SimpleActionGroup
import org.gnome.glib.GLib
import org.gnome.gtk.Align
import org.gnome.gtk.Button
import org.gnome.gtk.ContentFit
import org.gnome.gtk.EventControllerKey
import org.gnome.gtk.FileDialog
import org.gnome.gtk.FileLauncher
import org.gnome.gtk.GestureClick
import org.gnome.gtk.GestureLongPress
import org.gnome.gtk.Justification
import org.gnome.gtk.Label
import org.gnome.gtk.ListBox
import org.gnome.gtk.ListBoxRow
import org.gnome.gtk.MediaControls
import org.gnome.gtk.MediaFile
import org.gnome.gtk.MenuButton
import org.gnome.gtk.Picture
import org.gnome.gtk.PolicyType
import org.gnome.gtk.Popover
import org.gnome.gtk.PositionType
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.SelectionMode
import org.gnome.gtk.TextIter
import org.gnome.gtk.TextView
import org.gnome.gtk.Widget
import org.gnome.gtk.WrapMode

/**
 * One conversation: its messages, and the composer for text, files and voice messages. Incoming
 * messages stay hidden until clicked while "Hide messages until tapped" is on (the default), as on
 * the phone, and a message's actions are on right-click or a long press.
 */
class ChatPage(
    private val container: DesktopContainer,
    val conversationId: String,
    private val app: NiixApp,
    private val onGone: () -> Unit,
) {

    val widget: Widget
    var onTitle: ((String) -> Unit)? = null

    private val windowTitle = WindowTitle("", "")
    private val list = ListBox()
    private val scroller = ScrolledWindow()
    private val requestBar = hbox(8)
    private val replyBar = hbox(8)
    private val replyLabel = Label("")
    private val input = TextView()
    private val sendButton = Button.fromIconName("go-up-symbolic")
    private val voiceButton = Button.fromIconName("audio-input-microphone-symbolic")
    private val attachButton = Button.fromIconName("mail-attachment-symbolic")
    private val recordingLabel = Label("")
    private val actions = SimpleActionGroup()
    private val ops = ConversationActions(container, conversationId, parent = { widget }, onChanged = { reload() }, onGone = { onGone() })
    private val menu = Menu()
    private var conversation: Conversation? = null
    private var replyTo: Message? = null
    private var disposed = false
    private var firstLoad = true
    private var blurEnabled = true
    private var blocked = false
    private var muted = false
    private var callsAllowedByMe = false
    private val revealedHere = HashSet<String>()
    private var loadJob: Job? = null
    private val recorder = VoiceRecorder(container.tempFiles)
    private var recordingJob: Job? = null
    private val players = mutableListOf<MediaFile>()

    init {
        val view = ToolbarView()
        val header = HeaderBar()
        header.titleWidget = windowTitle
        val menuButton = MenuButton()
        menuButton.iconName = "view-more-symbolic"
        menuButton.menuModel = menu
        menuButton.tooltipText = tr("menu_more")
        header.packEnd(menuButton)
        view.addTopBar(header)

        requestBar.visible = false
        requestBar.margins(6, 12)
        view.addTopBar(requestBar)

        list.selectionMode = SelectionMode.NONE
        list.addCssClass("background")
        list.margins(8, 12)
        scroller.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
        scroller.child = list
        scroller.vexpand = true
        view.content = scroller
        view.addBottomBar(composer())

        widget = view
        widget.insertActionGroup("chat", actions)
        buildActions()
        container.conversations.keepConversationWarm(conversationId, true)
        reload()
        uiScope.launch {
            withContext(Dispatchers.IO) { runCatching { container.conversations.markConversationRead(conversationId) } }
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        loadJob?.cancel()
        recordingJob?.cancel()
        recorder.cancel()
        players.forEach { runCatching { it.playing = false } }
        players.clear()
        container.conversations.keepConversationWarm(conversationId, false)
    }

    private fun composer(): Widget {
        val column = vbox(4)
        column.addCssClass("composer")

        replyBar.visible = false
        replyLabel.addCssClass("bubble-quote")
        replyLabel.ellipsize = org.gnome.pango.EllipsizeMode.END
        replyLabel.hexpand = true
        replyLabel.xalign = 0f
        val cancelReply = Button.fromIconName("window-close-symbolic")
        cancelReply.addCssClass("flat")
        cancelReply.onClicked { setReply(null) }
        replyBar.append(replyLabel)
        replyBar.append(cancelReply)
        column.append(replyBar)

        val row = hbox(6)
        attachButton.addCssClass("flat")
        attachButton.tooltipText = tr("attach")
        attachButton.valign = Align.END
        attachButton.onClicked { chooseFile() }
        voiceButton.addCssClass("flat")
        voiceButton.tooltipText = tr("attach_audio")
        voiceButton.valign = Align.END
        voiceButton.onClicked { toggleRecording() }

        input.wrapMode = WrapMode.WORD_CHAR
        input.acceptsTab = false
        input.topMargin = 6
        input.bottomMargin = 6
        input.leftMargin = 4
        input.rightMargin = 4
        val keys = EventControllerKey()
        keys.onKeyPressed { keyval, _, state ->
            val enter = keyval == org.gnome.gdk.Gdk.KEY_Return || keyval == org.gnome.gdk.Gdk.KEY_KP_Enter
            if (enter && !state.contains(ModifierType.SHIFT_MASK)) {
                send()
                true
            } else if (keyval == org.gnome.gdk.Gdk.KEY_Escape && replyTo != null) {
                setReply(null)
                true
            } else {
                false
            }
        }
        input.addController(keys)
        val inputScroll = ScrolledWindow()
        inputScroll.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
        inputScroll.child = input
        inputScroll.propagateNaturalHeight = true
        inputScroll.maxContentHeight = 160
        inputScroll.hexpand = true
        val frame = hbox(0)
        frame.addCssClass("composer-frame")
        frame.hexpand = true
        frame.append(inputScroll)
        recordingLabel.visible = false
        recordingLabel.hexpand = true
        recordingLabel.xalign = 0f
        recordingLabel.addCssClass("danger")
        frame.append(recordingLabel)

        sendButton.addCssClass("suggested-action")
        sendButton.addCssClass("circular")
        sendButton.tooltipText = tr("chat_send")
        sendButton.valign = Align.END
        sendButton.onClicked { send() }

        row.append(attachButton)
        row.append(frame)
        row.append(voiceButton)
        row.append(sendButton)
        column.append(row)
        return column
    }

    private fun buildActions() {
        fun action(name: String, handler: () -> Unit) {
            val a = SimpleAction(name, null)
            a.onActivate { if (!disposed) handler() }
            actions.addAction(a)
        }
        // Each acts on the chat as it is when chosen, not as it was when the menu was built.
        fun withState(handler: (ConversationActions.State) -> Unit) {
            uiScope.launch {
                val state = ops.load() ?: return@launch
                if (!disposed) handler(state)
            }
        }
        action("info") { withState(ops::openInfo) }
        action("rename") { withState(ops::rename) }
        action("safety") { ops.safetyNumber() }
        action("timer") { ops.chooseTimer() }
        action("mute") { withState(ops::toggleMute) }
        action("call") { withState(ops::placeCall) }
        action("calls-allowed") { withState(ops::toggleCallsAllowed) }
        action("block") { withState(ops::toggleBlock) }
        action("delete") { withState(ops::delete) }
    }

    private fun rebuildMenu(group: Boolean) {
        menu.removeAll()
        if (!group && !blocked) {
            menu.append(tr("menu_voice_call"), "chat.call")
            menu.append(tr(if (callsAllowedByMe) "menu_calls_stop" else "menu_calls_allow"), "chat.calls-allowed")
        }
        menu.append(tr(if (group) "menu_group_info" else "contact_profile_title"), "chat.info")
        menu.append(tr("menu_rename"), "chat.rename")
        if (!group) menu.append(tr("action_safety_number"), "chat.safety")
        menu.append(tr("menu_disappearing"), "chat.timer")
        menu.append(tr(if (muted) "conversation_unmute" else "conversation_mute"), "chat.mute")
        if (!group) menu.append(tr(if (blocked) "menu_unblock" else "menu_block"), "chat.block")
        menu.append(tr("action_delete"), "chat.delete")
    }

    fun reload() {
        if (disposed) return
        loadJob?.cancel()
        loadJob = uiScope.launch {
            val data = withContext(Dispatchers.IO) { runCatching { loadData() }.getOrNull() }
            if (disposed) return@launch
            if (data == null) {
                onGone()
                return@launch
            }
            render(data)
        }
    }

    private class Data(
        val conversation: Conversation,
        val messages: List<Message>,
        val attachments: Map<String, Attachment?>,
        val names: Map<String, String>,
        val blur: Boolean,
        val blocked: Boolean,
        val muted: Boolean,
        val calls: CallAvailability,
    )

    private suspend fun loadData(): Data? {
        val c = container.conversations.conversation(conversationId) ?: return null
        val messages = container.conversations.messagesFor(conversationId)
        val attachments = messages.mapNotNull { it.attachmentId }.distinct().associateWith { id ->
            runCatching { container.conversations.attachment(id) }.getOrNull()
        }
        val names = if (c.type == ConversationType.GROUP) {
            messages.filter { it.direction == MessageDirection.INCOMING }.map { it.senderOnion }.distinct()
                .associateWith { onion -> runCatching { container.conversations.displayName(onion) }.getOrDefault(onion.take(10)) }
        } else {
            emptyMap()
        }
        val settings = container.storage.settings
        return Data(
            conversation = c,
            messages = messages,
            attachments = attachments,
            names = names,
            blur = settings.getBool(SettingsStore.KEY_BLUR_MESSAGES, true),
            blocked = c.type == ConversationType.DIRECT && runCatching { container.conversations.isBlocked(conversationId) }.getOrDefault(false),
            muted = settings.getBool(SettingsStore.mutedKey(conversationId), false),
            calls = if (c.type == ConversationType.DIRECT) container.conversations.callAvailability(conversationId) else CallAvailability.UNAVAILABLE,
        )
    }

    private var renderedSignature: String? = null

    /** Everything a row shows, so a change that alters nothing on screen doesn't rebuild the list. */
    private fun signatureOf(data: Data): String = buildString {
        append(data.conversation.title).append('|').append(data.conversation.disappearSeconds).append('|')
        append(data.conversation.pending).append('|').append(data.blur).append('|').append(data.blocked).append('|').append(data.muted).append('|').append(data.calls)
        data.messages.forEach { m ->
            append('\n').append(m.id).append(m.deliveryState).append(m.deleted).append(m.revealed)
            append(m.expiresAtEpochMillis).append(m.id in revealedHere)
            m.attachmentId?.let { append(data.attachments[it]?.state) }
        }
        data.names.forEach { (k, v) -> append(k).append(v) }
    }

    private fun render(data: Data) {
        val signature = signatureOf(data)
        if (signature == renderedSignature) return
        renderedSignature = signature
        // Rows are about to be rebuilt; a voice message playing in one of them stops with it.
        players.forEach { runCatching { it.playing = false } }
        players.clear()
        conversation = data.conversation
        blurEnabled = data.blur
        blocked = data.blocked
        muted = data.muted
        callsAllowedByMe = data.calls == CallAvailability.READY || data.calls == CallAvailability.WAITING_FOR_THEM
        val group = data.conversation.type == ConversationType.GROUP
        windowTitle.title = data.conversation.title
        windowTitle.subtitle = timerSubtitle(data.conversation.disappearSeconds)
        onTitle?.invoke(data.conversation.title)
        rebuildMenu(group)
        renderRequestBar(data.conversation)

        val adjustment = scroller.vadjustment
        val atBottom = firstLoad || adjustment.value + adjustment.pageSize >= adjustment.upper - 48
        val byId = data.messages.associateBy { it.id }
        list.removeAll()
        if (data.messages.isEmpty()) {
            val empty = wrappingLabel(tr("no_messages"), "dim-label")
            empty.halign = Align.CENTER
            empty.justify = Justification.CENTER
            empty.margins(24)
            list.append(empty)
        }
        var lastDay: LocalDate? = null
        for (m in data.messages) {
            val day = Instant.ofEpochMilli(m.createdAtEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
            if (day != lastDay) {
                lastDay = day
                list.append(dayDivider(day))
            }
            list.append(messageRow(m, byId, data, group))
        }
        if (atBottom) scrollToBottom()
        firstLoad = false
    }

    private fun renderRequestBar(c: Conversation) {
        var child = requestBar.firstChild
        while (child != null) {
            val next = child.nextSibling
            requestBar.remove(child)
            child = next
        }
        requestBar.visible = c.pending
        if (!c.pending) return
        val text = wrappingLabel(tr("desktop_request_banner"))
        text.hexpand = true
        val accept = Button.withLabel(tr("request_accept"))
        accept.addCssClass("suggested-action")
        accept.valign = Align.CENTER
        val block = Button.withLabel(tr("request_block"))
        block.addCssClass("destructive-action")
        block.valign = Align.CENTER
        accept.onClicked {
            uiScope.launch {
                withContext(Dispatchers.IO) { runCatching { container.conversations.acceptRequest(conversationId) } }
                reload()
            }
        }
        block.onClicked {
            uiScope.launch {
                withContext(Dispatchers.IO) { runCatching { container.conversations.blockRequest(conversationId) } }
                onGone()
            }
        }
        requestBar.append(text)
        requestBar.append(accept)
        requestBar.append(block)
    }

    private fun timerSubtitle(seconds: Long): String = when {
        seconds <= 0 -> ""
        seconds % 86_400 == 0L -> "⏱ " + Strings.plural("duration_days", (seconds / 86_400).toInt())
        seconds % 3_600 == 0L -> "⏱ " + Strings.plural("duration_hours", (seconds / 3_600).toInt())
        else -> "⏱ " + Strings.plural("duration_minutes", (seconds / 60).toInt().coerceAtLeast(1))
    }

    private fun scrollToBottom() {
        GLib.idleAddOnce {
            val adjustment = scroller.vadjustment
            adjustment.value = adjustment.upper - adjustment.pageSize
        }
        GLib.timeoutAddOnce(120) {
            val adjustment = scroller.vadjustment
            adjustment.value = adjustment.upper - adjustment.pageSize
        }
    }

    private fun dayDivider(day: LocalDate): ListBoxRow {
        val row = ListBoxRow()
        row.activatable = false
        val label = Label(day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
        label.addCssClass("dim-label")
        label.addCssClass("caption")
        label.margins(10, 0)
        row.child = label
        return row
    }

    private fun isHidden(m: Message): Boolean =
        blurEnabled && m.direction == MessageDirection.INCOMING && !m.deleted && !m.revealed && m.id !in revealedHere

    private fun messageRow(m: Message, byId: Map<String, Message>, data: Data, group: Boolean): ListBoxRow {
        val row = ListBoxRow()
        row.activatable = false
        if (m.type == MessageType.SYSTEM) {
            val label = wrappingLabel(m.body, "dim-label", "caption")
            label.halign = Align.CENTER
            label.justify = Justification.CENTER
            label.margins(4, 24)
            row.child = label
            return row
        }
        val outgoing = m.direction == MessageDirection.OUTGOING
        val bubble = vbox(4)
        bubble.addCssClass("bubble")
        bubble.addCssClass(if (outgoing) "outgoing" else "incoming")
        bubble.halign = if (outgoing) Align.END else Align.START
        bubble.marginStart = if (outgoing) 64 else 0
        bubble.marginEnd = if (outgoing) 0 else 64
        bubble.marginTop = 3
        bubble.marginBottom = 3

        if (group && !outgoing) {
            val sender = Label(data.names[m.senderOnion] ?: m.senderOnion.take(10))
            sender.addCssClass("bubble-sender")
            sender.xalign = 0f
            bubble.append(sender)
        }
        m.replyToId?.let { quotedId ->
            val quoted = byId[quotedId]
            val text = when {
                quoted == null -> tr("reply_original_unavailable")
                quoted.deleted -> tr("message_deleted")
                isHidden(quoted) -> tr("message_tap_to_reveal")
                quoted.type == MessageType.ATTACHMENT -> tr("attachment_label")
                else -> quoted.body
            }
            val quote = wrappingLabel(text, "bubble-quote")
            quote.lines = 3
            quote.ellipsize = org.gnome.pango.EllipsizeMode.END
            bubble.append(quote)
        }

        val hidden = isHidden(m)
        val attachment = if (!m.deleted && m.type == MessageType.ATTACHMENT) m.attachmentId?.let { data.attachments[it] } else null
        when {
            hidden -> {
                val reveal = Button.withLabel(tr("message_tap_to_reveal"))
                reveal.addCssClass("flat")
                reveal.onClicked { reveal(m) }
                bubble.append(reveal)
            }
            m.deleted -> bubble.append(wrappingLabel(tr("message_deleted"), "dim-label"))
            m.type == MessageType.ATTACHMENT -> bubble.append(attachmentWidget(m, attachment))
            else -> {
                val body = wrappingLabel(m.body)
                body.selectable = true
                body.canFocus = false
                bubble.append(body)
            }
        }

        val meta = Label(metaText(m))
        meta.addCssClass("bubble-meta")
        meta.xalign = if (outgoing) 1f else 0f
        bubble.append(meta)

        val press = GestureClick()
        press.button = 3
        press.onPressed { _, x, y -> showActions(m, attachment, bubble, x, y, hidden) }
        bubble.addController(press)
        val longPress = GestureLongPress()
        // Touchscreens only: with a mouse, right-click opens the actions, and a long press would
        // also fire after a click that rebuilt the list, on a row that's no longer on screen.
        longPress.touchOnly = true
        longPress.onPressed { x, y -> showActions(m, attachment, bubble, x, y, hidden) }
        bubble.addController(longPress)

        row.child = bubble
        return row
    }

    private fun metaText(m: Message): String {
        val time = Instant.ofEpochMilli(m.createdAtEpochMillis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        val timer = if (m.expiresAtEpochMillis != null || m.disappearSeconds != null) " ⏱" else ""
        if (m.direction == MessageDirection.INCOMING) return time + timer
        val state = when (m.deliveryState) {
            DeliveryState.PENDING -> " …"
            DeliveryState.SENT -> " ✓"
            DeliveryState.RELAYED -> " · " + tr("delivery_relayed")
            DeliveryState.DELIVERED -> " ✓✓"
            DeliveryState.FAILED -> " · " + tr("delivery_failed")
            DeliveryState.RECEIVED -> ""
        }
        return time + timer + state
    }

    private fun reveal(m: Message) {
        revealedHere.add(m.id)
        uiScope.launch {
            withContext(Dispatchers.IO) { runCatching { container.conversations.markMessageRead(m.id) } }
            reload()
        }
    }

    private fun attachmentWidget(m: Message, attachment: Attachment?): Widget {
        if (attachment == null || attachment.state == AttachmentState.FAILED) {
            return wrappingLabel("🚫 " + tr("attachment_unavailable"), "dim-label")
        }
        if (attachment.state == AttachmentState.PENDING && m.direction == MessageDirection.INCOMING) {
            return wrappingLabel(tr("attachment_label") + " …", "dim-label")
        }
        val mime = attachment.mimeType
        return when {
            mime.startsWith("image/") -> imageWidget(attachment)
            mime.startsWith("audio/") -> audioWidget(attachment)
            else -> {
                val kind = tr(if (mime.startsWith("video/")) "attachment_kind_video" else "attachment_kind_file")
                val button = Button.withLabel("📎 $kind • " + tr("attachment_tap_open"))
                button.addCssClass("flat")
                button.onClicked { openAttachment(attachment) }
                button
            }
        }
    }

    private fun imageWidget(attachment: Attachment): Widget {
        val picture = Picture()
        picture.contentFit = ContentFit.CONTAIN
        picture.canShrink = true
        picture.setSizeRequest(260, 200)
        picture.alternativeText = tr("attachment_label")
        val click = GestureClick()
        click.button = 1
        click.onReleased { _, _, _ -> openAttachment(attachment) }
        picture.addController(click)
        uiScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { container.conversations.attachmentBytes(attachment.id) }.getOrNull() }
            if (disposed || bytes == null) return@launch
            val texture = runCatching { Texture.fromBytes(bytes) }.getOrNull()
            bytes.fill(0)
            if (texture == null) return@launch
            picture.paintable = texture
            val scale = minOf(1.0, 320.0 / texture.width, 320.0 / texture.height)
            picture.setSizeRequest((texture.width * scale).toInt().coerceAtLeast(48), (texture.height * scale).toInt().coerceAtLeast(48))
        }
        return picture
    }

    private fun audioWidget(attachment: Attachment): Widget {
        val box = vbox(4)
        val play = Button.withLabel("▶ " + tr("attachment_kind_audio"))
        play.addCssClass("flat")
        play.onClicked {
            play.sensitive = false
            uiScope.launch {
                val file = decryptToTemp(attachment) ?: run {
                    play.sensitive = true
                    return@launch
                }
                val media = MediaFile.forFile(org.gnome.gio.File.forPath(file.absolutePath))
                players.add(media)
                val controls = MediaControls(media)
                controls.setSizeRequest(260, -1)
                box.remove(play)
                box.append(controls)
                media.playing = true
            }
        }
        box.append(play)
        return box
    }

    private suspend fun decryptToTemp(attachment: Attachment): File? {
        val file = container.tempFiles.create("open_", extensionFor(attachment.mimeType))
        val ok = withContext(Dispatchers.IO) { runCatching { container.conversations.decryptAttachmentTo(attachment.id, file) }.getOrDefault(false) }
        if (!ok) {
            file.delete()
            Toasts.show(tr("attachment_download_failed"))
            return null
        }
        return file
    }

    private fun openAttachment(attachment: Attachment) {
        uiScope.launch {
            val file = decryptToTemp(attachment) ?: return@launch
            val launcher = FileLauncher(org.gnome.gio.File.forPath(file.absolutePath))
            launcher.launch(app.window(), null) { result ->
                runCatching { launcher.launchFinish(result) }.onFailure { Toasts.show(tr("desktop_open_failed")) }
            }
        }
    }

    private fun saveAttachment(attachment: Attachment) {
        uiScope.launch {
            val ok = confirm(widget, tr("attachment_save_title"), tr("attachment_save_warning"), tr("attachment_save_confirm"))
            if (!ok) return@launch
            val dialog = FileDialog()
            dialog.title = tr("attachment_save_title")
            dialog.initialName = "niix-${attachment.id.take(8)}.${extensionFor(attachment.mimeType)}"
            dialog.save(app.window(), null) { result ->
                val target = runCatching { dialog.saveFinish(result) }.getOrNull()?.path?.toString() ?: return@save
                uiScope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        runCatching { container.conversations.decryptAttachmentTo(attachment.id, File(target)) }.getOrDefault(false)
                    }
                    Toasts.show(tr(if (saved) "attachment_saved" else "attachment_save_failed"))
                }
            }
        }
    }

    private fun showActions(m: Message, attachment: Attachment?, anchor: Widget, x: Double, y: Double, hidden: Boolean) {
        // A row rebuilt since the press is no longer in the window, and a popover needs one.
        if (disposed || anchor.root == null || !anchor.mapped) return
        val popover = Popover()
        val column = vbox(0)
        fun item(label: String, destructive: Boolean = false, handler: () -> Unit) {
            val button = Button.withLabel(label)
            button.addCssClass("flat")
            if (destructive) button.addCssClass("destructive-action")
            (button.child as? Label)?.xalign = 0f
            button.onClicked {
                popover.popdown()
                handler()
            }
            column.append(button)
        }
        if (!m.deleted && !hidden) {
            item(tr("action_reply")) { setReply(m) }
            if (m.type == MessageType.TEXT) item(tr("action_copy")) { copy(m.body) }
            if (attachment != null && attachment.state == AttachmentState.COMPLETE) {
                item(tr("desktop_open")) { openAttachment(attachment) }
                item(tr("desktop_save")) { saveAttachment(attachment) }
            }
        }
        if (hidden) item(tr("message_tap_to_reveal")) { reveal(m) }
        if (m.direction == MessageDirection.OUTGOING && m.deliveryState == DeliveryState.FAILED && !m.deleted) {
            item(tr("action_send_again")) {
                uiScope.launch {
                    withContext(Dispatchers.IO) { runCatching { container.conversations.sendAgain(m.id) } }
                    reload()
                }
            }
        }
        item(tr("delete_for_me"), destructive = true) { delete(m, everyone = false) }
        if (m.direction == MessageDirection.OUTGOING && m.remoteDeletable && !m.deleted) {
            item(tr("delete_for_everyone"), destructive = true) { delete(m, everyone = true) }
        }
        popover.child = column
        popover.setParent(anchor)
        popover.position = PositionType.BOTTOM
        popover.setPointingTo(org.gnome.gdk.Rectangle(x.toInt(), y.toInt(), 1, 1))
        popover.onClosed { GLib.idleAddOnce { popover.unparent() } }
        popover.popup()
    }

    private fun copy(text: String) {
        widget.clipboard.setText(text)
        Toasts.show(tr("message_copied"))
    }

    private fun delete(m: Message, everyone: Boolean) {
        uiScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (everyone) container.conversations.deleteForEveryone(conversationId, m.id)
                    else container.conversations.deleteForMe(m.id)
                }
            }
            Toasts.show(tr("toast_deleted"))
            reload()
        }
    }

    private fun setReply(m: Message?) {
        replyTo = m
        replyBar.visible = m != null
        if (m != null) {
            replyLabel.label = tr("reply_banner") + "  " + (if (m.type == MessageType.ATTACHMENT) tr("attachment_label") else m.body.replace('\n', ' '))
            input.grabFocus()
        }
    }

    private fun currentText(): String {
        val buffer = input.buffer
        val start = TextIter()
        val end = TextIter()
        buffer.getBounds(start, end)
        return buffer.getText(start, end, false)
    }

    private fun send() {
        if (recorder.isRecording) {
            stopRecording(send = true)
            return
        }
        val text = currentText().trim()
        if (text.isEmpty()) return
        val reply = replyTo?.id
        input.buffer.setText("", 0)
        setReply(null)
        uiScope.launch {
            val sent = withContext(Dispatchers.IO) { runCatching { container.conversations.sendText(conversationId, text, reply) } }
            if (sent.isFailure) Toasts.show(tr("toast_failed", sent.exceptionOrNull()?.message ?: ""))
            firstLoad = true
            reload()
        }
    }

    private fun chooseFile() {
        val dialog = FileDialog()
        dialog.title = tr("attach_choose")
        dialog.open(app.window(), null) { result ->
            val path = runCatching { dialog.openFinish(result) }.getOrNull()?.path?.toString() ?: return@open
            sendFile(File(path), deleteAfter = false)
        }
    }

    private fun sendFile(file: File, deleteAfter: Boolean, mimeOverride: String? = null) {
        if (!file.isFile || file.length() <= 0) return
        if (file.length() > MAX_ATTACHMENT_BYTES) {
            Toasts.show(tr("desktop_file_too_large"))
            if (deleteAfter) file.delete()
            return
        }
        val mime = mimeOverride ?: mimeTypeOf(file)
        uiScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    runCatching { container.conversations.sendAttachment(conversationId, file, mime) }
                } finally {
                    if (deleteAfter) file.delete()
                }
            }
            if (result.isFailure) Toasts.show(tr("toast_failed", result.exceptionOrNull()?.message ?: ""))
            firstLoad = true
            reload()
        }
    }

    private fun toggleRecording() {
        if (recorder.isRecording) {
            stopRecording(send = false)
            return
        }
        val started = recorder.start()
        if (!started) {
            Toasts.show(tr("desktop_recording_failed"))
            return
        }
        voiceButton.iconName = "edit-delete-symbolic"
        voiceButton.tooltipText = tr("audio_cancel")
        sendButton.tooltipText = tr("audio_stop_send")
        input.visible = false
        recordingLabel.visible = true
        val startedAt = System.nanoTime()
        recordingJob = uiScope.launch {
            while (recorder.isRecording) {
                val seconds = (System.nanoTime() - startedAt) / 1_000_000_000
                recordingLabel.label = "● " + tr("audio_recording") + "  %d:%02d".format(seconds / 60, seconds % 60)
                if (seconds >= MAX_RECORDING_SECONDS) {
                    stopRecording(send = true)
                    break
                }
                delay(500)
            }
        }
    }

    private fun stopRecording(send: Boolean) {
        recordingJob?.cancel()
        voiceButton.iconName = "audio-input-microphone-symbolic"
        voiceButton.tooltipText = tr("attach_audio")
        sendButton.tooltipText = tr("chat_send")
        input.visible = true
        recordingLabel.visible = false
        uiScope.launch {
            val recording = withContext(Dispatchers.IO) { recorder.stop() }
            if (recording == null) {
                if (send) Toasts.show(tr("desktop_recording_failed"))
                return@launch
            }
            if (send) sendFile(recording.file, deleteAfter = true, mimeOverride = recording.mimeType) else recording.file.delete()
        }
    }

    companion object {
        private const val MAX_ATTACHMENT_BYTES = 100L * 1024 * 1024
        private const val MAX_RECORDING_SECONDS = 15L * 60

        private val EXTENSIONS = mapOf(
            "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp",
            "image/heic" to "heic", "video/mp4" to "mp4", "video/webm" to "webm", "video/3gpp" to "3gp",
            "audio/mp4" to "m4a", "audio/aac" to "aac", "audio/ogg" to "ogg", "audio/mpeg" to "mp3",
            "audio/opus" to "opus", "application/pdf" to "pdf", "text/plain" to "txt", "application/zip" to "zip",
        )

        fun extensionFor(mime: String): String = EXTENSIONS[mime.lowercase().substringBefore(';').trim()] ?: "bin"

        private val BY_EXTENSION = mapOf(
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "jpe" to "image/jpeg", "png" to "image/png",
            "gif" to "image/gif", "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif",
            "bmp" to "image/bmp", "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime",
            "webm" to "video/webm", "mkv" to "video/x-matroska", "3gp" to "video/3gpp",
            "m4a" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg", "oga" to "audio/ogg",
            "opus" to "audio/opus", "mp3" to "audio/mpeg", "wav" to "audio/wav", "flac" to "audio/flac",
            "pdf" to "application/pdf", "txt" to "text/plain", "zip" to "application/zip",
        )
        private val MIME = Regex("^[a-z0-9][a-z0-9.+-]{0,40}/[a-z0-9][a-z0-9.+-]{0,80}$")

        /**
         * The type a file is sent as, which decides how the other side shows it: a photo as a
         * photo, not as a nameless file. By extension first, since that's what the phone goes by
         * too; then by what the desktop's own type database makes of the name and first bytes.
         * (The Java runtime's own guess isn't used: inside the Flatpak it has nothing to go on.)
         */
        fun mimeTypeOf(file: File): String {
            BY_EXTENSION[file.extension.lowercase()]?.let { return it }
            val guessed = runCatching {
                val head = file.inputStream().use { it.readNBytes(4096) }
                org.gnome.gio.Gio.contentTypeGetMimeType(org.gnome.gio.Gio.contentTypeGuess(file.name, head, null))
            }.getOrNull()?.lowercase()
            return guessed?.takeIf { MIME.matches(it) } ?: "application/octet-stream"
        }
    }
}
