package app.niix.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.niix.R

data class ConversationRow(
    val id: String,
    val title: String,
    val preview: String,
    val time: Long,
)

class ConversationAdapter(
    private val onClick: (ConversationRow) -> Unit,
    private val loadAvatar: (android.widget.TextView, String) -> Unit = { _, _ -> },
) : RecyclerView.Adapter<ConversationAdapter.VH>() {

    private val items = mutableListOf<ConversationRow>()

    fun submit(rows: List<ConversationRow>) {
        items.clear()
        items.addAll(rows)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_conversation, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        holder.title.text = row.title
        holder.preview.text = row.preview
        holder.avatar.text = row.title.trim().firstOrNull()?.uppercase() ?: "?"
        holder.avatar.setBackgroundResource(R.drawable.avatar_square)
        loadAvatar(holder.avatar, row.id)
        holder.time.text = if (row.time > 0) {
            DateUtils.getRelativeTimeSpanString(
                row.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            ).toString()
        } else {
            ""
        }
        holder.itemView.setOnClickListener { onClick(row) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: TextView = view.findViewById(R.id.avatar)
        val title: TextView = view.findViewById(R.id.title)
        val preview: TextView = view.findViewById(R.id.preview)
        val time: TextView = view.findViewById(R.id.time)
    }
}
