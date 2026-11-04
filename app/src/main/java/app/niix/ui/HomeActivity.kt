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
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.niix.ConnectivityService
import app.niix.R
import app.niix.core.model.ConversationType
import app.niix.core.storage.SettingsStore
import app.niix.core.model.Message
import app.niix.core.model.MessageType
import app.niix.core.transport.TransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

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
        PullToRefresh.attach(this, findViewById(R.id.home_refresh), container) { load() }

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
            onLongClick = { row -> showConversationOptions(row, isGroup = true) },
            onAvatarClick = { id -> enlargeAvatar(id) },
        )
        contactAdapter = ConversationAdapter(
            onClick = { row -> openChat(row.id, row.title) },
            loadAvatar = { tv, id -> applyAvatar(tv, id) },
            onLongClick = { row -> showConversationOptions(row, isGroup = false) },
            onAvatarClick = { id ->
                startActivity(Intent(this, ProfileActivity::class.java).putExtra(ProfileActivity.EXTRA_CONTACT, id))
            },
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

        findViewById<android.widget.ImageView>(R.id.action_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.self_avatar).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        findViewById<android.widget.ImageView>(R.id.action_wipe).setOnClickListener { wipeNow() }
        findViewById<ImageButton>(R.id.fab_new).setOnClickListener { showNewMenu(it) }

        lifecycleScope.launch {
            // One reload per burst: each change restarts a short wait and only the last one
            // reloads. 100 ms isn't noticeable.
            container.conversations.changes.collectLatest {
                delay(100)
                load()
            }
        }
        // Only a fresh start counts as opening the app, not the screen being recreated.
        checkForUpdates(appOpened = savedInstanceState == null)
    }

    private fun wipeNow() {
        if (container.wipeWithoutConfirmation()) {
            performWipe()
            return
        }
        niixDialog(this)
            .setTitle(R.string.wipe_now_title)
            .setMessage(R.string.wipe_now_body)
            .setPositiveButton(R.string.wipe_now_confirm) { _, _ -> performWipe() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showDestructive()
    }

    private fun performWipe() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { container.wipeAllData() } }
            // Straight to setting up a new account, with the task cleared so nothing behind it still
            // holds data that was just destroyed.
            startActivity(
                Intent(this@HomeActivity, OnboardingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        }
    }

    /**
     * Checks for an update each time the app is opened, then about once a day for as long as it
     * stays open. Over Tor unless that has been turned off.
     */
    private fun checkForUpdates(appOpened: Boolean) {
        // Play delivers updates for the Play build, which has no permission to install one itself.
        if (!app.niix.BuildConfig.SELF_UPDATE) return
        lifecycleScope.launch {
            if (appOpened) checkForUpdateNow()
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val next = withContext(Dispatchers.IO) {
                        container.storage.settings.getString(SettingsStore.KEY_NEXT_UPDATE_CHECK)?.toLongOrNull() ?: 0L
                    }
                    delay((next - System.currentTimeMillis()).coerceAtLeast(0))
                    checkForUpdateNow()
                }
            }
        }
    }

    /**
     * One check, offering the update if there is one. Sets when the next is due: in about a day,
     * or in an hour if this one failed or Tor wasn't up in time.
     */
    private suspend fun checkForUpdateNow() {
        val settings = container.storage.settings
        val (enabled, overTor) = withContext(Dispatchers.IO) {
            settings.getBool(SettingsStore.KEY_UPDATE_CHECK_ENABLED, true) to
                settings.getBool(SettingsStore.KEY_UPDATE_OVER_TOR, true)
        }
        val (checker, result) = if (enabled) runUpdateCheck(overTor) else null to null
        withContext(Dispatchers.IO) {
            val failed = enabled && (result == null || result is app.niix.update.UpdateCheckResult.Error)
            val wait = if (failed) UPDATE_RETRY_MILLIS else app.niix.update.UpdateChecker.nextCheckDelayMillis(SecureRandom())
            runCatching { settings.setString(SettingsStore.KEY_NEXT_UPDATE_CHECK, (System.currentTimeMillis() + wait).toString()) }
        }
        val info = (result as? app.niix.update.UpdateCheckResult.Available)?.info ?: return
        if (checker == null || isFinishing) return
        niixDialog(this)
            .setTitle(getString(R.string.update_available_title, info.versionName))
            .setMessage(info.changelog)
            .setPositiveButton(R.string.update_install) { _, _ -> downloadAutoUpdate(checker, info) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /** The checker used and what it found, or nulls when Tor wasn't up in time. */
    private suspend fun runUpdateCheck(
        overTor: Boolean,
    ): Pair<app.niix.update.UpdateChecker?, app.niix.update.UpdateCheckResult?> {
        val socks = if (overTor) {
            // Tor may still be starting. Waiting costs nothing; checking without it would defeat
            // the setting.
            withTimeoutOrNull(TOR_WAIT_MILLIS) {
                container.transport.state.first { it == TransportState.RUNNING }
            } ?: return null to null
            container.transport.socksAddress() ?: return null to null
        } else {
            null
        }
        val checker = app.niix.update.UpdateChecker(
            applicationContext,
            useTor = overTor,
            socksHost = socks?.first,
            socksPort = socks?.second,
        )
        val currentVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        val result = withContext(Dispatchers.IO) {
            runCatching { checker.checkForUpdate(currentVersion) }
                .getOrElse { app.niix.update.UpdateCheckResult.Error(it.message ?: getString(R.string.error_unknown)) }
        }
        return checker to result
    }

    private fun downloadAutoUpdate(checker: app.niix.update.UpdateChecker, info: app.niix.update.UpdateInfo) {
        UpdateDownloadDialog.start(this, checker, info) { apkFile ->
            val startError = checker.promptInstall(apkFile)
            if (startError != null) {
                Toast.makeText(this, startError, Toast.LENGTH_LONG).show()
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
        // Setup chose linking and it hasn't happened yet: back to the code, unless the person
        // backed out of it. Closing the app or the phone locking doesn't count as backing out.
        if (container.storage.appLock.isUnlocked() &&
            container.storage.settings.getBool(app.niix.core.storage.SettingsStore.KEY_LINK_PENDING, false)
        ) {
            startActivity(Intent(this, LinkDeviceActivity::class.java))
            return
        }
        if (!initialized) {
            initialized = true
            requestNotificationPermissionIfNeeded()
            requestBatteryExemptionOnce()
        }
        // Outside the initialised check: with idle sleeping on, the service stops while the screen
        // is off and has to come back up here. Starting a running service does nothing.
        ConnectivityService.start(this)
        avatarCache.remove("self")
        loadSelfAvatar()
        load()
        maybeShowStoppedNotice()
    }

    /**
     * Explains late messages when Android stopped NiiX in the background while the phone stayed
     * on. At most once a day, and not with "Disconnect when locked", where stopping is intended.
     */
    private fun maybeShowStoppedNotice() {
        lifecycleScope.launch {
            val gap = withContext(Dispatchers.IO) {
                runCatching {
                    val settings = container.storage.settings
                    val found = container.takeBackgroundStopGap() ?: return@runCatching null
                    if (settings.getBool(SettingsStore.KEY_DISCONNECT_WHEN_LOCKED, false)) return@runCatching null
                    val lastShown = settings.getString(SettingsStore.KEY_STOPPED_NOTICE_AT)?.toLongOrNull() ?: 0L
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastShown < STOPPED_NOTICE_INTERVAL_MILLIS) return@runCatching null
                    settings.setString(SettingsStore.KEY_STOPPED_NOTICE_AT, nowMs.toString())
                    found
                }.getOrNull()
            } ?: return@launch
            if (isFinishing) return@launch
            val minutes = (gap / 60_000).toInt()
            val duration = if (minutes >= 120) {
                resources.getQuantityString(R.plurals.duration_hours, minutes / 60, minutes / 60)
            } else {
                resources.getQuantityString(R.plurals.duration_minutes, minutes, minutes)
            }
            niixDialog(this@HomeActivity)
                .setTitle(R.string.stopped_notice_title)
                .setMessage(getString(R.string.stopped_notice_body, duration))
                .setPositiveButton(R.string.stopped_notice_settings) { _, _ ->
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
                .setNegativeButton(R.string.stopped_notice_dismiss, null)
                .show()
        }
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
                SafeBitmap.decodeBytes(bytes)
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
            // Read on the IO thread and defensively: the database might not be open yet. Defaulting
            // to blurred is the safe choice.
            val blurEnabled = withContext(Dispatchers.IO) {
                runCatching {
                    container.storage.settings.getBool(SettingsStore.KEY_BLUR_MESSAGES, true)
                }.getOrDefault(true)
            }
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
                            previewHidden = blurEnabled && last != null &&
                                last.direction == app.niix.core.model.MessageDirection.INCOMING &&
                                !last.deleted &&
                                !last.revealed,
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
            setTextColor(themeColor(R.attr.niixOnSurface))
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val accept = requestButton(getString(R.string.request_accept), themeColor(R.attr.niixPrimary)) {
            respondToRequest(onAccept)
        }
        val block = requestButton(getString(R.string.request_block), themeColor(R.attr.niixDanger)) {
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
            setTextColor(themeColor(R.attr.niixBg))
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
            val photo = avatarCache[key] ?: container.conversations.profileBytes(key)?.let { bytes ->
                withContext(Dispatchers.Default) { SafeBitmap.decodeBytes(bytes) }
            }
            photo?.let { showPhotoLarge(it) }
        }
    }

    /**
     * Options for a long-pressed conversation, so deleting isn't the only thing the gesture can do.
     */
    private fun showConversationOptions(row: ConversationRow, isGroup: Boolean) {
        val muted = container.storage.settings.getBool(SettingsStore.mutedKey(row.id), false)
        val muteLabel = getString(if (muted) R.string.conversation_unmute else R.string.conversation_mute)
        val options = arrayOf(muteLabel, getString(R.string.action_delete))
        niixDialog(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        container.storage.settings.setBool(SettingsStore.mutedKey(row.id), !muted)
                        Toast.makeText(
                            this,
                            getString(if (muted) R.string.conversation_unmuted else R.string.conversation_muted),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    1 -> confirmDeleteConversation(row, isGroup)
                }
            }
            .show()
    }

    private fun confirmDeleteConversation(row: ConversationRow, isGroup: Boolean) {
        val message = if (isGroup) {
            getString(R.string.delete_conversation_group_message)
        } else {
            getString(R.string.delete_conversation_message, row.title)
        }
        niixDialog(this)
            .setTitle(R.string.delete_conversation_title)
            .setMessage(message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { container.conversations.deleteConversation(row.id) }
                    load()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .showDestructive()
    }

    private fun requestBatteryExemptionOnce() {
        // Runs straight from onResume, where a throw would take the screen down. The database may
        // not be open yet, and a missing prompt is much better than a blank app.
        val alreadyAsked = runCatching {
            container.storage.settings.getBool(app.niix.core.storage.SettingsStore.KEY_BATTERY_ASKED, false)
        }.getOrDefault(true)
        if (alreadyAsked) return
        val power = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            container.storage.settings.setBool(app.niix.core.storage.SettingsStore.KEY_BATTERY_ASKED, true)
        }
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

    private companion object {
        const val TOR_WAIT_MILLIS = 120_000L
        const val UPDATE_RETRY_MILLIS = 60L * 60 * 1000
        const val STOPPED_NOTICE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
    }
}
