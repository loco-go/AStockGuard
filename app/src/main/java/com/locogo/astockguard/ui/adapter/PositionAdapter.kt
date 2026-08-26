package com.locogo.astockguard.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.locogo.astockguard.databinding.ItemPositionStrategyBinding
import com.locogo.astockguard.ui.main.StockStrategyUiModel
import java.util.Locale

class PositionAdapter(
    private val onSelected: (String) -> Unit
) : ListAdapter<StockStrategyUiModel, PositionAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemPositionStrategyBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemPositionStrategyBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: StockStrategyUiModel) = with(binding) {
            this.item = item
            tvTitle.text = "${item.name.ifBlank { item.code }}  ${item.code}  ·  ${item.role}"
            tvQuote.text = buildString {
                append(item.price?.let { String.format(Locale.CHINA, "%.2f", it) } ?: "--")
                append("  ")
                append(item.changeRatio?.let { String.format(Locale.CHINA, "%+.2f%%", it) } ?: "--")
                append("  R2 ${item.r2Score}/${item.r2Grade}")
                if (item.stale) append("  ·  缓存")
            }
            tvActions.text = "本地 ${item.localAction}  |  AI ${item.aiAction} ${item.aiConfidence}%" +
                if (item.conflict) "  ·  冲突" else ""
            tvReason.text = listOf(item.localReason, item.aiReason).filter { it.isNotBlank() }.joinToString("；")
            root.setOnClickListener { onSelected(item.code) }
            executePendingBindings()
        }
    }

    private object Diff : DiffUtil.ItemCallback<StockStrategyUiModel>() {
        override fun areItemsTheSame(oldItem: StockStrategyUiModel, newItem: StockStrategyUiModel) = oldItem.code == newItem.code
        override fun areContentsTheSame(oldItem: StockStrategyUiModel, newItem: StockStrategyUiModel) = oldItem == newItem
    }
}
