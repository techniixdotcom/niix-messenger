package app.niix.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.lifecycle.lifecycleScope
import app.niix.QrCodes
import app.niix.R
import app.niix.core.model.GroupRole
import app.niix.core.model.TrustState
import app.niix.core.storage.SettingsStore
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A profile page. Your own, with your photo, name and share code; or, with [EXTRA_CONTACT], a
 * contact's or group member's, with what you can do about them. Opened from group info with
 * [EXTRA_GROUP] it also has what a group admin can do. In both, tapping a photo shows it full size.
 */
class ProfileActivity : SecureActivity() {

    private lateinit var avatar: TextView
    private lateinit var name: TextView
    private var photo: Bitmap? = null

    /** The contact whose page this is, or null for your own. */
    private var contact: String? = null

    /** The group this page was opened from, if any. */
    private var group: String? = null
    private val contactDialogs by lazy { ContactDialogs(this, container.conversations) }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            cropPhoto.launch(Intent(this, CropActivity::class.java).putExtra(CropActivity.EXTRA_SOURCE_URI, it.toString()))
        }
    }
    private val cropPhoto = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(CropActivity.EXTRA_RESULT_PATH)?.let { onPhotoCropped(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        avatar = findViewById(R.id.profile_avatar)
        name = findViewById(R.id.profile_name)
        contact = intent.getStringExtra(EXTRA_CONTACT)
        group = intent.getStringExtra(EXTRA_GROUP)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        avatar.setOnClickListener {
            val shown = photo
            when {
                shown != null -> showPhotoLarge(shown)
                contact == null -> pickPhoto.launch("image/*")
            }
        }
        val onion = contact
        if (onion == null) setUpSelf() else setUpContact(onion)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && contact != null) contactDialogs.reassertKeyboard()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        // Again on every resume: the chat can rename, block or verify the contact meanwhile.
        val onion = contact
        if (onion == null) loadProfile() else loadContact(onion)
    }

    private fun setUpSelf() {
        findViewById<LinearLayout>(R.id.row_photo).setOnClickListener { pickPhoto.launch("image/*") }
        findViewById<LinearLayout>(R.id.row_remove_photo).setOnClickListener { removePhoto() }
        findViewById<LinearLayout>(R.id.row_name).setOnClickListener { changeNameDialog() }
        findViewById<LinearLayout>(R.id.row_code).setOnClickListener {
            startActivity(Intent(this, MyCodeActivity::class.java))
        }
        loadCode()
    }

    private fun setUpContact(onion: String) {
        findViewById<MaterialToolbar>(R.id.toolbar).setTitle(R.string.contact_profile_title)
        findViewById<View>(R.id.self_card).visibility = View.GONE
        findViewById<View>(R.id.code_card).visibility = View.GONE
        findViewById<View>(R.id.contact_card).visibility = View.VISIBLE
        findViewById<TextView>(R.id.profile_address).apply {
            text = onion
            visibility = View.VISIBLE
        }
        findViewById<View>(R.id.row_message).setOnClickListener { openChat(onion) }
        findViewById<View>(R.id.row_safety).setOnClickListener {
            contactDialogs.showSafetyNumber(onion) { loadContact(onion) }
        }
        findViewById<View>(R.id.row_mute).setOnClickListener { toggleMute(onion) }
        group?.let { groupId ->
            findViewById<View>(R.id.row_group_promote).setOnClickListener {
                groupAction(closeAfter = false) { container.conversations.promoteGroupMember(groupId, onion) }
            }
            findViewById<View>(R.id.row_group_remove).setOnClickListener { confirmRemoveFromGroup(groupId, onion) }
        }
    }

    private fun loadProfile() {
        lifecycleScope.launch {
            val username = container.conversations.username()
            val decoded = container.conversations.profileBytes(SELF)?.let { bytes ->
                withContext(Dispatchers.Default) { SafeBitmap.decodeBytes(bytes) }
            }
            name.text = username
            showAvatar(username, decoded)
            findViewById<LinearLayout>(R.id.row_remove_photo).visibility = if (decoded == null) View.GONE else View.VISIBLE
            findViewById<TextView>(R.id.profile_hint).setText(
                if (decoded == null) R.string.profile_photo_hint_add else R.string.profile_photo_hint_view,
            )
        }
    }

    private fun loadContact(onion: String) {
        lifecycleScope.launch {
            val conversations = container.conversations
            val displayName = conversations.displayName(onion)
            val decoded = conversations.profileBytes(onion)?.let { bytes ->
                withContext(Dispatchers.Default) { SafeBitmap.decodeBytes(bytes) }
            }
            val verified = conversations.trustState(onion) == TrustState.VERIFIED
            val blocked = conversations.isBlocked(onion)
            val isContact = conversations.listContacts().any { it.onionAddress.value == onion }
            name.text = displayName
            showAvatar(displayName, decoded)
            findViewById<TextView>(R.id.profile_hint).apply {
                setText(R.string.profile_photo_hint_view)
                visibility = if (decoded == null) View.GONE else View.VISIBLE
            }
            findViewById<TextView>(R.id.contact_safety_desc).setText(
                if (verified) R.string.contact_profile_verified else R.string.contact_profile_unverified,
            )
            findViewById<TextView>(R.id.row_rename).apply {
                setText(if (isContact) R.string.menu_rename else R.string.group_add_to_contacts)
                setOnClickListener {
                    if (isContact) {
                        contactDialogs.rename(onion, displayName) { loadContact(onion) }
                    } else {
                        addToContacts(onion, displayName)
                    }
                }
            }
            showMuted(onion)
            findViewById<TextView>(R.id.row_block).apply {
                setText(if (blocked) R.string.menu_unblock else R.string.menu_block)
                setOnClickListener { if (blocked) confirmUnblock(onion) else block(onion) }
            }
            group?.let { showGroupActions(it, onion) }
        }
    }

    /** Promote and remove, only for an admin, and promote only for someone who isn't one already. */
    private suspend fun showGroupActions(groupId: String, onion: String) {
        val conversations = container.conversations
        val amAdmin = conversations.amIGroupAdmin(groupId)
        val member = conversations.groupMembers(groupId).firstOrNull { it.memberOnion.value == onion }
        findViewById<View>(R.id.group_card).visibility = if (amAdmin && member != null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.row_group_promote).visibility =
            if (member?.role == GroupRole.ADMIN) View.GONE else View.VISIBLE
    }

    private fun addToContacts(onion: String, displayName: String) {
        lifecycleScope.launch {
            val ok = runCatching { container.conversations.addGroupMemberToContacts(onion, displayName) }.getOrDefault(false)
            toast(if (ok) getString(R.string.toast_added_to_contacts, displayName) else getString(R.string.toast_failed, ""))
            loadContact(onion)
        }
    }

    private fun confirmRemoveFromGroup(groupId: String, onion: String) {
        niixDialog(this)
            .setTitle(R.string.group_remove)
            .setMessage(getString(R.string.group_remove_confirm, name.text))
            .setPositiveButton(R.string.group_remove) { _, _ ->
                groupAction(closeAfter = true) { container.conversations.removeGroupMember(groupId, onion) }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showDestructive()
    }

    /**
     * Runs an admin action, then goes back to group info if [closeAfter] (the page is about a
     * membership that's gone), or shows the page again. Says so if it wasn't allowed.
     */
    private fun groupAction(closeAfter: Boolean, action: suspend () -> Boolean) {
        lifecycleScope.launch {
            val ok = runCatching { action() }.getOrDefault(false)
            when {
                !ok -> toast(getString(R.string.group_not_admin))
                closeAfter -> finish()
                else -> contact?.let { loadContact(it) }
            }
        }
    }

    /**
     * Goes back to the chat when that's where this page was opened from, so it isn't opened twice.
     * Otherwise opens it, in place of this page.
     */
    private fun openChat(onion: String) {
        if (intent.getBooleanExtra(EXTRA_FROM_CHAT, false)) {
            finish()
            return
        }
        lifecycleScope.launch {
            val conversation = container.conversations.ensureConversationForContact(onion)
            startActivity(
                Intent(this@ProfileActivity, ChatActivity::class.java)
                    .putExtra(ChatActivity.EXTRA_ID, conversation.id)
                    .putExtra(ChatActivity.EXTRA_TITLE, conversation.title),
            )
            finish()
        }
    }

    private fun showMuted(onion: String) {
        val muted = container.storage.settings.getBool(SettingsStore.mutedKey(onion), false)
        findViewById<TextView>(R.id.row_mute).setText(if (muted) R.string.conversation_unmute else R.string.conversation_mute)
    }

    private fun toggleMute(onion: String) {
        val key = SettingsStore.mutedKey(onion)
        val muted = !container.storage.settings.getBool(key, false)
        container.storage.settings.setBool(key, muted)
        toast(getString(if (muted) R.string.conversation_muted else R.string.conversation_unmuted))
        showMuted(onion)
    }

    private fun block(onion: String) {
        lifecycleScope.launch {
            runCatching { container.conversations.block(onion) }
            toast(getString(R.string.toast_blocked))
            loadContact(onion)
        }
    }

    private fun confirmUnblock(onion: String) {
        niixDialog(this)
            .setTitle(R.string.blocked_unblock_title)
            .setMessage(R.string.blocked_unblock_body)
            .setPositiveButton(R.string.menu_unblock) { _, _ ->
                lifecycleScope.launch {
                    runCatching { container.conversations.unblock(onion) }
                    toast(getString(R.string.toast_unblocked))
                    loadContact(onion)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showAvatar(username: String, photo: Bitmap?) {
        this.photo = photo
        if (photo == null) {
            avatar.text = username.trim().firstOrNull()?.uppercase() ?: "?"
            avatar.setBackgroundResource(R.drawable.avatar_square)
        } else {
            avatar.text = ""
            avatar.background = RoundedBitmapDrawableFactory.create(resources, photo).apply {
                cornerRadius = resources.displayMetrics.density * 24f
            }
        }
    }

    /** A thumbnail of the QR code. Retries until Tor has published the address it encodes. */
    private fun loadCode() {
        lifecycleScope.launch {
            while (isActive) {
                val code = withContext(Dispatchers.IO) { container.shareCode() }
                if (code != null) {
                    withContext(Dispatchers.Default) { QrCodes.encode(code, 512) }?.let {
                        findViewById<ImageView>(R.id.profile_qr).setImageBitmap(it)
                    }
                    findViewById<TextView>(R.id.profile_code_desc).setText(R.string.profile_code_desc)
                    return@launch
                }
                delay(1500)
            }
        }
    }

    private fun changeNameDialog() {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(name.text)
            setSelection(text.length)
        }
        val padded = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(field)
        }
        niixDialog(this)
            .setTitle(R.string.profile_change_name)
            .setMessage(R.string.profile_change_name_body)
            .setView(padded)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                lifecycleScope.launch {
                    val ok = container.conversations.setUsername(field.text.toString())
                    toast(getString(if (ok) R.string.profile_name_changed else R.string.profile_name_invalid))
                    if (ok) loadProfile()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun onPhotoCropped(path: String) {
        val file = java.io.File(path)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val bytes = runCatching { file.readBytes() }.getOrNull()
                file.delete()
                bytes != null && runCatching { container.conversations.setSelfProfile(bytes) }.isSuccess
            }
            toast(if (ok) getString(R.string.toast_profile_updated) else getString(R.string.toast_failed, ""))
            loadProfile()
        }
    }

    private fun removePhoto() {
        niixDialog(this)
            .setMessage(R.string.profile_remove_photo_confirm)
            .setPositiveButton(R.string.profile_remove_photo) { _, _ ->
                lifecycleScope.launch {
                    runCatching { container.conversations.setSelfProfile(null) }
                    toast(getString(R.string.toast_profile_updated))
                    loadProfile()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showDestructive()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        /** A contact's address, to show their page instead of your own. */
        const val EXTRA_CONTACT = "contact"

        /** With EXTRA_CONTACT: the group it was opened from, which adds the admin's options. */
        const val EXTRA_GROUP = "group"

        /** With EXTRA_CONTACT: opened from the chat with them, which "Send message" goes back to. */
        const val EXTRA_FROM_CHAT = "from_chat"

        private const val SELF = "self"
    }
}
