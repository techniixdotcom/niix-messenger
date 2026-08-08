package app.niix.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import app.niix.core.model.Attachment
import android.content.Intent
import java.io.File
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.niix.QrCodes
import app.niix.R
import app.niix.core.crypto.SafetyNumber
import app.niix.core.model.Message
import app.niix.core.model.MessageDirection
import app.niix.core.model.MessageType
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatActivity : SecureActivity() {

    private lateinit var conversationId: String
    private var isGroup = false
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
        if (ok) cameraOutput?.let { sendFile(it, "image/jpeg", deleteAfter = true) }
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
    private var renameKeyboardController: NiixKeyboardController? = null

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            if (::inputKeyboardController.isInitialized) inputKeyboardController.reassertOnWindowFocus()
            renameKeyboardController?.reassertOnWindowFocus()
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

        adapter = MessageAdapter(
            onLongClick = { showMessageActions(it) },
            attachmentOf = { container.conversations.attachment(it) },
            bindImage = { imageView, id -> bindAttachmentImage(imageView, id) },
            onOpenAttachment = { _, att -> openAttachment(att) },
        )
        list = findViewById(R.id.message_list)
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
            isGroup = withContext(Dispatchers.IO) { container.conversations.groupMembers(conversationId).isNotEmpty() }
            invalidateOptionsMenu()
        }

        lifecycleScope.launch {
            container.conversations.changes.collect { load() }
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        edgeSwipeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Lets a swipe starting from the very left edge of the screen close the chat and return to
     * the contacts list, in addition to the toolbar back arrow -- without this, on devices using
     * classic 3-button navigation (no system back gesture) there was no swipe-based way back.
     */
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
        menu.findItem(R.id.action_group_info)?.isVisible = isGroup
        return super.onPrepareOptionsMenu(menu)
    }

    /** Wires up the in-chat "find in this chat" bar (opened from the toolbar overflow menu):
     * typing highlights and jumps straight to the matching message instead of just filtering a
     * list, and prev/next step through every match in the conversation. */
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
        AlertDialog.Builder(this)
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
        val file = File(cacheDir, "cam_${System.currentTimeMillis()}.jpg")
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
            toast(e.message ?: "record error")
            rec.release()
            return
        }
        recorder = rec
        AlertDialog.Builder(this)
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
                runCatching {
                    val mime = contentResolver.getType(uri) ?: fallbackMime
                    val temp = File(cacheDir, "att_${System.currentTimeMillis()}")
                    val ok = contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { input.copyTo(it) }; true
                    } ?: false
                    if (ok) Pair(temp, mime) else null
                }.getOrNull()
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
        // Starts the disappearing-message countdown (and lets the sender know) for anything
        // unread in this conversation -- see ConversationManager.markConversationRead().
        lifecycleScope.launch { withContext(Dispatchers.IO) { container.conversations.markConversationRead(conversationId) } }
    }

    private fun load() {
        lifecycleScope.launch {
            val messages = withContext(Dispatchers.IO) {
                container.conversations.messagesFor(conversationId)
            }
            adapter.submit(messages)
            if (::searchBar.isInitialized && searchBar.visibility == android.view.View.VISIBLE) {
                // Keep results in sync as new messages arrive instead of yanking the scroll
                // position back to the bottom while someone is mid-search.
                performSearch(searchField.text.toString())
            } else if (messages.isNotEmpty()) {
                list.scrollToPosition(messages.size - 1)
            }
        }
    }

    private fun send(text: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { container.conversations.sendText(conversationId, text) }
            }
            load()
        }
    }

    private fun renameDialog() {
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)

        val field = NiixEditField.create(this, getString(R.string.hint_contact_name)).apply {
            setText(toolbar.title?.toString().orEmpty())
            setSelection(text.length)
        }
        val keyboardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(resources.getColor(R.color.niix_surface, theme))
            setPadding(dp(4), dp(6), dp(4), dp(10))
        }
        val saveButton = com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.dialog_save)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(resources.getColor(R.color.niix_surface, theme))
            setPadding(dp(20), dp(20), dp(20), dp(10))
            addView(
                TextView(this@ChatActivity).apply {
                    text = getString(R.string.menu_rename)
                    setTextColor(resources.getColor(R.color.niix_on_surface, theme))
                    textSize = 17f
                    setPadding(0, 0, 0, dp(14))
                },
            )
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                saveButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                    it.topMargin = dp(14)
                },
            )
            addView(keyboardPanel)
        }

        renameKeyboardController = NiixKeyboardController(this, keyboardPanel).apply { attach(field) }

        saveButton.setOnClickListener {
            val newName = field.text.toString().trim()
            if (newName.isNotEmpty()) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { container.conversations.renameContact(conversationId, newName) }
                    }
                    toolbar.title = newName
                }
            }
            sheet.dismiss()
        }

        sheet.setContentView(content)
        sheet.setOnDismissListener { renameKeyboardController = null }
        sheet.show()
        field.requestFocus()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showSafetyNumber() {
        lifecycleScope.launch {
            val number = withContext(Dispatchers.IO) {
                runCatching { container.conversations.safetyNumber(conversationId) }.getOrNull()
            }
            if (number == null) {
                Toast.makeText(this@ChatActivity, getString(R.string.toast_no_session), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val text = TextView(this@ChatActivity).apply {
                setPadding(48, 32, 48, 16)
                textSize = 16f
                setTextIsSelectable(true)
                text = SafetyNumber.formatted(number)
            }
            val image = ImageView(this@ChatActivity).apply {
                QrCodes.encode(number, 480)?.let { setImageBitmap(it) }
            }
            val column = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(32, 24, 32, 8)
                addView(text)
                addView(image)
            }
            AlertDialog.Builder(this@ChatActivity)
                .setTitle(R.string.action_safety_number)
                .setMessage(R.string.safety_number_explainer)
                .setView(column)
                .setPositiveButton(R.string.action_mark_verified) { _, _ -> markVerified() }
                .setNegativeButton(R.string.dialog_close, null)
                .show()
        }
    }

    private fun markVerified() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { container.conversations.markVerified(conversationId) } }
            Toast.makeText(this@ChatActivity, getString(R.string.toast_verified), Toast.LENGTH_SHORT).show()
        }
    }

    private fun blockContact() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { container.conversations.block(conversationId) } }
            Toast.makeText(this@ChatActivity, getString(R.string.toast_blocked), Toast.LENGTH_SHORT).show()
            finish()
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
        R.id.action_rename -> { renameDialog(); true }
        R.id.action_verify -> { showSafetyNumber(); true }
        R.id.action_disappearing -> { showTimerDialog(); true }
        R.id.action_block -> { blockContact(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showMessageActions(message: Message) {
        val options = mutableListOf(getString(R.string.delete_for_me))
        val canDeleteEveryone = message.direction == MessageDirection.OUTGOING &&
            message.remoteDeletable && !message.deleted
        if (canDeleteEveryone) options.add(getString(R.string.delete_for_everyone))
        AlertDialog.Builder(this)
            .setTitle(R.string.message_actions_title)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
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
        AlertDialog.Builder(this)
            .setTitle(R.string.timer_title)
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
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            if (imageView.tag == attachmentId && bmp != null) imageView.setImageBitmap(bmp)
        }
    }

    private fun openAttachment(att: Attachment) {
        lifecycleScope.launch {
            val dest = File(cacheDir, "open_${att.id}${extensionFor(att.mimeType)}")
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
                val uri = androidx.core.content.FileProvider.getUriForFile(this@ChatActivity, "$packageName.fileprovider", dest)
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { startActivity(view) }.onFailure { toast(getString(R.string.toast_failed, "")) }
            }
        }
    }

    private fun extensionFor(mime: String): String {
        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        return if (!ext.isNullOrEmpty()) ".$ext" else ""
    }

    companion object {
        const val EXTRA_ID = "conversation_id"
        const val EXTRA_TITLE = "conversation_title"
    }
}
