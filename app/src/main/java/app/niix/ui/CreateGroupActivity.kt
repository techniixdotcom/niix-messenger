package app.niix.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import app.niix.R
import app.niix.core.model.ConversationType
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CreateGroupActivity : SecureActivity() {

    private data class ContactEntry(val onion: String, val title: String)

    private val boxes = mutableListOf<CheckBox>()
    private lateinit var groupNameField: EditText
    private lateinit var createButton: MaterialButton
    private var creating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_group)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        createButton = findViewById<MaterialButton>(R.id.create_button).apply { setOnClickListener { create() } }
        groupNameField = NiixEditField.create(this, getString(R.string.hint_group_name))
        findViewById<FrameLayout>(R.id.group_name_container).addView(
            groupNameField,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        NiixKeyboardController(this, findViewById<LinearLayout>(R.id.keyboard_panel)).attach(groupNameField)
        loadContacts()
    }

    private fun loadContacts() {
        lifecycleScope.launch {
            val contacts = withContext(Dispatchers.IO) {
                container.conversations.listConversations()
                    .filter { it.type == ConversationType.DIRECT && !it.pending }
                    .map { ContactEntry(it.id, it.title) }
            }
            val memberContainer = findViewById<LinearLayout>(R.id.member_container)
            if (contacts.isEmpty()) {
                memberContainer.addView(
                    TextView(this@CreateGroupActivity).apply {
                        text = getString(R.string.create_group_no_contacts)
                        setTextColor(resources.getColor(R.color.niix_on_surface_muted, theme))
                        gravity = Gravity.CENTER
                        setPadding(0, 48, 0, 0)
                    },
                )
                return@launch
            }
            for (contact in contacts) {
                val box = CheckBox(this@CreateGroupActivity).apply {
                    text = contact.title
                    tag = contact.onion
                    setTextColor(resources.getColor(R.color.niix_on_surface, theme))
                    setPadding(24, 24, 24, 24)
                }
                boxes.add(box)
                memberContainer.addView(box)
            }
        }
    }

    private fun create() {
        // Group creation reaches out to every member over Tor before it returns, which can take
        // a few seconds. Without this guard, tapping "Create group" more than once while that's
        // in flight (the button gave no feedback that anything was happening) fired a separate
        // createGroup() call per tap, silently creating several duplicate groups.
        if (creating) return
        val name = groupNameField.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, getString(R.string.create_group_need_name), Toast.LENGTH_SHORT).show()
            return
        }
        val members = boxes.filter { it.isChecked }.map { it.tag as String }
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
                Toast.makeText(this@CreateGroupActivity, getString(R.string.create_group_done), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                creating = false
                createButton.isEnabled = true
                createButton.text = getString(R.string.action_create_group)
                Toast.makeText(this@CreateGroupActivity, getString(R.string.toast_failed, result.exceptionOrNull()?.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
