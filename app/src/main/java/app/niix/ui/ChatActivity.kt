package app.niix.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import app.niix.core.model.Attachment
import app.niix.core.model.DeliveryState
import app.niix.core.storage.SettingsStore
import android.content.Intent
import java.io.File
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.niix.R
import app.niix.core.model.Message
import app.niix.core.model.MessageDirection
import app.niix.core.model.MessageType
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatActivity : SecureActivity() {

    private lateinit var conversationId: String
    /**
     * Null until we know. A plain false would mean "not a group" before we actually knew, and the
     * trust banner would flash in group chats.
     */
    private var isGroupOrNull: Boolean? = null

    /** Whether this contact is blocked, once known. Decides between Block and Unblock in the menu. */
    private var isBlocked = false

    private val isGroup: Boolean get() = isGroupOrNull == true
    private var cameraOutput: File? = null
    private var recorder: android.media.MediaRecorder? = null
    private var recordFile: File? = null

    private val pickImage = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onPicked(it, "image/*") }
    }
    private val pickVideo = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onPicked(it, "video/*") }
    }
    private val pickFile = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onPicked(it, "application/octet-stream") }
    }
    private val takePhoto = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            cameraOutput?.let { sendFile(it, "image/jpeg", deleteAfter = true) }
        } else {
            // Cancelled, but the camera may have written half a file anyway. It would sit in cache
            // as plaintext until the next lock, so nuke it now.
            runCatching { cameraOutput?.delete() }
        }
        cameraOutput = null
    }
    private val micPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else toast(getString(R.string.perm_mic_needed))
    }
    private val cameraPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCameraInternal() else toast(getString(R.string.perm_camera_needed))
    }
    private lateinit var adapter: MessageAdapter
    private lateinit var list: RecyclerView
    private lateinit var searchBar: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var searchCount: TextView
    private var searchMatchPositions: List<Int> = emptyList()
    private var searchMatchIndex: Int = -1
    private lateinit var inputKeyboardController: NiixKeyboardController
    private val contactDialogs by lazy { ContactDialogs(this, container.conversations) }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            if (::inputKeyboardController.isInitialized) inputKeyboardController.reassertOnWindowFocus()
            contactDialogs.reassertKeyboard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conversationId = intent.getStringExtra(EXTRA_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (conversationId.isEmpty()) { finish(); return }

        setContentView(R.layout.activity_chat)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = title
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnClickListener { openDetails() }

        wireReplyCancel()
        adapter = MessageAdapter(
            onLongClick = { showMessageActions(it) },
            attachmentOf = { container.conversations.attachment(it) },
            bindImage = { imageView, id -> bindAttachmentImage(imageView, id) },
            onOpenAttachment = { _, att -> openAttachment(att) },
        ).apply {
            blurEnabled = container.storage.settings.getBool(SettingsStore.KEY_BLUR_MESSAGES, true)
            onReveal = { message ->
                // The timer starts here rather than when the chat opened: with blurring on, being
                // on screen isn't the same as being read. markMessageRead also stores the revealed
                // flag, so it survives a restart.
                lifecycleScope.launch {
                    // No load() here: the adapter already updated the row, and a reload would jump
                    // to the bottom. This just stores the flag.
                    runCatching { container.conversations.markMessageRead(message.id) }
                }
            }
        }
        list = findViewById(R.id.message_list)
        PullToRefresh.attach(this, findViewById(R.id.chat_refresh), container) { load() }
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter

        setUpSearchBar()

        val input = findViewById<EditText>(R.id.input)
        val keyboardPanel = findViewById<LinearLayout>(R.id.keyboard_panel)
        inputKeyboardController = NiixKeyboardController(this, keyboardPanel).apply { attach(input) }

        findViewById<ImageButton>(R.id.send).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                input.text.clear()
                send(text)
            }
        }

        findViewById<ImageButton>(R.id.attach).setOnClickListener { showAttachMenu() }

        lifecycleScope.launch {
            isGroupOrNull = withContext(Dispatchers.IO) { container.conversations.groupMembers(conversationId).isNotEmpty() }
            invalidateOptionsMenu()
            // onResume ran before the type was known, so the header waited for it.
            updateHeader()
            if (isGroup) {
                // Resolve names once and cache them; bind runs on every scroll and a database read
                // there would make the list stutter. Uses the same displayName lookup as group
                // info, keyed case-insensitively, because contact addresses and message senders
                // aren't guaranteed to be written identically.
                val names = withContext(Dispatchers.IO) {
                    runCatching {
                        container.conversations.groupMembers(conversationId).associate { member ->
                            val onion = member.memberOnion.value
                            onion.lowercase() to container.conversations.displayName(onion)
                        }
                    }.getOrDefault(emptyMap())
                }
                adapter.senderNameOf = { onion ->
                    // Fall back to a short address. A member who isn't a contact still needs to be
                    // told apart, and an ugly label beats a blank one every damn time.
                    names[onion.lowercase()] ?: onion.take(8)
                }
                load()
            }
        }

        lifecycleScope.launch {
            // One reload per burst: each change restarts a short wait and only the last one
            // reloads. 100 ms isn't noticeable.
            container.conversations.changes.collectLatest {
                kotlinx.coroutines.delay(100)
                load()
            }
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        edgeSwipeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private val edgeSwipeDetector by lazy {
        val edgeZonePx = dp(24)
        val minTravelPx = dp(80)
        val minVelocityPx = dp(400)
        android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: android.view.MotionEvent?,
                    e2: android.view.MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    val start = e1 ?: return false
                    val dx = e2.x - start.x
                    val dy = e2.y - start.y
                    val startedAtLeftEdge = start.x <= edgeZonePx
                    val swipedRightFarEnough = dx > minTravelPx
                    val mostlyHorizontal = kotlin.math.abs(dy) < minTravelPx
                    val fastEnough = kotlin.math.abs(velocityX) > minVelocityPx
                    if (startedAtLeftEdge && swipedRightFarEnough && mostlyHorizontal && fastEnough) {
                        if (::searchBar.isInitialized && searchBar.visibility == android.view.View.VISIBLE) {
                            closeSearch()
                        } else {
                            finish()
                        }
                        return true
                    }
                    return false
                }
            },
        )
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Hidden until we know the conversation type, so the menu doesn't change under the user or
        // offer a verify action that can't work in a group.
        val group = isGroupOrNull
        menu.findItem(R.id.action_group_info)?.isVisible = group == true
        // A safety number is between two people, so there's nothing to verify for a group as a
        // whole. Per-member verification is in group info.
        menu.findItem(R.id.action_verify)?.isVisible = group == false
        // Blocking is of a person; a group is left from group info instead.
        menu.findItem(R.id.action_block)?.apply {
            isVisible = group == false
            setTitle(if (isBlocked) R.string.menu_unblock else R.string.menu_block)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    private fun setUpSearchBar() {
        searchBar = findViewById(R.id.search_bar)
        searchField = findViewById(R.id.search_field)
        searchCount = findViewById(R.id.search_count)
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = performSearch(s?.toString().orEmpty())
        })
        findViewById<ImageButton>(R.id.search_prev).setOnClickListener { stepMatch(-1) }
        findViewById<ImageButton>(R.id.search_next).setOnClickListener { stepMatch(1) }
        findViewById<ImageButton>(R.id.search_close).setOnClickListener { closeSearch() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (searchBar.visibility == android.view.View.VISIBLE) {
                    closeSearch()
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    private fun openSearch() {
        searchBar.visibility = android.view.View.VISIBLE
        searchField.requestFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(searchField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        performSearch(searchField.text.toString())
    }

    private fun closeSearch() {
        searchBar.visibility = android.view.View.GONE
        searchField.text.clear()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(searchField.windowToken, 0)
        searchMatchPositions = emptyList()
        searchMatchIndex = -1
        adapter.setHighlighted(null)
    }

    private fun performSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            searchMatchPositions = emptyList()
            searchMatchIndex = -1
            searchCount.text = "0/0"
            adapter.setHighlighted(null)
            return
        }
        searchMatchPositions = adapter.currentItems().withIndex()
            .filter { (_, m) -> !m.deleted && m.type == MessageType.TEXT && m.body.contains(q, ignoreCase = true) }
            .map { it.index }
        searchMatchIndex = if (searchMatchPositions.isEmpty()) -1 else searchMatchPositions.lastIndex
        updateSearchCount()
        if (searchMatchIndex >= 0) goToMatch() else adapter.setHighlighted(null)
    }

    private fun stepMatch(delta: Int) {
        if (searchMatchPositions.isEmpty()) return
        searchMatchIndex = (searchMatchIndex + delta + searchMatchPositions.size) % searchMatchPositions.size
        updateSearchCount()
        goToMatch()
    }

    private fun updateSearchCount() {
        searchCount.text = if (searchMatchPositions.isEmpty()) "0/0" else "${searchMatchIndex + 1}/${searchMatchPositions.size}"
    }

    private fun goToMatch() {
        val pos = searchMatchPositions.getOrNull(searchMatchIndex) ?: return
        val message = adapter.currentItems().getOrNull(pos) ?: return
        adapter.setHighlighted(message.id)
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, list.height / 3)
    }

    private fun showAttachMenu() {
        val options = arrayOf(
            getString(R.string.attach_camera),
            getString(R.string.attach_photo),
            getString(R.string.attach_video),
            getString(R.string.attach_audio),
            getString(R.string.attach_file),
        )
        niixDialog(this)
            .setTitle(R.string.attach_choose)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchCamera()
                    1 -> pickImage.launch("image/*")
                    2 -> pickVideo.launch("video/*")
                    3 -> requestAudio()
                    else -> pickFile.launch("*/*")
                }
            }
            .show()
    }

    private fun launchCamera() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            launchCameraInternal()
        } else {
            cameraPermission.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun launchCameraInternal() {
        val sharedDir = File(cacheDir, "shared").apply { mkdirs() }
        val file = File(sharedDir, "cam_${System.currentTimeMillis()}.jpg")
        cameraOutput = file
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        runCatching { takePhoto.launch(uri) }.onFailure { toast(it.message ?: "") }
    }

    private fun requestAudio() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        val file = File(cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        recordFile = file
        val rec = if (android.os.Build.VERSION.SDK_INT >= 31) {
            android.media.MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION") android.media.MediaRecorder()
        }
        rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
        rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
        rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
        rec.setOutputFile(file.absolutePath)
        try {
            rec.prepare()
            rec.start()
        } catch (e: Exception) {
            toast(getString(R.string.toast_failed, e.message.orEmpty()))
            rec.release()
            return
        }
        recorder = rec
        niixDialog(this)
            .setTitle(R.string.audio_recording)
            .setCancelable(false)
            .setPositiveButton(R.string.audio_stop_send) { _, _ -> stopRecording(true) }
            .setNegativeButton(R.string.audio_cancel) { _, _ -> stopRecording(false) }
            .show()
    }

    private fun stopRecording(send: Boolean) {
        val rec = recorder ?: return
        recorder = null
        runCatching { rec.stop() }
        rec.release()
        val file = recordFile
        recordFile = null
        if (send && file != null && file.length() > 0) sendFile(file, "audio/mp4", deleteAfter = true)
        else file?.delete()
    }

    private fun onPicked(uri: android.net.Uri, fallbackMime: String) {
        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                val mime = contentResolver.getType(uri) ?: fallbackMime
                val temp = File(cacheDir, "att_${System.currentTimeMillis()}")
                runCatching {
                    val ok = contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { input.copyTo(it) }; true
                    } ?: false
                    if (ok) Pair(temp, mime) else null
                }.getOrElse { null }.also { result ->
                    // A failed copy leaves plaintext in cache until the next lock. The file is
                    // declared outside runCatching so we can still delete it here.
                    if (result == null) runCatching { temp.delete() }
                }
            }
            if (prepared == null) {
                toast(getString(R.string.toast_failed, ""))
                return@launch
            }
            sendFile(prepared.first, prepared.second, deleteAfter = true)
        }
    }

    private fun sendFile(file: File, mime: String, deleteAfter: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { container.conversations.sendAttachment(conversationId, file, mime) }
                if (deleteAfter) file.delete()
            }
            load()
        }
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        load()

        lifecycleScope.launch { withContext(Dispatchers.IO) { container.conversations.markConversationRead(conversationId) } }
        updateHeader()
        // Connect to the other side now, so what's typed next goes out without waiting for Tor.
        container.conversations.keepConversationWarm(conversationId, true)
    }

    override fun onPause() {
        container.conversations.keepConversationWarm(conversationId, false)
        super.onPause()
    }

    /**
     * The name, blocked state and unverified-identity warning of a direct chat, read again on every
     * resume because the contact's profile page can change any of them. No warning in a group:
     * there's a safety number per member and no single answer, so it would just read as "this group
     * is insecure". Does nothing until the conversation type is known.
     */
    private fun updateHeader() {
        val group = isGroupOrNull ?: return
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        if (group) {
            toolbar.subtitle = null
            return
        }
        lifecycleScope.launch {
            val conversations = container.conversations
            val trusted = conversations.trustState(conversationId) == app.niix.core.model.TrustState.VERIFIED
            conversations.conversation(conversationId)?.title?.let { toolbar.title = it }
            toolbar.subtitle = if (trusted) null else getString(R.string.unverified_indicator)
            val blocked = conversations.isBlocked(conversationId)
            if (blocked != isBlocked) {
                isBlocked = blocked
                invalidateOptionsMenu()
            }
        }
    }

    /** The contact's profile in a direct chat, the group's details in a group. */
    private fun openDetails() {
        val group = isGroupOrNull ?: return
        startActivity(
            if (group) {
                Intent(this, GroupInfoActivity::class.java).putExtra(GroupInfoActivity.EXTRA_ID, conversationId)
            } else {
                Intent(this, ProfileActivity::class.java)
                    .putExtra(ProfileActivity.EXTRA_CONTACT, conversationId)
                    .putExtra(ProfileActivity.EXTRA_FROM_CHAT, true)
            },
        )
    }

    private fun load() {
        lifecycleScope.launch {
            val messages = withContext(Dispatchers.IO) {
                container.conversations.messagesFor(conversationId)
            }
            adapter.submit(messages)
            if (::searchBar.isInitialized && searchBar.visibility == android.view.View.VISIBLE) {
                performSearch(searchField.text.toString())
            } else if (messages.isNotEmpty()) {
                list.scrollToPosition(messages.size - 1)
            }
        }
    }

    /**
     * The message the next send replies to, or null. Cleared after sending so replying is always
     * deliberate, not a mode you forget you're in.
     */
    private var pendingReplyTo: Message? = null

    private fun wireReplyCancel() {
        findViewById<View>(R.id.reply_cancel).setOnClickListener { cancelReply() }
    }

    private fun copyMessage(message: Message) {
        val clip = android.content.ClipData.newPlainText("", message.body)
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(clip)
        // Android 13+ shows its own copy confirmation; older versions show nothing unless we do.
        if (android.os.Build.VERSION.SDK_INT < 33) {
            toast(getString(R.string.message_copied))
        }
    }

    private fun beginReply(message: Message) {
        pendingReplyTo = message
        val banner = findViewById<TextView>(R.id.reply_banner)
        banner.visibility = View.VISIBLE
        banner.text = getString(
            R.string.reply_banner,
            when {
                message.deleted -> getString(R.string.message_deleted)
                message.type == MessageType.ATTACHMENT -> getString(R.string.attachment_label)
                else -> message.body.take(60)
            },
        )
        findViewById<View>(R.id.reply_cancel).visibility = View.VISIBLE
    }

    private fun cancelReply() {
        pendingReplyTo = null
        findViewById<TextView>(R.id.reply_banner).visibility = View.GONE
        findViewById<View>(R.id.reply_cancel).visibility = View.GONE
    }

    private fun send(text: String) {
        val replyTo = pendingReplyTo?.id
        cancelReply()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { container.conversations.sendText(conversationId, text, replyTo) }
            }
            load()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun blockContact() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { container.conversations.block(conversationId) } }
            Toast.makeText(this@ChatActivity, getString(R.string.toast_blocked), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun unblockContact() {
        lifecycleScope.launch {
            runCatching { container.conversations.unblock(conversationId) }
            isBlocked = false
            invalidateOptionsMenu()
            Toast.makeText(this@ChatActivity, getString(R.string.toast_unblocked), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.chat_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_search -> { openSearch(); true }
        R.id.action_group_info -> {
            startActivity(Intent(this, GroupInfoActivity::class.java).putExtra(GroupInfoActivity.EXTRA_ID, conversationId))
            true
        }
        R.id.action_rename -> {
            val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
            contactDialogs.rename(conversationId, toolbar.title?.toString().orEmpty()) { toolbar.title = it }
            true
        }
        R.id.action_verify -> { contactDialogs.showSafetyNumber(conversationId) { updateHeader() }; true }
        R.id.action_disappearing -> { showTimerDialog(); true }
        R.id.action_block -> { if (isBlocked) unblockContact() else blockContact(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showMessageActions(message: Message) {
        val options = mutableListOf(getString(R.string.action_reply))
        // Only offer Copy when there's text; for an attachment or deleted message it would copy an
        // empty string.
        if (!message.deleted && message.type == MessageType.TEXT && message.body.isNotBlank()) {
            options.add(getString(R.string.action_copy))
        }
        if (message.direction == MessageDirection.OUTGOING && message.deliveryState == DeliveryState.FAILED && !message.deleted) {
            options.add(getString(R.string.action_send_again))
        }
        options.add(getString(R.string.delete_for_me))
        val canDeleteEveryone = message.direction == MessageDirection.OUTGOING &&
            message.remoteDeletable && !message.deleted
        if (canDeleteEveryone) options.add(getString(R.string.delete_for_everyone))
        niixDialog(this)
            // No title, the options speak for themselves.
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    getString(R.string.action_reply) -> beginReply(message)
                    getString(R.string.action_copy) -> copyMessage(message)
                    getString(R.string.action_send_again) -> lifecycleScope.launch { container.conversations.sendAgain(message.id) }
                    getString(R.string.delete_for_me) -> runDelete(false, message.id)
                    getString(R.string.delete_for_everyone) -> runDelete(true, message.id)
                }
            }
            .show()
    }

    private fun runDelete(forEveryone: Boolean, messageId: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (forEveryone) container.conversations.deleteForEveryone(conversationId, messageId)
                    else container.conversations.deleteForMe(messageId)
                }
            }
            load()
            Toast.makeText(this@ChatActivity, getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTimerDialog() {
        val labels = arrayOf(
            getString(R.string.timer_off),
            getString(R.string.timer_5min),
            getString(R.string.timer_1hour),
            getString(R.string.timer_1day),
        )
        val seconds = longArrayOf(0L, 5L * 60, 60L * 60, 24L * 60 * 60)
        // What it doesn't do, below the choices: people tend to assume it stops the other person
        // keeping a copy, which no app can. setView, since setMessage would replace the list.
        val note = TextView(this).apply {
            setText(R.string.timer_limits_note)
            textSize = 13f
            setTextColor(themeColor(R.attr.niixOnSurfaceMuted))
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        niixDialog(this)
            .setTitle(R.string.timer_title)
            .setView(note)
            .setItems(labels) { _, which ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { container.conversations.setDisappearTimer(conversationId, seconds[which]) }
                    }
                    Toast.makeText(this@ChatActivity, getString(R.string.toast_timer_set), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun bindAttachmentImage(imageView: ImageView, attachmentId: String) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { container.conversations.attachmentBytes(attachmentId) } ?: return@launch
            if (imageView.tag != attachmentId) return@launch
            val bmp = withContext(Dispatchers.Default) {
                SafeBitmap.decodeBytes(bytes)
            }
            if (imageView.tag == attachmentId && bmp != null) imageView.setImageBitmap(bmp)
        }
    }

    private fun openAttachment(att: Attachment) {
        lifecycleScope.launch {
            val sharedDir = File(cacheDir, "shared").apply { mkdirs() }
            val dest = File(sharedDir, "open_${att.id}${extensionFor(att.mimeType)}")
            val ok = withContext(Dispatchers.IO) { container.conversations.decryptAttachmentTo(att.id, dest) }
            if (!ok) {
                toast(getString(R.string.attachment_download_failed))
                return@launch
            }
            val mime = att.mimeType
            if (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")) {
                startActivity(
                    Intent(this@ChatActivity, MediaViewerActivity::class.java)
                        .putExtra(MediaViewerActivity.EXTRA_PATH, dest.absolutePath)
                        .putExtra(MediaViewerActivity.EXTRA_MIME, mime),
                )
            } else {
                // Anything that isn't media gets saved instead of opened. The decrypted file lives
                // in our cache, which is cleared whenever the app locks, so another app opening it
                // could lose access at any moment. Saving to Downloads gives the user a real file.
                confirmSaveToDownloads(att, dest)
            }
        }
    }

    /**
     * Asks before writing a decrypted file outside the app.
     *
     * This is the one action that leaves a plaintext copy somewhere we don't control: the duress
     * wipe can't reach it, other apps can read it, and it outlives uninstalling NiiX. Fine to want,
     * but it should be a choice, not a side effect of tapping a file.
     */
    private fun confirmSaveToDownloads(att: Attachment, decrypted: File) {
        // The sender's file name is never transmitted, so build one from the attachment id and
        // type. Predictable, and it can't leak anything about the sender's files.
        val name = "niix-${att.id.take(8)}${extensionFor(att.mimeType)}"
        niixDialog(this)
            .setTitle(R.string.attachment_save_title)
            .setMessage(getString(R.string.attachment_save_warning, name))
            .setPositiveButton(R.string.attachment_save_confirm) { _, _ ->
                lifecycleScope.launch {
                    val saved = withContext(Dispatchers.IO) { saveToDownloads(decrypted, name, att.mimeType) }
                    if (saved) {
                        toast(getString(R.string.attachment_saved, name))
                    } else {
                        toast(getString(R.string.attachment_save_failed))
                    }
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun saveToDownloads(source: File, displayName: String, mime: String): Boolean = runCatching {
        run {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: return@runCatching false
            // Only cleared once the copy is complete, so other apps never see a half-written file.
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }
    }.getOrDefault(false)

    private fun extensionFor(mime: String): String {
        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        return if (!ext.isNullOrEmpty()) ".$ext" else ""
    }

    companion object {
        const val EXTRA_ID = "conversation_id"
        const val EXTRA_TITLE = "conversation_title"
    }
}
