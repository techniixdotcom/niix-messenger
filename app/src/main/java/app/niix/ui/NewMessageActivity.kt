package app.niix.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import app.niix.R
import app.niix.core.model.ConversationType
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything new from the + button on the main screen: a contact by code, a contact by QR code, or
 * a group. One screen with tabs, so starting something always looks the same.
 */
class NewMessageActivity : SecureActivity() {

    private data class ContactEntry(val onion: String, val title: String)

    private lateinit var contactNameField: EditText
    private lateinit var shareCodeField: EditText
    private lateinit var groupNameField: EditText
    private lateinit var createButton: MaterialButton
    private lateinit var keyboardController: NiixKeyboardController
    private val memberBoxes = mutableListOf<CheckBox>()
    private var contactsLoaded = false
    private var creating = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::keyboardController.isInitialized) keyboardController.reassertOnWindowFocus()
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { code ->
            shareCodeField.setText(code)
            shareCodeField.setSelection(shareCodeField.text.length)
            selectTab(TAB_CODE)
            Toast.makeText(this, getString(R.string.scan_captured), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_message)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val controller = NiixKeyboardController(this, findViewById<LinearLayout>(R.id.keyboard_panel))
        keyboardController = controller

        contactNameField = NiixEditField.create(this, getString(R.string.hint_contact_name))
        findViewById<FrameLayout>(R.id.contact_name_container).addView(
            contactNameField,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        controller.attach(contactNameField)

        shareCodeField = NiixEditField.create(this, getString(R.string.hint_share_code), multiline = true)
        val shareCodeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        shareCodeRow.addView(
            shareCodeField,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val pasteButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            background = null
            contentDescription = getString(R.string.action_paste)
            imageTintList = android.content.res.ColorStateList.valueOf(themeColor(R.attr.niixPrimary))
            setOnClickListener { pasteInto(shareCodeField) }
        }
        shareCodeRow.addView(pasteButton, LinearLayout.LayoutParams(dp(40), dp(40)).also { it.marginStart = dp(8) })
        findViewById<FrameLayout>(R.id.share_code_container).addView(
            shareCodeRow,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        controller.attach(shareCodeField)

        groupNameField = NiixEditField.create(this, getString(R.string.hint_group_name))
        findViewById<FrameLayout>(R.id.group_name_container).addView(
            groupNameField,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        controller.attach(groupNameField)
        createButton = findViewById<MaterialButton>(R.id.create_button).apply { setOnClickListener { createGroup() } }

        val tabs = findViewById<TabLayout>(R.id.tabs)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPanel(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        findViewById<MaterialButton>(R.id.add_button).setOnClickListener { add() }
        findViewById<MaterialButton>(R.id.scan_button).setOnClickListener { launchScanner() }
        findViewById<MaterialButton>(R.id.my_code_button).setOnClickListener {
            startActivity(Intent(this, MyCodeActivity::class.java))
        }

        if (intent.getIntExtra(EXTRA_TAB, TAB_CODE) == TAB_GROUP) selectTab(TAB_GROUP)
    }

    private fun pasteInto(field: EditText) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = manager?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val pasted = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
            if (pasted.isNotEmpty()) {
                field.setText(pasted)
                field.setSelection(field.text.length)
            }
        }
    }

    private fun selectTab(index: Int) {
        findViewById<TabLayout>(R.id.tabs).getTabAt(index)?.select()
        showPanel(index)
    }

    private fun showPanel(index: Int) {
        findViewById<LinearLayout>(R.id.panel_code).visibility = if (index == TAB_CODE) View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.panel_scan).visibility = if (index == TAB_SCAN) View.VISIBLE else View.GONE
        val group = index == TAB_GROUP
        findViewById<LinearLayout>(R.id.panel_group).visibility = if (group) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.my_code_button).visibility = if (group) View.GONE else View.VISIBLE
        createButton.visibility = if (group) View.VISIBLE else View.GONE
        findViewById<MaterialToolbar>(R.id.toolbar).setTitle(if (group) R.string.create_group_title else R.string.new_message_title)
        if (group) loadContacts()
    }

    /** The people who can be put in a group: direct chats that aren't unanswered requests. */
    private fun loadContacts() {
        if (contactsLoaded) return
        contactsLoaded = true
        lifecycleScope.launch {
            val contacts = withContext(Dispatchers.IO) {
                runCatching {
                    container.conversations.listConversations()
                        .filter { it.type == ConversationType.DIRECT && !it.pending }
                        .map { ContactEntry(it.id, it.title) }
                }.getOrDefault(emptyList())
            }
            val memberContainer = findViewById<LinearLayout>(R.id.member_container)
            memberContainer.removeAllViews()
            memberBoxes.clear()
            if (contacts.isEmpty()) {
                memberContainer.addView(
                    TextView(this@NewMessageActivity).apply {
                        text = getString(R.string.create_group_no_contacts)
                        setTextColor(themeColor(R.attr.niixOnSurfaceMuted))
                        gravity = Gravity.CENTER
                        setPadding(0, dp(24), 0, 0)
                    },
                )
                return@launch
            }
            for (contact in contacts) {
                val box = CheckBox(this@NewMessageActivity).apply {
                    text = contact.title
                    tag = contact.onion
                    setTextColor(themeColor(R.attr.niixOnSurface))
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                }
                memberBoxes.add(box)
                memberContainer.addView(box)
            }
        }
    }

    private fun createGroup() {
        if (creating) return
        val name = groupNameField.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, getString(R.string.create_group_need_name), Toast.LENGTH_SHORT).show()
            return
        }
        val members = memberBoxes.filter { it.isChecked }.map { it.tag as String }
        if (members.isEmpty()) {
            Toast.makeText(this, getString(R.string.create_group_need_members), Toast.LENGTH_SHORT).show()
            return
        }
        creating = true
        createButton.isEnabled = false
        createButton.text = getString(R.string.create_group_creating)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { container.conversations.createGroup(name, members) }
            }
            if (result.isSuccess) {
                Toast.makeText(this@NewMessageActivity, getString(R.string.create_group_done), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                creating = false
                createButton.isEnabled = true
                createButton.text = getString(R.string.action_create_group)
                Toast.makeText(
                    this@NewMessageActivity,
                    getString(R.string.toast_failed, result.exceptionOrNull()?.message ?: ""),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun launchScanner() {
        scanLauncher.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(getString(R.string.scan_prompt))
                setBeepEnabled(false)
                setOrientationLocked(true)
                setCaptureActivity(PortraitCaptureActivity::class.java)
            },
        )
    }

    private fun add() {
        val name = contactNameField.text.toString().trim()
        val code = shareCodeField.text.toString().trim()
        if (code.isEmpty()) {
            Toast.makeText(this, getString(R.string.add_contact_need_code), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val parts = code.split("|", limit = 2)
                    require(parts.size == 2) { "Malformed share code" }
                    val onion = parts[0].trim()
                    val identityKey = Base64.decode(parts[1].trim(), Base64.NO_WRAP)
                    container.conversations.addContactByCode(onion, identityKey, name.ifEmpty { onion.take(8) })
                }
            }
            if (result.isSuccess) {
                Toast.makeText(this@NewMessageActivity, getString(R.string.toast_contact_added), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this@NewMessageActivity, getString(R.string.toast_failed, result.exceptionOrNull()?.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** Which tab to open on: [TAB_CODE] (the default) or [TAB_GROUP]. */
        const val EXTRA_TAB = "tab"
        const val TAB_CODE = 0
        const val TAB_SCAN = 1
        const val TAB_GROUP = 2
    }
}
