package app.niix.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import app.niix.R
import app.niix.core.model.Attachment
import app.niix.core.model.AttachmentState
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
    private var byId: Map<String, Message> = emptyMap()
    private var highlightedId: String? = null

    /** Whether blurring is on at all. Set by the activity. */
    var blurEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            // Affects every row but no message, so the diff in submit() wouldn't redraw anything.
            notifyDataSetChanged()
        }

    /**
     * Group chats only: sender onion to display name. Null in direct chats, which keeps the sender
     * line hidden.
     */
    var senderNameOf: ((String) -> String)? = null
        set(value) {
            field = value
            // Names arrive after the messages, and reloading the same messages changes no row as
            // far as the diff can tell, so redraw here.
            notifyDataSetChanged()
        }

    /**
     * Called when a message is revealed, so the read receipt and disappearing timer start when it's
     * actually read.
     */
    var onReveal: ((Message) -> Unit)? = null

    private fun isHidden(m: Message): Boolean =
        blurEnabled &&
            m.direction != MessageDirection.OUTGOING &&
            !m.deleted &&
            !m.revealed

    private fun reveal(m: Message) {
        if (m.revealed) return
        onReveal?.invoke(m)

        // Update just this row. Reloading the list would scroll to the bottom and lose the message
        // the user just tapped.
        val index = items.indexOfFirst { it.id == m.id }
        if (index >= 0) {
            val revealed = items[index].copy(revealed = true)
            items[index] = revealed
            // Kept in step with items, since quotes are drawn from this map.
            byId = byId + (revealed.id to revealed)
            notifyItemChanged(index)
            // Replies quoting this message need redrawing too.
            items.forEachIndexed { i, other -> if (other.replyToId == revealed.id) notifyItemChanged(i) }
        }
    }

    /**
     * BlurMaskFilter only works on a software layer. On a hardware layer it silently does nothing
     * and the text stays readable.
     */
    private fun applyTextBlur(view: android.widget.TextView, hidden: Boolean) {
        if (hidden) {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            val radius = view.textSize / 2.2f
            view.paint.maskFilter = android.graphics.BlurMaskFilter(
                radius.coerceAtLeast(4f),
                android.graphics.BlurMaskFilter.Blur.NORMAL,
            )
        } else {
            view.paint.maskFilter = null
            view.setLayerType(View.LAYER_TYPE_NONE, null)
        }
        view.invalidate()
    }

    /**
     * Replaces the list, redrawing only rows whose content changed. The chat reloads on every
     * change, and redrawing everything would decrypt and decode every visible image again. A row
     * counts as changed if its message or the message it quotes changed.
     */
    fun submit(messages: List<Message>) {
        val old = items.toList()
        val oldById = byId
        val newById = messages.associateBy { it.id }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = messages.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].id == messages[n].id
            override fun areContentsTheSame(o: Int, n: Int): Boolean {
                val before = old[o]
                val after = messages[n]
                if (before != after) return false
                val quoteId = after.replyToId ?: return true
                return oldById[quoteId] == newById[quoteId]
            }
        })
        items.clear()
        items.addAll(messages)
        byId = newById
        diff.dispatchUpdatesTo(this)
    }

    fun currentItems(): List<Message> = items

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
        // Date as well as time, otherwise yesterday evening looks later than this morning. Android
        // leaves out the year for the current year.
        holder.time.text = DateUtils.formatDateTime(
            context,
            m.createdAtEpochMillis,
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL,
        )
        if (m.direction == MessageDirection.OUTGOING && m.deliveryState == app.niix.core.model.DeliveryState.FAILED) {
            holder.time.text = "${holder.time.text} · ${context.getString(R.string.delivery_failed)}"
            holder.time.setTextColor(context.themeColor(R.attr.niixDanger))
        } else if (m.direction == MessageDirection.OUTGOING && m.deliveryState == app.niix.core.model.DeliveryState.RELAYED) {
            holder.time.text = "${holder.time.text} · ${context.getString(R.string.delivery_relayed)}"
            holder.time.setTextColor(context.themeColor(R.attr.niixOnSurfaceMuted))
        } else {
            holder.time.setTextColor(context.themeColor(R.attr.niixOnSurfaceMuted))
        }
        holder.itemView.setOnLongClickListener { onLongClick(m); true }
        // Sender name in groups only. In a direct chat it's just noise.
        val naming = senderNameOf
        if (naming != null && m.direction != MessageDirection.OUTGOING) {
            holder.sender?.visibility = View.VISIBLE
            holder.sender?.text = naming(m.senderOnion)
        } else {
            holder.sender?.visibility = View.GONE
        }

        // The quote is looked up locally rather than sent with the reply, so it shows what the
        // original says now, and says so if the sender deleted it.
        val quoted = m.replyToId?.let { id -> byId[id] }
        if (m.replyToId == null) {
            holder.quote.visibility = View.GONE
        } else {
            holder.quote.visibility = View.VISIBLE
            holder.quote.text = when {
                quoted == null -> context.getString(R.string.reply_original_unavailable)
                quoted.deleted -> context.getString(R.string.message_deleted)
                quoted.type == MessageType.ATTACHMENT -> context.getString(R.string.attachment_label)
                else -> quoted.body
            }
        }

        holder.itemView.setBackgroundColor(
            if (m.id == highlightedId) context.themeColor(R.attr.niixSearchHighlight) else android.graphics.Color.TRANSPARENT,
        )

        val attachment = if (!m.deleted && m.type == MessageType.ATTACHMENT) m.attachmentId?.let { attachmentOf(it) } else null

        if (m.deleted || m.type != MessageType.ATTACHMENT || m.attachmentId == null) {
            holder.body.visibility = View.VISIBLE
            holder.attachmentImage.visibility = View.GONE
            holder.attachmentChip.visibility = View.GONE
            holder.body.text = when {
                m.deleted -> context.getString(R.string.message_deleted)
                m.type == MessageType.ATTACHMENT -> context.getString(R.string.attachment_label)
                else -> m.body
            }
            val hidden = isHidden(m)
            applyTextBlur(holder.body, hidden)
            if (hidden) {
                // Tapping anywhere on the row reveals it, since a blurred message gives no clue
                // where the words are.
                holder.itemView.setOnClickListener { reveal(m) }
            } else {
                holder.itemView.setOnClickListener(null)
                holder.itemView.isClickable = false
            }
            return
        }

        if (attachment != null && attachment.mimeType.startsWith("image/")) {
            holder.body.visibility = View.GONE
            holder.attachmentChip.visibility = View.GONE
            holder.attachmentImage.visibility = View.VISIBLE
            holder.attachmentImage.setImageDrawable(null)
            holder.attachmentImage.tag = m.attachmentId
            if (attachment?.state == AttachmentState.FAILED) {
                holder.attachmentImage.visibility = View.GONE
                holder.attachmentChip.visibility = View.VISIBLE
                holder.attachmentChip.text = chipLabel(context, attachment)
                holder.attachmentChip.setOnClickListener(null)
                holder.attachmentChip.isClickable = false
                return
            }
            val hiddenImage = isHidden(m)
            if (hiddenImage) {
                // Images aren't blurred in place, because a blurred photo can still give away what
                // it shows. A chip stands in until it's revealed.
                holder.attachmentImage.visibility = View.GONE
                holder.attachmentChip.visibility = View.VISIBLE
                holder.attachmentChip.text = context.getString(R.string.message_tap_to_reveal)
                holder.attachmentChip.setOnClickListener { reveal(m) }
            } else {
                bindImage(holder.attachmentImage, m.attachmentId!!)
                holder.attachmentImage.setOnClickListener { onOpenAttachment(m, attachment) }
            }
        } else {
            holder.body.visibility = View.GONE
            holder.attachmentImage.visibility = View.GONE
            holder.attachmentChip.visibility = View.VISIBLE
            val hiddenFile = isHidden(m)
            holder.attachmentChip.text = if (hiddenFile) {
                context.getString(R.string.message_tap_to_reveal)
            } else {
                chipLabel(context, attachment)
            }
            holder.attachmentChip.setOnClickListener {
                if (hiddenFile) {
                    reveal(m)
                } else if (attachment != null && attachment.state != AttachmentState.FAILED) {
                    onOpenAttachment(m, attachment)
                }
            }
        }
    }

    private fun chipLabel(context: android.content.Context, a: Attachment?): String {
        // A retired attachment says it's gone instead of failing every time it's tapped.
        if (a?.state == AttachmentState.FAILED) {
            return "\uD83D\uDEAB " + context.getString(R.string.attachment_unavailable)
        }
        val kind = when {
            a == null -> "file"
            a.mimeType.startsWith("video/") -> "video"
            a.mimeType.startsWith("audio/") -> "audio"
            else -> "file"
        }
        return "\uD83D\uDCCE $kind \u2022 " + context.getString(R.string.attachment_tap_open)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        // Incoming layout only; outgoing messages never need a sender line.
        val sender: TextView? = view.findViewById(R.id.sender)
        val quote: TextView = view.findViewById(R.id.quote)
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
