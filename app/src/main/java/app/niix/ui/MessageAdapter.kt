package app.niix.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.niix.R
import app.niix.core.model.Attachment
import app.niix.core.model.Message
import app.niix.core.model.MessageDirection
import app.niix.core.model.MessageType

class MessageAdapter(
    private val onLongClick: (Message) -> Unit,
    private val attachmentOf: (String) -> Attachment?,
    private val bindImage: (ImageView, String) -> Unit,
    private val onOpenAttachment: (Message, Attachment) -> Unit,
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    private val items = mutableListOf<Message>()
    private var highlightedId: String? = null

    fun submit(messages: List<Message>) {
        items.clear()
        items.addAll(messages)
        notifyDataSetChanged()
    }

    fun currentItems(): List<Message> = items

    /** Tints one message's row to mark it as the active in-chat search result; pass null to clear. */
    fun setHighlighted(messageId: String?) {
        if (highlightedId == messageId) return
        highlightedId = messageId
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position].direction == MessageDirection.OUTGOING) TYPE_OUT else TYPE_IN

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == TYPE_OUT) R.layout.item_message_out else R.layout.item_message_in
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val context = holder.itemView.context
        holder.time.text = DateUtils.formatDateTime(context, m.createdAtEpochMillis, DateUtils.FORMAT_SHOW_TIME)
        holder.itemView.setOnLongClickListener { onLongClick(m); true }
        holder.itemView.setBackgroundColor(
            if (m.id == highlightedId) context.getColor(R.color.niix_search_highlight) else android.graphics.Color.TRANSPARENT,
        )

        val attachment = if (!m.deleted && m.type == MessageType.ATTACHMENT) m.attachmentId?.let { attachmentOf(it) } else null

        if (m.deleted || m.type != MessageType.ATTACHMENT || m.attachmentId == null) {
            // Plain text (or an attachment whose metadata hasn't arrived yet).
            holder.body.visibility = View.VISIBLE
            holder.attachmentImage.visibility = View.GONE
            holder.attachmentChip.visibility = View.GONE
            holder.body.text = when {
                m.deleted -> context.getString(R.string.message_deleted)
                m.type == MessageType.ATTACHMENT -> context.getString(R.string.attachment_label)
                else -> m.body
            }
            return
        }

        if (attachment != null && attachment.mimeType.startsWith("image/")) {
            holder.body.visibility = View.GONE
            holder.attachmentChip.visibility = View.GONE
            holder.attachmentImage.visibility = View.VISIBLE
            holder.attachmentImage.setImageDrawable(null)
            holder.attachmentImage.tag = m.attachmentId
            bindImage(holder.attachmentImage, m.attachmentId!!)
            holder.attachmentImage.setOnClickListener { onOpenAttachment(m, attachment) }
        } else {
            holder.body.visibility = View.GONE
            holder.attachmentImage.visibility = View.GONE
            holder.attachmentChip.visibility = View.VISIBLE
            holder.attachmentChip.text = chipLabel(context, attachment)
            holder.attachmentChip.setOnClickListener {
                if (attachment != null) onOpenAttachment(m, attachment)
            }
        }
    }

    private fun chipLabel(context: android.content.Context, a: Attachment?): String {
        val kind = when {
            a == null -> "file"
            a.mimeType.startsWith("video/") -> "video"
            a.mimeType.startsWith("audio/") -> "audio"
            else -> "file"
        }
        return "\uD83D\uDCCE $kind \u2022 " + context.getString(R.string.attachment_tap_open)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val body: TextView = view.findViewById(R.id.body)
        val time: TextView = view.findViewById(R.id.time)
        val attachmentImage: ImageView = view.findViewById(R.id.attachment_image)
        val attachmentChip: TextView = view.findViewById(R.id.attachment_chip)
    }

    companion object {
        private const val TYPE_OUT = 1
        private const val TYPE_IN = 2
    }
}
