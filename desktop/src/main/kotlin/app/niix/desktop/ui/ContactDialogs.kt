package app.niix.desktop.ui

import app.niix.core.messaging.ConversationManager.LinkResult
import app.niix.core.messaging.LinkInvite
import app.niix.core.model.ConversationType
import app.niix.core.model.DeviceList
import app.niix.core.model.GroupRole
import app.niix.core.model.OnionAddress
import app.niix.desktop.DesktopContainer
import app.niix.desktop.tr
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gnome.adw.ActionRow
import org.gnome.adw.Dialog
import org.gnome.adw.EntryRow
import org.gnome.adw.HeaderBar
import org.gnome.adw.PreferencesGroup
import org.gnome.adw.PreferencesPage
import org.gnome.adw.ToolbarView
import org.gnome.adw.ViewStack
import org.gnome.adw.ViewSwitcher
import org.gnome.adw.ViewSwitcherPolicy
import org.gnome.gtk.Align
import org.gnome.gtk.Button
import org.gnome.gtk.CheckButton
import org.gnome.gtk.ContentFit
import org.gnome.gtk.FileDialog
import org.gnome.gtk.Label
import org.gnome.gtk.Picture
import org.gnome.gtk.Widget

/** A dialog with a header bar and scrolling content, the shape most of these share. */
abstract class NiixDialog(title: String, width: Int = 480, height: Int = 600) {
    protected val dialog = Dialog()
    protected val header = HeaderBar()
    protected val view = ToolbarView()

    init {
        dialog.title = title
        dialog.contentWidth = width
        dialog.contentHeight = height
        view.addTopBar(header)
        dialog.child = view
    }

    open fun present(parent: Widget) = dialog.present(parent)

    protected fun close() {
        dialog.close()
    }
}

/**
 * Starting a conversation: adding a contact from their code (pasted, or read from a picture of
 * their QR code), or making a group from existing contacts. A device link code pasted here is
 * recognised and offered as linking instead.
 */
class NewChatDialog(private val container: DesktopContainer, private val onOpen: (String) -> Unit) :
    NiixDialog(tr("new_message_title"), 520, 640) {

    private val stack = ViewStack()
    private val nameRow = EntryRow()
    private val codeRow = EntryRow()
    private val groupName = EntryRow()
    private val memberChecks = mutableListOf<Pair<CheckButton, String>>()
    private var busy = false

    init {
        val switcher = ViewSwitcher()
        switcher.stack = stack
        switcher.policy = ViewSwitcherPolicy.WIDE
        header.titleWidget = switcher
        stack.addTitledWithIcon(codePage(), "code", tr("tab_enter_code"), "contact-new-symbolic")
        stack.addTitledWithIcon(groupPage(), "group", tr("create_group_title"), "system-users-symbolic")
        view.content = stack
        loadContacts()
    }

    private fun codePage(): Widget {
        val page = PreferencesPage()
        val group = PreferencesGroup()
        group.description = tr("add_contact_hint")
        nameRow.title = tr("hint_contact_name")
        codeRow.title = tr("hint_share_code")
        val paste = Button.fromIconName("edit-paste-symbolic")
        paste.tooltipText = tr("action_paste")
        paste.addCssClass("flat")
        paste.valign = Align.CENTER
        paste.onClicked { pasteCode() }
        val image = Button.fromIconName("image-x-generic-symbolic")
        image.tooltipText = tr("desktop_read_qr_image")
        image.addCssClass("flat")
        image.valign = Align.CENTER
        image.onClicked { readQrImage() }
        codeRow.addSuffix(paste)
        codeRow.addSuffix(image)
        codeRow.onEntryActivated { add() }
        group.add(nameRow)
        group.add(codeRow)
        page.add(group)

        val buttons = PreferencesGroup()
        val add = Button.withLabel(tr("action_add_contact"))
        add.addCssClass("suggested-action")
        add.addCssClass("pill")
        add.halign = Align.CENTER
        add.onClicked { add() }
        val mine = Button.withLabel(tr("action_show_my_code"))
        mine.addCssClass("pill")
        mine.halign = Align.CENTER
        mine.marginTop = 12
        mine.onClicked { MyCodeDialog(container).present(dialog) }
        val box = vbox(6)
        box.append(add)
        box.append(mine)
        buttons.add(box)
        page.add(buttons)
        return page
    }

    private fun groupPage(): Widget {
        val page = PreferencesPage()
        val nameGroup = PreferencesGroup()
        groupName.title = tr("hint_group_name")
        nameGroup.add(groupName)
        page.add(nameGroup)
        val members = PreferencesGroup()
        members.title = tr("create_group_pick")
        members.name = "members"
        page.add(members)
        val buttons = PreferencesGroup()
        val create = Button.withLabel(tr("action_create_group"))
        create.addCssClass("suggested-action")
        create.addCssClass("pill")
        create.halign = Align.CENTER
        create.onClicked { createGroup(create) }
        buttons.add(create)
        page.add(buttons)
        membersGroup = members
        return page
    }

    private var membersGroup: PreferencesGroup? = null

    private fun loadContacts() {
        uiScope.launch {
            val contacts = withContext(Dispatchers.IO) {
                runCatching {
                    container.conversations.listConversations()
                        .filter { it.type == ConversationType.DIRECT && !it.pending }
                        .sortedBy { it.title.lowercase() }
                }.getOrDefault(emptyList())
            }
            val group = membersGroup ?: return@launch
            if (contacts.isEmpty()) {
                group.add(wrappingLabel(tr("create_group_no_contacts"), "dim-label"))
                return@launch
            }
            contacts.forEach { c ->
                val row = ActionRow()
                row.useMarkup = false
                row.title = c.title
                val check = CheckButton()
                row.addPrefix(check)
                row.activatableWidget = check
                memberChecks += check to c.id
                group.add(row)
            }
        }
    }

    private fun pasteCode() {
        val clipboard = codeRow.clipboard
        clipboard.readTextAsync(null) { result ->
            val text = runCatching { clipboard.readTextFinish(result) }.getOrNull()?.trim()
            if (!text.isNullOrEmpty()) codeRow.text = text
        }
    }

    private fun readQrImage() {
        val chooser = FileDialog()
        chooser.title = tr("desktop_read_qr_image")
        chooser.open(dialog.root as? org.gnome.gtk.Window, null) { result ->
            val path = runCatching { chooser.openFinish(result) }.getOrNull()?.path?.toString() ?: return@open
            uiScope.launch {
                val text = withContext(Dispatchers.IO) { QrCodes.decode(File(path)) }
                if (text == null) Toasts.show(tr("desktop_no_qr_found")) else {
                    codeRow.text = text
                    Toasts.show(tr("scan_captured"))
                }
            }
        }
    }

    private fun add() {
        if (busy) return
        val code = codeRow.text.trim()
        val name = nameRow.text.trim()
        if (code.isEmpty()) {
            Toasts.show(tr("add_contact_need_code"))
            return
        }
        if (LinkInvite.parseOrNull(code) != null) {
            linkDevice(dialog, container, code) { close() }
            return
        }
        busy = true
        uiScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val parts = code.split("|", limit = 2)
                    require(parts.size == 2) { "Malformed share code" }
                    val onion = parts[0].trim()
                    require(OnionAddress.parseOrNull(onion) != null) { "Malformed share code" }
                    val identityKey = Base64.getDecoder().decode(parts[1].trim())
                    container.conversations.addContactByCode(onion, identityKey, name.ifEmpty { onion.take(8) })
                }
            }
            busy = false
            result.onSuccess { conversation ->
                Toasts.show(tr("toast_contact_added"))
                close()
                onOpen(conversation.id)
            }.onFailure {
                Toasts.show(tr("toast_failed", it.message ?: ""))
            }
        }
    }

    private fun createGroup(button: Button) {
        if (busy) return
        val name = groupName.text.trim()
        if (name.isEmpty()) {
            Toasts.show(tr("create_group_need_name"))
            return
        }
        val members = memberChecks.filter { it.first.active }.map { it.second }
        if (members.isEmpty()) {
            Toasts.show(tr("create_group_need_members"))
            return
        }
        busy = true
        button.sensitive = false
        button.label = tr("create_group_creating")
        uiScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { container.conversations.createGroup(name, members) } }
            busy = false
            result.onSuccess {
                Toasts.show(tr("create_group_done"))
                close()
                onOpen(it.id)
            }.onFailure {
                button.sensitive = true
                button.label = tr("action_create_group")
                Toasts.show(tr("toast_failed", it.message ?: ""))
            }
        }
    }
}

/**
 * Hands this account to another device, after asking: linking gives the other device the account's
 * key, so a code passed off as something harmless would give the account away.
 */
fun linkDevice(parent: Widget, container: DesktopContainer, code: String, onDone: () -> Unit = {}) {
    uiScope.launch {
        if (!confirm(parent, tr("devices_link_confirm_title"), tr("devices_link_confirm_body"), tr("devices_link_confirm"))) return@launch
        Toasts.show(tr("devices_linking"))
        val result = withContext(Dispatchers.IO) { runCatching { container.conversations.linkDevice(code) }.getOrNull() }
        val message = when (result) {
            LinkResult.LINKED -> tr("devices_linked")
            LinkResult.NOT_A_LINK_CODE -> tr("devices_not_link_code")
            LinkResult.TOO_MANY_DEVICES -> tr("devices_too_many", DeviceList.MAX_DEVICES)
            LinkResult.UNREACHABLE, null -> tr("devices_unreachable")
        }
        Toasts.show(message)
        onDone()
    }
}

/** The code a contact needs to add this account, as a QR code and as text to copy. */
class MyCodeDialog(private val container: DesktopContainer) : NiixDialog(tr("menu_my_code"), 440, 640) {

    private val picture = Picture()
    private val codeLabel = wrappingLabel(tr("share_code_pending"), "monospace", "caption")
    private val nameLabel = Label("")
    private val fingerprint = wrappingLabel("", "monospace", "caption", "dim-label")
    private var code: String? = null
    private var job: Job? = null

    init {
        val box = vbox(14)
        box.margins(18)
        nameLabel.addCssClass("title-2")
        box.append(nameLabel)
        picture.contentFit = ContentFit.CONTAIN
        picture.setSizeRequest(260, 260)
        picture.halign = Align.CENTER
        picture.addCssClass("qr-frame")
        picture.visible = false
        box.append(picture)
        codeLabel.selectable = true
        box.append(codeLabel)
        val copy = Button.withLabel(tr("action_copy_code"))
        copy.addCssClass("suggested-action")
        copy.addCssClass("pill")
        copy.halign = Align.CENTER
        copy.onClicked {
            val current = code
            if (current == null) {
                Toasts.show(tr("share_code_pending"))
            } else {
                copy.clipboard.setText(current)
                Toasts.show(tr("toast_copied"))
            }
        }
        box.append(copy)
        box.append(Label(tr("label_fingerprint")).apply { addCssClass("heading"); xalign = 0f })
        fingerprint.selectable = true
        box.append(fingerprint)
        val scroller = org.gnome.gtk.ScrolledWindow()
        scroller.child = box
        scroller.vexpand = true
        view.content = scroller
        dialog.onClosed { job?.cancel() }
        load()
    }

    private fun load() {
        job = uiScope.launch {
            while (isActive) {
                val data = withContext(Dispatchers.IO) {
                    runCatching {
                        val share = container.shareCode()
                        val local = container.crypto.localIdentity(container.accountOnion?.let { OnionAddress.parse(it) })
                        Triple(container.conversations.username(), local.fingerprint.displayable, share)
                    }.getOrNull()
                }
                if (data != null) {
                    nameLabel.label = data.first
                    fingerprint.label = data.second
                    val share = data.third
                    if (share != null) {
                        code = share
                        codeLabel.label = share
                        QrCodes.texture(share)?.let {
                            picture.paintable = it
                            picture.visible = true
                        }
                        return@launch
                    }
                }
                delay(1500)
            }
        }
    }
}

/** A group's members, and what an admin can do with them; leaving the group for everyone. */
class GroupInfoDialog(
    private val container: DesktopContainer,
    private val conversationId: String,
    private val onLeft: () -> Unit,
) : NiixDialog(tr("group_info_title"), 520, 640) {

    private val page = PreferencesPage()
    private var membersGroup = PreferencesGroup()
    private var actionsGroup = PreferencesGroup()

    init {
        page.add(membersGroup)
        page.add(actionsGroup)
        view.content = page
        reload()
    }

    private fun reload() {
        uiScope.launch {
            val data = withContext(Dispatchers.IO) {
                runCatching {
                    val members = container.conversations.groupMembers(conversationId)
                    val admin = container.conversations.amIGroupAdmin(conversationId)
                    val self = container.accountOnion
                    val contacts = container.conversations.listContacts().map { it.onionAddress.value }.toSet()
                    val names = members.associate { it.memberOnion.value to container.conversations.displayName(it.memberOnion.value) }
                    Data(members.map { Member(it.memberOnion.value, names[it.memberOnion.value] ?: it.memberOnion.value.take(10), it.role, it.memberOnion.value == self, it.memberOnion.value in contacts) }, admin)
                }.getOrNull()
            } ?: return@launch
            render(data)
        }
    }

    private data class Member(val onion: String, val name: String, val role: GroupRole, val isSelf: Boolean, val isContact: Boolean)
    private data class Data(val members: List<Member>, val admin: Boolean)

    private fun render(data: Data) {
        page.remove(membersGroup)
        page.remove(actionsGroup)
        membersGroup = PreferencesGroup()
        actionsGroup = PreferencesGroup()
        membersGroup.title = tr("group_members")
        data.members.sortedWith(compareBy({ it.role != GroupRole.ADMIN }, { it.name.lowercase() })).forEach { member ->
            val row = ActionRow()
            row.useMarkup = false
            row.title = if (member.isSelf) tr("desktop_you") else member.name
            row.subtitle = (if (member.role == GroupRole.ADMIN) tr("group_member_admin") + " · " else "") + member.onion.take(16) + "…"
            if (!member.isSelf) {
                if (!member.isContact) {
                    row.addSuffix(smallButton(tr("group_add_to_contacts")) {
                        val ok = container.conversations.addGroupMemberToContacts(member.onion, member.name)
                        if (ok) Toasts.show(tr("toast_added_to_contacts"))
                    })
                }
                if (data.admin && member.role != GroupRole.ADMIN) {
                    row.addSuffix(smallButton(tr("group_promote")) { container.conversations.promoteGroupMember(conversationId, member.onion) })
                }
                if (data.admin) {
                    row.addSuffix(smallButton(tr("group_remove"), destructive = true, confirmBody = tr("group_remove_confirm", member.name)) {
                        container.conversations.removeGroupMember(conversationId, member.onion)
                    })
                }
            }
            membersGroup.add(row)
        }
        if (data.admin) {
            val add = ActionRow()
            add.useMarkup = false
            add.title = tr("group_add_members")
            add.activatable = true
            add.addSuffix(org.gnome.gtk.Image.fromIconName("list-add-symbolic"))
            add.onActivated { addMembers() }
            actionsGroup.add(add)
        }
        val leave = ActionRow()
        leave.useMarkup = false
        leave.title = tr("group_leave")
        leave.subtitle = tr("group_leave_desc")
        leave.activatable = true
        leave.addCssClass("danger")
        leave.onActivated { leave() }
        actionsGroup.add(leave)
        page.add(membersGroup)
        page.add(actionsGroup)
    }

    private fun smallButton(label: String, destructive: Boolean = false, confirmBody: String? = null, work: suspend () -> Any?): Button {
        val button = Button.withLabel(label)
        button.valign = Align.CENTER
        button.addCssClass(if (destructive) "destructive-action" else "flat")
        button.onClicked {
            uiScope.launch {
                if (confirmBody != null && !confirm(dialog, label, confirmBody, label, destructive = true)) return@launch
                button.sensitive = false
                withContext(Dispatchers.IO) { runCatching { work() } }
                reload()
            }
        }
        return button
    }

    private fun addMembers() {
        uiScope.launch {
            val candidates = withContext(Dispatchers.IO) {
                runCatching {
                    val members = container.conversations.groupMembers(conversationId).map { it.memberOnion.value }.toSet()
                    container.conversations.listConversations()
                        .filter { it.type == ConversationType.DIRECT && !it.pending && it.id !in members }
                }.getOrDefault(emptyList())
            }
            if (candidates.isEmpty()) {
                Toasts.show(tr("group_no_candidates"))
                return@launch
            }
            val chooser = PickerDialog(tr("group_add_members"), candidates.map { it.id to it.title }) { chosen ->
                if (chosen.isEmpty()) return@PickerDialog
                uiScope.launch {
                    withContext(Dispatchers.IO) { runCatching { container.conversations.addGroupMembers(conversationId, chosen) } }
                    reload()
                }
            }
            chooser.present(dialog)
        }
    }

    private fun leave() {
        uiScope.launch {
            if (!confirm(dialog, tr("leave_group_title"), tr("leave_group_message"), tr("leave_group_confirm"), destructive = true)) return@launch
            val ok = withContext(Dispatchers.IO) { runCatching { container.conversations.leaveGroup(conversationId) }.getOrDefault(false) }
            if (ok) {
                Toasts.show(tr("toast_left_group"))
                close()
                onLeft()
            } else {
                Toasts.show(tr("toast_change_failed"))
            }
        }
    }
}

/** A list of checkboxes and an OK button. */
class PickerDialog(title: String, items: List<Pair<String, String>>, private val onChosen: (List<String>) -> Unit) :
    NiixDialog(title, 420, 520) {

    init {
        val page = PreferencesPage()
        val group = PreferencesGroup()
        val checks = items.map { (id, label) ->
            val row = ActionRow()
            row.useMarkup = false
            row.title = label
            val check = CheckButton()
            row.addPrefix(check)
            row.activatableWidget = check
            group.add(row)
            check to id
        }
        page.add(group)
        view.content = page
        val ok = Button.withLabel(tr("dialog_ok"))
        ok.addCssClass("suggested-action")
        ok.onClicked {
            onChosen(checks.filter { it.first.active }.map { it.second })
            close()
        }
        header.packEnd(ok)
    }
}
