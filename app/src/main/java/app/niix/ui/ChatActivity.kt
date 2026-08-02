package app.niix.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

        val input = findViewById<TextView>(R.id.input)
        val keyboardPanel = findViewById<LinearLayout>(R.id.keyboard_panel)
        val composerText = StringBuilder()

        fun refreshComposer() {
            if (composerText.isEmpty()) {
                input.text = getString(R.string.chat_hint)
                input.setTextColor(resources.getColor(R.color.niix_on_surface_muted, theme))
            } else {
                input.text = composerText.toString()
                input.setTextColor(resources.getColor(R.color.niix_on_surface, theme))
            }
        }
        refreshComposer()

        val keyboard = NiixKeyboard(
            context = this,
            container = keyboardPanel,
            onKey = { key -> composerText.append(key); refreshComposer() },
            onBackspace = {
                if (composerText.isNotEmpty()) {
                    composerText.deleteCharAt(composerText.length - 1)
                    refreshComposer()
                }
            },
            onDone = { keyboardPanel.visibility = android.view.View.GONE },
        )
        keyboard.render()

        input.setOnClickListener {
            keyboardPanel.visibility =
                if (keyboardPanel.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
        }

        findViewById<ImageButton>(R.id.send).setOnClickListener {
            val text = composerText.toString().trim()
            if (text.isNotEmpty()) {
                composerText.clear()
                refreshComposer()
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

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_group_info)?.isVisible = isGroup
        return super.onPrepareOptionsMenu(menu)
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
    }

    private fun load() {
        lifecycleScope.launch {
            val messages = withContext(Dispatchers.IO) {
                container.conversations.messagesFor(conversationId)
            }
            adapter.submit(messages)
            if (messages.isNotEmpty()) list.scrollToPosition(messages.size - 1)
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
        val field = NiixTextEntry(this, getString(R.string.menu_rename)).apply {
            text = toolbar.title?.toString().orEmpty()
        }
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_rename)
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val newName = field.text.trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { container.conversations.renameContact(conversationId, newName) }
                        }
                        toolbar.title = newName
                    }
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

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
