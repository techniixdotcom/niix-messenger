package app.niix.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
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
    }

    /**
     * Forces the header row and the contacts drawer to sit below the status bar.
     *
     * DrawerLayout doesn't apply window-inset padding to its children the way a plain
     * ViewGroup with `fitsSystemWindows` does, so with edge-to-edge enabled (see
     * [SecureActivity]) Home's content was drawing all the way to the physical top of the
     * screen, leaving the status bar row transparent -- some device skins then filled that
     * transparent strip with the app's accent color, which is the "green bar" this fixes.
     */
    private fun applyStatusBarInsets() {
        val content = findViewById<LinearLayout>(R.id.home_content_root)
        val drawer = findViewById<LinearLayout>(R.id.contacts_drawer)
        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            content.setPadding(content.paddingLeft, top, content.paddingRight, content.paddingBottom)
            drawer.setPadding(drawer.paddingLeft, top, drawer.paddingRight, drawer.paddingBottom)
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
            val (groups, contacts) = withContext(Dispatchers.IO) {
                val all = container.conversations.listConversations().map { c ->
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
                Pair(g, c)
            }
            groupAdapter.submit(groups)
            contactAdapter.submit(contacts)
            groupsEmpty.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
            contactsEmpty.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
        }
    }

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

    /** Loads every saved contact into the left-edge drawer, including ones whose conversation
     * was deleted -- they stay in your contacts and can be messaged again from here. */
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

    /** Opens a chat with a contact picked from the drawer, recreating the conversation first
     * if it was previously deleted -- no re-adding or re-exchanging keys is needed. */
    private fun openContactFromDrawer(onion: String) {
        drawerLayout.closeDrawer(GravityCompat.START)
        lifecycleScope.launch {
            val conversation = withContext(Dispatchers.IO) {
                container.conversations.ensureConversationForContact(onion)
            }
            openChat(conversation.id, conversation.title)
        }
    }

    /** Shows a contact's or group's profile photo full-screen when its avatar is tapped. */
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
}
