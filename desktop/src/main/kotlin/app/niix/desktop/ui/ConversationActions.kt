package app.niix.desktop.ui

import app.niix.core.messaging.CallAvailability
import app.niix.core.model.Conversation
import app.niix.core.model.ConversationType
import app.niix.core.storage.SettingsStore
import app.niix.desktop.DesktopContainer
import app.niix.desktop.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gnome.gtk.Widget

/**
 * What can be done with a chat or a contact: calling, renaming, the safety number, the
 * disappearing-message timer, muting, blocking and deleting. One place for it, so the chat's menu
 * and a right-click in the chat list offer the same things and do them the same way.
 */
class ConversationActions(
    private val container: DesktopContainer,
    private val conversationId: String,
    /** The widget dialogs are shown over. */
    private val parent: () -> Widget,
    /** Something about the chat changed and what shows it should be read again. */
    private val onChanged: () -> Unit,
    /** The chat is gone: deleted, or its contact blocked. */
    private val onGone: () -> Unit,
) {

    /** What the choices depend on, read fresh before they're offered. */
    class State(val conversation: Conversation, val blocked: Boolean, val muted: Boolean, val calls: CallAvailability) {
        val group: Boolean get() = conversation.type == ConversationType.GROUP
        val callsAllowedByMe: Boolean get() = calls == CallAvailability.READY || calls == CallAvailability.WAITING_FOR_THEM
    }

    class Item(val label: String, val destructive: Boolean, val run: () -> Unit)

    /** Null if the chat no longer exists or the database has been locked. */
    suspend fun load(): State? = withContext(Dispatchers.IO) {
        runCatching {
            val c = container.conversations.conversation(conversationId) ?: return@runCatching null
            val direct = c.type == ConversationType.DIRECT
            State(
                conversation = c,
                blocked = direct && runCatching { container.conversations.isBlocked(conversationId) }.getOrDefault(false),
                muted = container.storage.settings.getBool(SettingsStore.mutedKey(conversationId), false),
                calls = if (direct) container.conversations.callAvailability(conversationId) else CallAvailability.UNAVAILABLE,
            )
        }.getOrNull()
    }

    /** The choices for [state], in the order the chat's menu has them. */
    fun items(state: State): List<Item> {
        val items = ArrayList<Item>()
        fun add(label: String, destructive: Boolean = false, run: () -> Unit) {
            items.add(Item(tr(label), destructive, run))
        }
        if (!state.group && !state.blocked) {
            add("menu_voice_call") { placeCall(state) }
            add(if (state.callsAllowedByMe) "menu_calls_stop" else "menu_calls_allow") { toggleCallsAllowed(state) }
        }
        add(if (state.group) "menu_group_info" else "contact_profile_title") { openInfo(state) }
        add("menu_rename") { rename(state) }
        if (!state.group) add("action_safety_number") { safetyNumber() }
        add("menu_disappearing") { chooseTimer() }
        add(if (state.muted) "conversation_unmute" else "conversation_mute") { toggleMute(state) }
        if (!state.group) add(if (state.blocked) "menu_unblock" else "menu_block") { toggleBlock(state) }
        add("action_delete", destructive = true) { delete(state) }
        return items
    }

    fun openInfo(state: State) {
        if (state.group) {
            GroupInfoDialog(container, conversationId) { onGone() }.present(parent())
        } else {
            safetyNumber()
        }
    }

    fun safetyNumber() = showSafetyNumber(parent(), container, conversationId) { onChanged() }

    fun rename(state: State) {
        uiScope.launch {
            val name = askText(parent(), tr("menu_rename"), "", initial = state.conversation.title, placeholder = tr("hint_contact_name"), maxLength = 64) ?: return@launch
            if (name.isEmpty()) return@launch
            withContext(Dispatchers.IO) { runCatching { container.conversations.renameContact(conversationId, name) } }
            onChanged()
        }
    }

    fun chooseTimer() {
        uiScope.launch {
            val labels = listOf(tr("timer_off"), tr("timer_5min"), tr("timer_1hour"), tr("timer_1day"))
            val seconds = longArrayOf(0L, 5L * 60, 60L * 60, 24L * 60 * 60)
            val index = choose(parent(), tr("timer_title"), tr("timer_limits_note"), labels) ?: return@launch
            withContext(Dispatchers.IO) { runCatching { container.conversations.setDisappearTimer(conversationId, seconds[index]) } }
            Toasts.show(tr("toast_timer_set"))
            onChanged()
        }
    }

    /** Calls if this side allows it, otherwise says what's missing and offers to allow it. */
    fun placeCall(state: State) {
        uiScope.launch {
            when (withContext(Dispatchers.IO) { container.conversations.callAvailability(conversationId) }) {
                // Waiting only means their "I allow calls" hasn't reached this device, which over
                // Tor can lag. Their device decides, and refuses before ringing if they haven't.
                CallAvailability.READY, CallAvailability.WAITING_FOR_THEM -> {
                    if (!container.conversations.calls.startCall(conversationId) && !container.conversations.calls.isInCall()) {
                        Toasts.show(tr("call_failed"))
                    }
                }
                CallAvailability.NEEDS_VERIFICATION -> Toasts.show(tr("calls_need_verification"))
                CallAvailability.NOT_ALLOWED -> allowCalls(state)
                CallAvailability.UNAVAILABLE -> Toasts.show(tr("calls_unavailable"))
            }
        }
    }

    private suspend fun allowCalls(state: State) {
        if (!confirm(parent(), tr("calls_allow_title"), tr("calls_allow_body", state.conversation.title), tr("call_allow_action"))) return
        val ok = withContext(Dispatchers.IO) { runCatching { container.conversations.setCallsAllowed(conversationId, true) }.getOrDefault(false) }
        Toasts.show(tr(if (ok) "calls_allowed_toast" else "calls_need_verification"))
        onChanged()
    }

    fun toggleCallsAllowed(state: State) {
        uiScope.launch {
            if (state.callsAllowedByMe) {
                withContext(Dispatchers.IO) { runCatching { container.conversations.setCallsAllowed(conversationId, false) } }
                Toasts.show(tr("calls_stopped_toast"))
                onChanged()
            } else {
                when (withContext(Dispatchers.IO) { container.conversations.callAvailability(conversationId) }) {
                    CallAvailability.NEEDS_VERIFICATION -> Toasts.show(tr("calls_need_verification"))
                    CallAvailability.UNAVAILABLE -> Toasts.show(tr("calls_unavailable"))
                    else -> allowCalls(state)
                }
            }
        }
    }

    fun toggleMute(state: State) {
        val mute = !state.muted
        uiScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (mute) container.storage.settings.setBool(SettingsStore.mutedKey(conversationId), true)
                    else container.storage.settings.remove(SettingsStore.mutedKey(conversationId))
                }
            }
            Toasts.show(tr(if (mute) "conversation_muted" else "conversation_unmuted"))
            onChanged()
        }
    }

    fun toggleBlock(state: State) {
        uiScope.launch {
            if (state.blocked) {
                withContext(Dispatchers.IO) { runCatching { container.conversations.unblock(conversationId) } }
                Toasts.show(tr("toast_unblocked"))
                onChanged()
            } else {
                withContext(Dispatchers.IO) { runCatching { container.conversations.block(conversationId) } }
                Toasts.show(tr("toast_blocked"))
                onGone()
            }
        }
    }

    fun delete(state: State) {
        uiScope.launch {
            val body = if (state.group) tr("delete_conversation_group_message") else tr("delete_conversation_message", state.conversation.title)
            if (!confirm(parent(), tr("delete_conversation_title"), body, tr("action_delete"), destructive = true)) return@launch
            withContext(Dispatchers.IO) { runCatching { container.conversations.deleteConversation(conversationId) } }
            onGone()
        }
    }
}
