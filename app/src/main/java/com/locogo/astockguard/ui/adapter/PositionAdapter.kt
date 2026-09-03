package com.locogo.astockguard.ui.adapter

/*
 * 文件职责：绑定持仓策略列表并复用 ViewHolder；点击事件只回传证券代码，不在 Adapter 中请求数据。
 * 架构边界：生命周期内只收集可观察状态；耗时任务、持久化和网络请求交给 ViewModel/Repository。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
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
            tvTitle.text = "${item.name.ifBlank { item.code }}  ${item.code}"
            tvQuote.text = buildString {
                append(item.price?.let { String.format(Locale.CHINA, "%.2f", it) } ?: "--")
                append("  ")
                append(item.changeRatio?.let { String.format(Locale.CHINA, "%+.2f%%", it) } ?: "--")
                append("  ·  ${item.role}  ·  R2 ${item.r2Score}/${item.r2Grade}")
                if (item.stale) append("  ·  缓存")
            }
            val quoteColor = when {
                item.changeRatio == null || item.changeRatio == 0.0 -> com.locogo.astockguard.designsystem.R.color.astock_text_secondary
                item.changeRatio > 0.0 -> com.locogo.astockguard.designsystem.R.color.astock_positive
                else -> com.locogo.astockguard.designsystem.R.color.astock_negative
            }
            tvQuote.setTextColor(ContextCompat.getColor(root.context, quoteColor))
            val advice = item.aiAction.takeUnless { it == "-" } ?: item.localAction
            tvActions.text = "仓位 ${item.positionPct?.let { String.format(Locale.CHINA, "%.1f%%", it) } ?: "--"}  |  " +
                "AI评分 ${if (item.aiAction == "-") "--" else item.aiConfidence}  |  建议 $advice" +
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
