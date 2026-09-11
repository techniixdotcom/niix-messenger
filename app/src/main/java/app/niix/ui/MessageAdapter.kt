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

    /** Whether blurring applies at all. Read once per bind by the owning activity. */
    var blurEnabled: Boolean = true

    /** Called when a message is revealed, so the read receipt and any disappearing timer start
     * at the moment it was actually read rather than when it arrived on screen. */
    var onReveal: ((Message) -> Unit)? = null

    private fun isHidden(m: Message): Boolean =
        blurEnabled &&
            m.direction != MessageDirection.OUTGOING &&
            !m.deleted &&
            !RevealedMessages.isRevealed(m.id)

    private fun reveal(m: Message) {
        if (!RevealedMessages.reveal(m.id)) return
        onReveal?.invoke(m)
        notifyDataSetChanged()
    }

    /** Applies or clears a blur on a text view. BlurMaskFilter needs a software layer -- on a
     * hardware layer it silently does nothing, which would leave the text fully readable while
     * appearing to be handled. */
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

    fun submit(messages: List<Message>) {
        items.clear()
        items.addAll(messages)
        notifyDataSetChanged()
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
        // Date as well as time. Showing only the time meant a message from yesterday evening
        // appeared "later" than one from this morning, which reads as the conversation being out
        // of order. FORMAT_SHOW_DATE with FORMAT_ABBREV_ALL keeps it short, and Android omits
        // the year for dates in the current year on its own.
        holder.time.text = DateUtils.formatDateTime(
            context,
            m.createdAtEpochMillis,
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL,
        )
        if (m.direction == MessageDirection.OUTGOING && m.deliveryState == app.niix.core.model.DeliveryState.FAILED) {
            holder.time.text = "${holder.time.text} · ${context.getString(R.string.delivery_failed)}"
            holder.time.setTextColor(context.getColor(R.color.niix_danger))
        } else if (m.direction == MessageDirection.OUTGOING && m.deliveryState == app.niix.core.model.DeliveryState.RELAYED) {
            holder.time.text = "${holder.time.text} · ${context.getString(R.string.delivery_relayed)}"
            holder.time.setTextColor(context.getColor(R.color.niix_on_surface_muted))
        } else {
            holder.time.setTextColor(context.getColor(R.color.niix_on_surface_muted))
        }
        holder.itemView.setOnLongClickListener { onLongClick(m); true }
        holder.itemView.setBackgroundColor(
            if (m.id == highlightedId) context.getColor(R.color.niix_search_highlight) else android.graphics.Color.TRANSPARENT,
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
                // Tapping anywhere on the row reveals it. Set on the row rather than the text so
                // the target is the whole bubble -- a blurred message gives no clue where its
                // words actually are.
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
            val hiddenImage = isHidden(m)
            if (hiddenImage) {
                // An image is not blurred in place: a downscaled or masked photo can still give
                // away its subject. It is replaced with a chip until revealed, so nothing of the
                // picture is rendered at all.
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
                } else if (attachment != null) {
                    onOpenAttachment(m, attachment)
                }
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
