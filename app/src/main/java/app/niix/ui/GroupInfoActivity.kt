package app.niix.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import app.niix.R
import app.niix.core.model.ConversationType
import app.niix.core.model.GroupRole
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroupInfoActivity : SecureActivity() {

    private lateinit var conversationId: String
    private var amAdmin = false
    private var leaving = false
    private var hasGroupPhoto = false

    private val pickGroupPhoto = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            groupCropLauncher.launch(
                Intent(this, CropActivity::class.java).putExtra(CropActivity.EXTRA_SOURCE_URI, it.toString()),
            )
        }
    }
    private val groupCropLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(CropActivity.EXTRA_RESULT_PATH)?.let { onGroupPhotoCropped(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conversationId = intent.getStringExtra(EXTRA_ID).orEmpty()
        if (conversationId.isEmpty()) { finish(); return }
        setContentView(R.layout.activity_group_info)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btn_add_member).setOnClickListener { showAddMembers() }
        findViewById<LinearLayout>(R.id.row_leave_group).setOnClickListener { confirmLeaveGroup() }
        findViewById<TextView>(R.id.group_avatar).apply {
            setOnClickListener { onGroupAvatarTapped() }
            setOnLongClickListener { onGroupAvatarLongPressed(); true }
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing) load()
    }

    private fun load() {
        lifecycleScope.launch {
            amAdmin = withContext(Dispatchers.IO) { container.conversations.amIGroupAdmin(conversationId) }
            findViewById<ImageButton>(R.id.btn_add_member).visibility =
                if (amAdmin) android.view.View.VISIBLE else android.view.View.GONE
            val conversation = withContext(Dispatchers.IO) { container.conversations.conversation(conversationId) }
            findViewById<TextView>(R.id.group_title_text).text = conversation?.title.orEmpty()
            loadGroupAvatar()
            val rows = withContext(Dispatchers.IO) {
                container.conversations.groupMembers(conversationId).map { m ->
                    Triple(m.memberOnion.value, container.conversations.displayName(m.memberOnion.value), m.role)
                }
            }
            val list = findViewById<LinearLayout>(R.id.member_list)
            list.removeAllViews()
            for ((onion, name, role) in rows) {
                list.addView(memberRow(onion, name, role))
            }
        }
    }

    private fun loadGroupAvatar() {
        val avatar = findViewById<TextView>(R.id.group_avatar)
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { container.conversations.profileBytes(conversationId) }
            hasGroupPhoto = bytes != null
            if (bytes == null) {
                avatar.text = findViewById<TextView>(R.id.group_title_text).text.firstOrNull()?.uppercase() ?: "?"
                avatar.background = androidx.core.content.ContextCompat.getDrawable(this@GroupInfoActivity, R.drawable.avatar_square)
                return@launch
            }
            val bmp = withContext(Dispatchers.Default) {
                SafeBitmap.decodeBytes(bytes)
            } ?: return@launch
            val drawable = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(resources, bmp)
            drawable.cornerRadius = resources.displayMetrics.density * 10f
            avatar.text = ""
            avatar.background = drawable
        }
    }

    private fun onGroupAvatarTapped() {
        if (!amAdmin) {
            Toast.makeText(this, getString(R.string.group_photo_admin_only), Toast.LENGTH_SHORT).show()
            return
        }
        pickGroupPhoto.launch("image/*")
    }

    private fun onGroupAvatarLongPressed() {
        if (!amAdmin || !hasGroupPhoto) return
        niixDialog(this)
            .setTitle(R.string.group_photo_remove)
            .setMessage(R.string.group_photo_remove_confirm)
            .setPositiveButton(R.string.dialog_ok) { _, _ -> setGroupPhoto(null) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun onGroupPhotoCropped(path: String) {
        val file = java.io.File(path)
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                val b = runCatching { file.readBytes() }.getOrNull()
                file.delete()
                b
            }
            if (bytes == null) {
                Toast.makeText(this@GroupInfoActivity, getString(R.string.toast_failed, ""), Toast.LENGTH_SHORT).show()
                return@launch
            }
            setGroupPhoto(bytes)
        }
    }

    private fun setGroupPhoto(bytes: ByteArray?) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { container.conversations.setGroupProfile(conversationId, bytes) }.getOrDefault(false)
            }
            Toast.makeText(this@GroupInfoActivity, getString(if (ok) R.string.toast_profile_updated else R.string.toast_failed, ""), Toast.LENGTH_SHORT).show()
            if (ok) loadGroupAvatar()
        }
    }

    /** A tap opens the member's profile page, which has the admin options when there are any. */
    private fun memberRow(onion: String, name: String, role: GroupRole): TextView {
        val label = if (role == GroupRole.ADMIN) "$name  \u2022  admin" else name
        return TextView(this).apply {
            text = label
            setTextColor(resources.getColor(if (role == GroupRole.ADMIN) R.color.niix_pink else R.color.niix_on_surface, theme))
            textSize = 16f
            setPadding(24, 32, 24, 32)
            val ripple = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener {
                val page = Intent(this@GroupInfoActivity, ProfileActivity::class.java)
                if (onion != container.accountOnion) {
                    page.putExtra(ProfileActivity.EXTRA_CONTACT, onion).putExtra(ProfileActivity.EXTRA_GROUP, conversationId)
                }
                startActivity(page)
            }
        }
    }

    private fun execute(block: suspend () -> Boolean) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { block() }.getOrDefault(false) }
            if (!ok) Toast.makeText(this@GroupInfoActivity, getString(R.string.group_not_admin), Toast.LENGTH_SHORT).show()
            load()
        }
    }

    private fun showAddMembers() {
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) {
                container.conversations.groupMembers(conversationId).map { it.memberOnion.value }.toSet()
            }
            val candidates = withContext(Dispatchers.IO) {
                container.conversations.listConversations()
                    .filter { it.type == ConversationType.DIRECT && !it.pending && it.id !in existing }
                    .map { Pair(it.id, it.title) }
            }
            if (candidates.isEmpty()) {
                Toast.makeText(this@GroupInfoActivity, getString(R.string.group_no_candidates), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = candidates.map { it.second }.toTypedArray()
            val checked = BooleanArray(candidates.size)
            niixDialog(this@GroupInfoActivity)
                .setTitle(R.string.group_add_members)
                .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton(R.string.dialog_ok) { _, _ ->
                    val selected = candidates.filterIndexed { i, _ -> checked[i] }.map { it.first }
                    if (selected.isNotEmpty()) execute { container.conversations.addGroupMembers(conversationId, selected) }
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    private fun confirmLeaveGroup() {
        niixDialog(this)
            .setTitle(R.string.leave_group_title)
            .setMessage(R.string.leave_group_message)
            .setPositiveButton(R.string.leave_group_confirm) { _, _ -> leaveGroupNow() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun leaveGroupNow() {
        if (leaving) return
        leaving = true
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { container.conversations.leaveGroup(conversationId) }.getOrDefault(false)
            }
            if (ok) {
                Toast.makeText(this@GroupInfoActivity, getString(R.string.toast_left_group), Toast.LENGTH_SHORT).show()

                startActivity(
                    Intent(this@GroupInfoActivity, HomeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
                finish()
            } else {
                leaving = false
                Toast.makeText(this@GroupInfoActivity, getString(R.string.toast_failed, ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
