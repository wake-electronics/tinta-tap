package com.wakeelectronics.tintatap.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.wakeelectronics.tintatap.R
import com.wakeelectronics.tintatap.model.Action
import com.wakeelectronics.tintatap.model.ActionType
import java.util.Collections

class ActionAdapter(
    private val items: MutableList<Action>,
    private val lastUsedId: String?,
    private val onClick: (Action) -> Unit
) : RecyclerView.Adapter<ActionAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val card = view as MaterialCardView
        val icon: ImageView = view.findViewById(R.id.ivIcon)
        val name: TextView = view.findViewById(R.id.tvName)
        val subtitle: TextView = view.findViewById(R.id.tvSubtitle)
        val badge: TextView = view.findViewById(R.id.tvBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_action, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = items[position]
        holder.name.text = a.name
        holder.subtitle.text = a.subtitle
        holder.icon.setImageResource(iconFor(a.type))
        val isLast = a.id == lastUsedId
        holder.badge.visibility = if (isLast) View.VISIBLE else View.GONE
        holder.card.strokeColor = ContextCompat.getColor(
            holder.card.context, if (isLast) R.color.tinta_primary else R.color.tinta_border
        )
        holder.itemView.setOnClickListener { onClick(a) }
    }

    fun move(from: Int, to: Int) {
        if (from < to) for (i in from until to) Collections.swap(items, i, i + 1)
        else for (i in from downTo to + 1) Collections.swap(items, i, i - 1)
        notifyItemMoved(from, to)
    }

    fun ids(): List<String> = items.map { it.id }

    private fun iconFor(type: ActionType): Int = when (type) {
        ActionType.PAGE -> R.drawable.ic_action_home
        ActionType.DECISION -> R.drawable.ic_action_decide
        ActionType.MESSAGE -> R.drawable.ic_action_message
        ActionType.SKETCH -> R.drawable.ic_action_sketch
        ActionType.BOOK -> R.drawable.ic_action_book
    }
}
