package app.niix.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import app.niix.R
import app.niix.core.messaging.ConversationManager.BlockedPeer
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

/** Everyone who's blocked, including requests that were blocked and deleted, and unblocking them. */
class BlockedActivity : SecureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val blocked = container.conversations.blocked()
            val list = findViewById<LinearLayout>(R.id.blocked_list)
            list.removeAllViews()
            blocked.forEach { list.addView(row(it)) }
            findViewById<TextView>(R.id.blocked_empty).visibility = if (blocked.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun row(peer: BlockedPeer): TextView = TextView(this).apply {
        val address = "${peer.onion.take(16)}…"
        text = peer.name?.let { "$it\n$address" } ?: address
        setTextColor(themeColor(R.attr.niixOnSurface))
        textSize = 15f
        setPadding(8, 24, 8, 24)
        setOnClickListener { confirmUnblock(peer) }
    }

    private fun confirmUnblock(peer: BlockedPeer) {
        niixDialog(this)
            .setTitle(R.string.blocked_unblock_title)
            .setMessage(R.string.blocked_unblock_body)
            .setPositiveButton(R.string.menu_unblock) { _, _ ->
                lifecycleScope.launch {
                    container.conversations.unblock(peer.onion)
                    Toast.makeText(this@BlockedActivity, R.string.toast_unblocked, Toast.LENGTH_SHORT).show()
                    load()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
