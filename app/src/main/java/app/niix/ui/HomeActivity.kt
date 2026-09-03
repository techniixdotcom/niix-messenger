package app.niix.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.niix.ConnectivityService
import app.niix.R
import app.niix.core.model.ConversationType
import app.niix.core.model.Message
import app.niix.core.model.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActivity : SecureActivity() {

    private lateinit var groupAdapter: ConversationAdapter
    private lateinit var contactAdapter: ConversationAdapter
    private lateinit var groupsEmpty: View
    private lateinit var contactsEmpty: View

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerContactAdapter: ConversationAdapter
    private lateinit var drawerContactsEmpty: View

    private var initialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        groupsEmpty = findViewById(R.id.groups_empty)
        contactsEmpty = findViewById(R.id.contacts_empty)

        drawerLayout = findViewById(R.id.drawer_layout)
        applyStatusBarInsets()
        drawerContactsEmpty = findViewById(R.id.drawer_contacts_empty)
        drawerContactAdapter = ConversationAdapter(
            onClick = { row -> openContactFromDrawer(row.id) },
            loadAvatar = { tv, id -> applyAvatar(tv, id) },
        )
        findViewById<RecyclerView>(R.id.drawer_contact_list).apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
            adapter = drawerContactAdapter
            isNestedScrollingEnabled = false
        }
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) = loadDrawerContacts()
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        groupAdapter = ConversationAdapter(
            onClick = { row -> openChat(row.id, row.title) },
            loadAvatar = { tv, id -> applyAvatar(tv, id) },
            onLongClick = { row -> confirmDeleteConversation(row, isGroup = true) },
            onAvatarClick = { id -> enlargeAvatar(id) },
        )
        contactAdapter = ConversationAdapter(
            onClick = { row -> openChat(row.id, row.title) },
            loadAvatar = { tv, id -> applyAvatar(tv, id) },
            onLongClick = { row -> confirmDeleteConversation(row, isGroup = false) },
            onAvatarClick = { id -> enlargeAvatar(id) },
        )
        findViewById<RecyclerView>(R.id.group_list).apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
            adapter = groupAdapter
            isNestedScrollingEnabled = false
        }
        findViewById<RecyclerView>(R.id.contact_list).apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
            adapter = contactAdapter
            isNestedScrollingEnabled = false
        }

        findViewById<TextView>(R.id.self_avatar).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.fab_new).setOnClickListener { showNewMenu(it) }

        lifecycleScope.launch {
            container.conversations.changes.collect { load() }
        }
        maybeAutoCheckForUpdate()
    }

    private fun maybeAutoCheckForUpdate() {
        lifecycleScope.launch {
            val enabled = withContext(Dispatchers.IO) {
                container.storage.settings.getBool(app.niix.core.storage.SettingsStore.KEY_UPDATE_CHECK_ENABLED, true)
            }
            if (!enabled) return@launch
            val lastCheckedAt = withContext(Dispatchers.IO) {
                container.storage.settings.getLong(app.niix.core.storage.SettingsStore.KEY_LAST_UPDATE_CHECK_AT, 0L)
            }
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastCheckedAt < AUTO_UPDATE_CHECK_INTERVAL_MILLIS) return@launch

            val overTor = withContext(Dispatchers.IO) {
                container.storage.settings.getBool(app.niix.core.storage.SettingsStore.KEY_UPDATE_OVER_TOR, false)
            }
            val socks = if (overTor) container.transport.socksAddress() else null
            // Only a Tor-routed check needs Tor up; skip silently in that case rather than
            // interrupting with something the user didn't ask for. Deliberately does not record
            // the check timestamp, so it retries promptly once a connection exists instead of
            // being suppressed for the full interval by an attempt that never left the device.
            if (overTor && socks == null) return@launch
            val checker = app.niix.update.UpdateChecker(
                applicationContext,
                useTor = overTor,
                socksHost = socks?.first,
                socksPort = socks?.second,
            )
            val currentVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
            val result = withContext(Dispatchers.IO) {
                runCatching { checker.checkForUpdate(currentVersion) }
                    .getOrElse { app.niix.update.UpdateCheckResult.Error(it.message ?: "Unknown error") }
            }
            withContext(Dispatchers.IO) {
                container.storage.settings.setLong(app.niix.core.storage.SettingsStore.KEY_LAST_UPDATE_CHECK_AT, nowMs)
            }
            val info = (result as? app.niix.update.UpdateCheckResult.Available)?.info ?: return@launch
            if (isFinishing) return@launch
            AlertDialog.Builder(this@HomeActivity)
                .setTitle(getString(R.string.update_available_title, info.versionName))
                .setMessage(info.changelog)
                .setPositiveButton(R.string.update_install) { _, _ -> downloadAutoUpdate(checker, info) }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    private fun downloadAutoUpdate(checker: app.niix.update.UpdateChecker, info: app.niix.update.UpdateInfo) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { checker.downloadAndVerify(info) }
                    .getOrElse { app.niix.update.UpdateInstallResult.Rejected(it.message ?: "Unknown error") }
            }
            when (result) {
                is app.niix.update.UpdateInstallResult.Rejected ->
                    Toast.makeText(this@HomeActivity, getString(R.string.toast_failed, result.reason), Toast.LENGTH_SHORT).show()
                is app.niix.update.UpdateInstallResult.Ready -> {
                    val startError = checker.promptInstall(result.apkFile)
                    if (startError != null) {
                        android.widget.Toast.makeText(this@HomeActivity, startError, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun applyStatusBarInsets() {
        val content = findViewById<LinearLayout>(R.id.home_content_root)
        val drawer = findViewById<LinearLayout>(R.id.contacts_drawer)
        val fab = findViewById<ImageButton>(R.id.fab_new)
        val fabBaseMarginPx = (24 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.setPadding(content.paddingLeft, bars.top, content.paddingRight, content.paddingBottom)
            drawer.setPadding(drawer.paddingLeft, bars.top, drawer.paddingRight, drawer.paddingBottom)
            (fab.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = fabBaseMarginPx + bars.bottom
            fab.requestLayout()
            insets
        }
        ViewCompat.requestApplyInsets(drawerLayout)
    }

    private fun showNewMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.fab_new_menu, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_new_message -> startActivity(Intent(this@HomeActivity, NewMessageActivity::class.java))
                    R.id.action_new_group -> startActivity(Intent(this@HomeActivity, CreateGroupActivity::class.java))
                }
                true
            }
        }.show()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        if (!initialized) {
            initialized = true
            container.applyLockTimeoutFromSettings()
            requestNotificationPermissionIfNeeded()
            requestBatteryExemptionOnce()
            ConnectivityService.start(this)
        }
        avatarCache.remove("self")
        loadSelfAvatar()
        load()
    }

    private val avatarCache = HashMap<String, android.graphics.Bitmap>()

    private fun loadSelfAvatar() {
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { container.conversations.username() }
            val tv = findViewById<TextView>(R.id.self_avatar)
            tv.text = name.trim().firstOrNull()?.uppercase() ?: "?"
            tv.setBackgroundResource(R.drawable.avatar_square)
            applyAvatar(tv, "self")
        }
    }

    private fun applyAvatar(tv: TextView, key: String) {
        tv.tag = key
        avatarCache[key]?.let { setAvatarPhoto(tv, it); return }
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { container.conversations.profileBytes(key) } ?: return@launch
            val bmp = withContext(Dispatchers.Default) {
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } ?: return@launch
            avatarCache[key] = bmp
            if (tv.tag == key) setAvatarPhoto(tv, bmp)
        }
    }

    private fun setAvatarPhoto(tv: TextView, bmp: android.graphics.Bitmap) {
        val drawable = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(resources, bmp)
        drawable.cornerRadius = resources.displayMetrics.density * 10f
        tv.text = ""
        tv.background = drawable
    }

    private fun load() {
        lifecycleScope.launch {
            val (groups, contacts, requests, groupInvites) = withContext(Dispatchers.IO) {
                val all = container.conversations.listConversations().filterNot { it.pending }.map { c ->
                    val last = container.conversations.lastMessage(c.id)
                    Pair(
                        c.type,
                        ConversationRow(
                            id = c.id,
                            title = c.title,
                            preview = last?.let { previewOf(it) } ?: getString(R.string.no_messages),
                            time = last?.createdAtEpochMillis ?: c.createdAtEpochMillis,
                        ),
                    )
                }
                val g = all.filter { it.first == ConversationType.GROUP }.map { it.second }.sortedByDescending { it.time }
                val c = all.filter { it.first == ConversationType.DIRECT }.map { it.second }.sortedByDescending { it.time }
                val r = container.conversations.pendingRequests()
                    .map { req -> Pair(req, container.conversations.lastMessage(req.id)) }
                    .sortedByDescending { (req, last) -> last?.createdAtEpochMillis ?: req.createdAtEpochMillis }
                val gi = container.conversations.pendingGroupInvites()
                Quadruple(g, c, r, gi)
            }
            groupAdapter.submit(groups)
            contactAdapter.submit(contacts)
            groupsEmpty.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
            contactsEmpty.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
            renderRequests(requests, groupInvites)
        }
    }

    private fun renderRequests(
        requests: List<Pair<app.niix.core.model.Conversation, Message?>>,
        groupInvites: List<app.niix.core.storage.PendingGroupInvite>,
    ) {
        val section = findViewById<LinearLayout>(R.id.requests_section)
        val list = findViewById<LinearLayout>(R.id.requests_list)
        section.visibility = if (requests.isEmpty() && groupInvites.isEmpty()) View.GONE else View.VISIBLE
        list.removeAllViews()
        for ((conversation, last) in requests) {
            list.addView(
                requestRow(
                    title = conversation.title,

                    preview = getString(R.string.request_unverified_prefix, last?.let { previewOf(it) } ?: getString(R.string.no_messages)),
                    onAccept = { container.conversations.acceptRequest(conversation.id) },
                    onBlock = { container.conversations.blockRequest(conversation.id) },
                ),
            )
        }
        for (invite in groupInvites) {
            list.addView(
                requestRow(
                    title = invite.title,
                    preview = getString(R.string.group_invite_preview, invite.members.size, invite.inviterOnion.take(10)),
                    onAccept = { container.conversations.acceptGroupInvite(invite.conversationId) },
                    onBlock = { container.conversations.rejectGroupInvite(invite.conversationId) },
                ),
            )
        }
    }

    private fun requestRow(title: String, preview: String, onAccept: suspend () -> Boolean, onBlock: suspend () -> Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val text = TextView(this).apply {
            text = "${title.take(16)}\n$preview"
            setTextColor(resources.getColor(R.color.niix_on_surface, theme))
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val accept = requestButton(getString(R.string.request_accept), resources.getColor(R.color.niix_green, theme)) {
            respondToRequest(onAccept)
        }
        val block = requestButton(getString(R.string.request_block), resources.getColor(R.color.niix_danger, theme)) {
            respondToRequest(onBlock)
        }
        row.addView(text)
        row.addView(accept, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        row.addView(block, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        return row
    }

    private fun requestButton(label: String, color: Int, onClick: () -> Unit) =
        com.google.android.material.button.MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            setPadding(dp(14), 0, dp(14), 0)
            cornerRadius = dp(16)
            backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            setTextColor(resources.getColor(R.color.niix_bg, theme))
            setOnClickListener { onClick() }
        }

    private fun respondToRequest(action: suspend () -> Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { action() } }
            load()
        }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun previewOf(m: Message): String = when {
        m.deleted -> getString(R.string.message_deleted)
        m.type == MessageType.ATTACHMENT -> getString(R.string.attachment_label)
        else -> m.body
    }

    private fun openChat(id: String, title: String) {
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_ID, id)
                .putExtra(ChatActivity.EXTRA_TITLE, title),
        )
    }

    private fun loadDrawerContacts() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                container.conversations.listContacts().map { c ->
                    ConversationRow(
                        id = c.onionAddress.value,
                        title = c.displayName,
                        preview = c.onionAddress.value,
                        time = c.addedAtEpochMillis,
                    )
                }
            }.sortedBy { it.title.lowercase() }
            drawerContactAdapter.submit(rows)
            drawerContactsEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun openContactFromDrawer(onion: String) {
        drawerLayout.closeDrawer(GravityCompat.START)
        lifecycleScope.launch {
            val conversation = withContext(Dispatchers.IO) {
                container.conversations.ensureConversationForContact(onion)
            }
            openChat(conversation.id, conversation.title)
        }
    }

    private fun enlargeAvatar(key: String) {
        lifecycleScope.launch {
            val bitmap = avatarCache[key] ?: run {
                val bytes = withContext(Dispatchers.IO) { container.conversations.profileBytes(key) } ?: return@launch
                withContext(Dispatchers.Default) { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            } ?: return@launch
            val file = withContext(Dispatchers.IO) {
                val target = java.io.File(cacheDir, "open_avatar_${System.currentTimeMillis()}.jpg")
                runCatching {
                    java.io.FileOutputStream(target).use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out) }
                }
                target
            }
            startActivity(
                Intent(this@HomeActivity, MediaViewerActivity::class.java)
                    .putExtra(MediaViewerActivity.EXTRA_PATH, file.absolutePath)
                    .putExtra(MediaViewerActivity.EXTRA_MIME, "image/jpeg"),
            )
        }
    }

    private fun confirmDeleteConversation(row: ConversationRow, isGroup: Boolean) {
        val message = if (isGroup) {
            getString(R.string.delete_conversation_group_message)
        } else {
            getString(R.string.delete_conversation_message, row.title)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.delete_conversation_title)
            .setMessage(message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { container.conversations.deleteConversation(row.id) }
                    load()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun requestBatteryExemptionOnce() {
        if (container.storage.settings.getBool(app.niix.core.storage.SettingsStore.KEY_BATTERY_ASKED, false)) return
        val power = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        container.storage.settings.setBool(app.niix.core.storage.SettingsStore.KEY_BATTERY_ASKED, true)
        runCatching {
            startActivity(
                android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:$packageName")),
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    companion object {
        private const val AUTO_UPDATE_CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
    }
}
