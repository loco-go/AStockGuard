package com.locogo.astockguard.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.locogo.astockguard.MainActivity
import com.locogo.astockguard.appContainer
import com.locogo.astockguard.databinding.FragmentDashboardBinding
import com.locogo.astockguard.ui.adapter.PositionAdapter
import com.locogo.astockguard.ui.main.MainUiState
import com.locogo.astockguard.ui.main.StrategyUiMapper
import kotlinx.coroutines.launch
import java.util.Locale

class DashboardFragment : Fragment(), DashboardHandlers {
    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val host get() = requireActivity() as MainActivity
    private val viewModel get() = host.dashboardViewModel
    private val positionAdapter = PositionAdapter { viewModel.selectStock(it) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        binding.handlers = this
        binding.listStrategies.layoutManager = LinearLayoutManager(requireContext())
        binding.listStrategies.adapter = positionAdapter
        showPanel(Panel.DECISION)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.lifecycleOwner = viewLifecycleOwner
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    private fun render(state: MainUiState) = with(binding) {
        this.state = state
        val snapshot = state.snapshot
        tvMarketSummary.text = if (snapshot == null) {
            "暂无行情"
        } else {
            val health = if (snapshot.dataHealth.isStale) "缓存数据，仅供观察" else snapshot.dataHealth.source
            "阶段 ${snapshot.assessment.marketPhase}  ·  仓位 ${percentValue(snapshot.positionRatio)}\n" +
                "平均涨跌 ${percentValue(snapshot.assessment.avgChange)}  ·  下跌占比 ${pct(snapshot.assessment.redRatio)}\n" +
                "$health  ·  ${snapshot.assessment.advice}"
        }
        positionAdapter.submitList(StrategyUiMapper.map(snapshot, requireContext().appContainer.settings.positions(), state.aiStrategy))
        tvTradePlan.text = state.tTradePlan?.let {
            "${it.code}  ${it.status}\n买入 ${price(it.buyZoneLow)}-${price(it.buyZoneHigh)}  " +
                "卖出 ${price(it.sellZoneLow)}-${price(it.sellZoneHigh)}\n" +
                "失效 ${price(it.invalidPrice)}  数量 ${it.suggestedQuantity}\n${it.reason}"
        } ?: "暂无计划"
        tvR2.text = state.r2ScanRows.take(8).joinToString("\n") { "${it.code}  ${it.score}/${it.grade}  ${it.stageHint}\n${it.reason}" }.ifBlank { "暂无扫描结果" }
        tvSignals.text = state.signalStates.take(8).joinToString("\n") { "${it.code}  ${it.stage}  ${it.lastAction}  ${it.reason}" }.ifBlank { "暂无信号" }
        tvNews.text = with(state.newsRisk) {
            "$level  ·  风险分 $score  ·  来源 $sourceCount" +
                (if (stale) "  ·  缓存" else "") +
                evidence.take(3).joinToString(separator = "\n", prefix = if (evidence.isEmpty()) "" else "\n") { it.title }
        }
        klineView.setBars(state.dailyBars)
        tvTrades.text = state.tradeRecords.takeLast(12).joinToString("\n") {
            "${it.code}  ${it.side}  ${it.quantity}股  ${price(it.price)}  ${it.source}"
        }.ifBlank { "暂无成交记录" }
        tvLevel2.text = state.level2?.let {
            val flags = listOfNotNull(if (it.simulated) "模拟" else null, if (it.stale) "缓存" else null).joinToString("/")
            "${it.code}  ${it.source} ${flags}\n" +
                "卖盘 ${it.asks.take(5).joinToString { row -> "${price(row.price)}/${row.volume}" }}\n" +
                "买盘 ${it.bids.take(5).joinToString { row -> "${price(row.price)}/${row.volume}" }}\n${it.message}"
        } ?: "暂无 Level2 数据"
        tvStockFlow.text = state.stockFundFlow?.let {
            "${it.code}  ${it.source}${if (it.stale) "  ·  缓存" else ""}\n" +
                it.periods.joinToString("\n") { row -> "${row.days}日 主力净额 ${money(row.mainNet)}" }
        } ?: "暂无资金流数据"
        tvSectorFlow.text = state.sectorFundFlow?.let {
            "${it.type}  ${it.source}${if (it.stale) "  ·  缓存" else ""}\n" +
                it.rows.take(12).joinToString("\n") { row -> "${row.name}  ${money(row.mainNet)}  ${percentValue(row.changePct)}" }
        } ?: "暂无板块排名"
        tvEquity.text = state.equityCurve.takeLast(8).joinToString("  ") { "${it.first} ${String.format(Locale.CHINA, "%.2f", it.second)}" }.ifBlank { "暂无组合净值" }
        tvReview.text = "信号 ${state.signalReviewStats.evaluated}/${state.signalReviewStats.total}  " +
            "胜率 ${percentValue(state.signalReviewStats.winRate)}  平均优势 ${percentValue(state.signalReviewStats.averageEdgePct)}\n" +
            "真实成交 ${state.tradeReviewStats.trades}  已闭合 ${state.tradeReviewStats.closedTrades}  " +
            "实现盈亏 ${money(state.tradeReviewStats.realizedPnl)}"
        tvPaper.text = with(state.paperSummary) {
            "权益 ${money(equity)}  现金 ${money(cash)}  市值 ${money(marketValue)}  收益 ${pct(returnPct / 100.0)}\n" +
                positions.take(6).joinToString("  ") { "${it.code} ${it.quantity}股" }
        }
        tvReplay.text = state.replayReport?.let {
            "${it.strategy}  收益 ${pct(it.returnPct / 100.0)}  最大回撤 ${pct(it.maxDrawdownPct / 100.0)}\n" +
                "闭合 ${it.closedTrades}  胜率 ${pct(it.winRatePct / 100.0)}  PF ${String.format(Locale.CHINA, "%.2f", it.profitFactor)}"
        } ?: "进度 ${state.replayIndex + 1}/${state.minuteBars.size}${if (state.replayRunning) "  ·  播放中" else ""}"
        executePendingBindings()
    }

    private enum class Panel { DECISION, CHART, FLOW, REVIEW }

    private fun showPanel(panel: Panel) = with(binding) {
        panelDecision.visibility = if (panel == Panel.DECISION) View.VISIBLE else View.GONE
        panelChart.visibility = if (panel == Panel.CHART) View.VISIBLE else View.GONE
        panelFlow.visibility = if (panel == Panel.FLOW) View.VISIBLE else View.GONE
        panelReview.visibility = if (panel == Panel.REVIEW) View.VISIBLE else View.GONE
        btnDecision.isActivated = panel == Panel.DECISION
        btnChart.isActivated = panel == Panel.CHART
        btnFlow.isActivated = panel == Panel.FLOW
        btnReview.isActivated = panel == Panel.REVIEW
    }

    override fun onDecisionTab() = showPanel(Panel.DECISION)
    override fun onChartTab() = showPanel(Panel.CHART)
    override fun onFlowTab() = showPanel(Panel.FLOW)
    override fun onReviewTab() = showPanel(Panel.REVIEW)
    override fun onRefresh() = viewModel.refresh()
    override fun onStartMonitor() = host.startMonitor()
    override fun onStopMonitor() = host.stopMonitor()
    override fun onSettings() = host.openSettings()
    override fun onAnalyze() = viewModel.analyze(binding.etQuestion.text?.toString().orEmpty())
    override fun onRefreshNews() = viewModel.refreshNews(force = true)
    override fun onRefreshLevel2() = viewModel.refreshLevel2()
    override fun onRecordBuy() = showTradeDialog("BUY")
    override fun onRecordSell() = showTradeDialog("SELL")
    override fun onIndustryFlow() = viewModel.refreshSectorFlow("INDUSTRY")
    override fun onConceptFlow() = viewModel.refreshSectorFlow("CONCEPT")
    override fun onClearAnchor() = viewModel.clearBuyAnchor()
    override fun onPaperBuy() = viewModel.paperTrade("BUY")
    override fun onPaperSell() = viewModel.paperTrade("SELL")
    override fun onResetPaper() = viewModel.resetPaper()
    override fun onReplayReset() = viewModel.resetReplay()
    override fun onReplayStep() = viewModel.stepReplay()
    override fun onReplayPlay() = viewModel.startReplay(5)
    override fun onReplayPause() = viewModel.pauseReplay()
    override fun onRunBacktest() = viewModel.runReplayBacktest()

    private fun showTradeDialog(side: String) {
        val code = viewModel.uiState.value.selectedCode ?: return
        val quantityInput = EditText(requireContext()).apply {
            hint = "数量（股）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val priceInput = EditText(requireContext()).apply {
            hint = "成交价格"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(quantityInput)
            addView(priceInput)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("${if (side == "BUY") "记录买入" else "记录卖出"} · $code")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val quantity = quantityInput.text.toString().toIntOrNull() ?: return@setPositiveButton
                val price = priceInput.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                viewModel.recordTrade(side, quantity, price)
            }
            .show()
    }

    override fun onDestroyView() {
        binding.listStrategies.adapter = null
        binding.klineView.release()
        _binding = null
        super.onDestroyView()
    }

    private fun price(value: Double) = String.format(Locale.CHINA, "%.2f", value)
    private fun pct(value: Double) = String.format(Locale.CHINA, "%+.2f%%", value * 100.0)
    private fun percentValue(value: Double) = String.format(Locale.CHINA, "%+.2f%%", value)
    private fun money(value: Double) = when {
        kotlin.math.abs(value) >= 100_000_000 -> String.format(Locale.CHINA, "%.2f亿", value / 100_000_000)
        kotlin.math.abs(value) >= 10_000 -> String.format(Locale.CHINA, "%.2f万", value / 10_000)
        else -> String.format(Locale.CHINA, "%.2f", value)
    }
}
