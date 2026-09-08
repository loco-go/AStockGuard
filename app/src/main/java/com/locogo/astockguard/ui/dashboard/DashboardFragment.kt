package com.locogo.astockguard.ui.dashboard

/*
 * 文件职责：把 MainUiState 渲染为交易驾驶舱并转发用户操作；这里只负责展示和事件绑定，不承担行情选择或交易决策。
 * 架构边界：生命周期内只收集可观察状态；耗时任务、持久化和网络请求交给 ViewModel/Repository。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.locogo.astockguard.MainActivity
import com.locogo.astockguard.R
import com.locogo.astockguard.chart.ChartPeriod
import com.locogo.astockguard.databinding.FragmentDashboardBinding
import com.locogo.astockguard.domain.quality.DataQualityEvaluator
import com.locogo.astockguard.domain.review.AccountLedgerType
import com.locogo.astockguard.domain.replay.VolumeReplayMetrics
import com.locogo.astockguard.domain.strategy.ChartSignalAction
import com.locogo.astockguard.domain.strategy.IntradayChartSignal
import com.locogo.astockguard.ui.chart.ChartDataMapper
import com.locogo.astockguard.ui.adapter.PositionAdapter
import com.locogo.astockguard.ui.main.MainUiState
import com.locogo.astockguard.ui.main.StrategyUiMapper
import com.locogo.astockguard.ui.stock.StockDetailFragment
import kotlinx.coroutines.launch
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DashboardFragment : Fragment(), DashboardHandlers {
    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val host get() = requireActivity() as MainActivity
    private val viewModel get() = host.dashboardViewModel
    private val positionAdapter = PositionAdapter(::openStockDetail)

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
        val quality = DataQualityEvaluator.evaluate(
            snapshot = snapshot,
            minuteCount = state.minuteBars.size,
            minuteFromCache = state.minuteFromCache,
            minuteHistorical = state.minuteHistorical,
            minuteSource = state.minuteSource,
            stockFundFlow = state.stockFundFlow,
            level2 = state.level2,
            news = state.newsRisk
        )
        val market = DashboardSummaryMapper.market(snapshot, state.monitoredCodes)
        val account = DashboardSummaryMapper.account(snapshot, state.positions, state.cashBalance)
        tvShanghaiIndex.text = indexText(market.indices[0])
        tvShenzhenIndex.text = indexText(market.indices[1])
        tvChinextIndex.text = indexText(market.indices[2])
        applyTone(tvShanghaiIndex, market.indices[0].changePct)
        applyTone(tvShenzhenIndex, market.indices[1].changePct)
        applyTone(tvChinextIndex, market.indices[2].changePct)
        tvTurnover.text = "两市成交额\n${market.turnover?.let(::money) ?: "--"}"
        tvMarketBreadth.text = "${if (market.stale) "缓存" else ""}涨/跌/平（监控池${market.trackedCount}只）\n" +
            "${market.risingCount}/${market.fallingCount}/${market.flatCount}" +
            if (market.unknownCount > 0) " · 待行情${market.unknownCount}" else ""
        tvTotalAssets.text = "总资产\n${account.totalAssets?.let(::money) ?: "未配置现金"}"
        tvTodayPnl.text = "今日收益\n" + when (account.todayPnlState) {
            "PREOPEN" -> "待开盘"
            "INCOMPLETE" -> "待行情"
            else -> account.todayPnl?.let(::signedMoney) ?: "--"
        }
        tvCumulativePnl.text = "累计收益\n${account.cumulativePnl?.let(::signedMoney) ?: "--"}"
        tvPositionRatio.text = "当前仓位\n${percentValue(account.positionPct)}"
        tvExposurePlan.text = with(state.exposurePlan) {
            buildString {
                append("$status  当前${percentValue(currentPositionPct)}  目标上限${percentValue(targetPositionPct)}")
                append("\n$reason")
                reductions.take(6).forEachIndexed { index, row ->
                    append("\n${index + 1}. ${row.name.ifBlank { row.code }} ${row.category.label} 减${row.quantity}股")
                    append(" · ${row.reason}")
                }
            }
        }
        tvExposureBacktest.text = if (state.exposureBacktestLoading) {
            "历史回放计算中…"
        } else with(state.exposureBacktest) {
            if (status != "READY") note else buildString {
                append("日K无前视回放 $sampleDays 日 · 降仓提醒 $reductionSignals 次")
                append("\n策略收益 ${percentValue(strategyReturnPct)} / 满仓基准 ${percentValue(benchmarkReturnPct)}")
                append("\n最大回撤 ${percentValue(strategyMaxDrawdownPct)} / 基准 ${percentValue(benchmarkMaxDrawdownPct)}")
                append("\n降仓后3日有效率 ${reductionSuccessRatePct?.let(::percentValue) ?: "样本不足"}")
                append("\n$note")
            }
        }
        applyTone(tvTodayPnl, account.todayPnl)
        applyTone(tvCumulativePnl, account.cumulativePnl)
        val importedAt = System.currentTimeMillis()
        com.locogo.astockguard.integration.ths.ThsAccountMetric.entries.forEach { metric ->
            ImportedAccountPresenter.text(metric, state.importedAccountMetrics, importedAt)?.let { text ->
                val view = when (metric) {
                    com.locogo.astockguard.integration.ths.ThsAccountMetric.TODAY_PNL -> tvTodayPnl
                    com.locogo.astockguard.integration.ths.ThsAccountMetric.CUMULATIVE_PNL -> tvCumulativePnl
                    com.locogo.astockguard.integration.ths.ThsAccountMetric.POSITION_PCT -> tvPositionRatio
                    com.locogo.astockguard.integration.ths.ThsAccountMetric.TOTAL_ASSETS -> tvTotalAssets
                }
                view.text = text
                if (metric == com.locogo.astockguard.integration.ths.ThsAccountMetric.TODAY_PNL ||
                    metric == com.locogo.astockguard.integration.ths.ThsAccountMetric.CUMULATIVE_PNL) {
                    applyTone(view, state.importedAccountMetrics.find { it.metric == metric.name }?.value)
                }
            }
        }
        tvMarketSummary.text = if (snapshot == null) {
            "暂无行情"
        } else {
            val health = if (snapshot.dataHealth.isStale) "缓存数据，仅供观察" else snapshot.dataHealth.source
            "阶段 ${snapshot.assessment.marketPhase}  ·  仓位 ${percentValue(snapshot.positionRatio)}\n" +
                "平均涨跌 ${percentValue(snapshot.assessment.avgChange)}  ·  下跌占比 ${pct(snapshot.assessment.redRatio)}\n" +
                "$health  ·  ${snapshot.assessment.advice}"
        }
        tvDataQualitySummary.text = if (quality.realtimeReady) {
            "实时策略数据就绪"
        } else {
            "仅观察 · ${quality.blockingReasons.joinToString("；")}"
        }
        tvDataQualityDetails.text = quality.items.joinToString("\n") { item ->
            val permission = if (item.usableForRealtime) "可实时使用" else "仅参考"
            "${item.name}  ${item.status.label} · ${item.source} · $permission" +
                item.message.takeIf(String::isNotBlank)?.let { "\n  $it" }.orEmpty()
        }
        positionAdapter.submitList(StrategyUiMapper.map(snapshot, state.positions, state.aiStrategy))
        tvTradePlan.text = state.tTradePlan?.let {
            val bookSequence = when (it.orderBookPersistence) {
                "PERSISTENT_BID" -> "连续买压"
                "PERSISTENT_ASK" -> "连续卖压"
                "REVERSING" -> "方向反复"
                "UNCONFIRMED" -> "尚未连续"
                "COLLECTING" -> "采集中"
                else -> "不可用"
            }
            "${it.code}  ${it.status}\n买入 ${price(it.buyZoneLow)}-${price(it.buyZoneHigh)}  " +
                "卖出 ${price(it.sellZoneLow)}-${price(it.sellZoneHigh)}\n" +
                "失效 ${price(it.invalidPrice)}  数量 ${it.suggestedQuantity}\n" +
                "资金 ${it.fundFlowStatus}  盘口 ${it.orderBookStatus}\n" +
                "盘口时序 $bookSequence（${it.orderBookSampleCount}帧）\n" +
                "盘口证据 ${if (it.orderBookEvidenceGrade == "SUPPORTING") "五档辅助" else it.orderBookEvidenceGrade}\n" +
                "利润模式 ${it.profitMode}  盘口失衡 ${it.orderBookImbalance?.let(::percentValue) ?: "--"}\n${it.reason}"
        } ?: "暂无计划"
        tvAuctionPlan.text = state.auctionPlan?.let { plan ->
            buildString {
                append("${plan.code}  ${plan.status}  评分${plan.score}  ·  ${plan.source}")
                plan.gapPct?.let { append("\n竞价缺口 ${percentValue(it)}") }
                plan.auctionVolumePct?.let { append("  竞价量/10日日均量 ${percentValue(it)}") }
                append("\n${plan.reason}")
            }
        } ?: "暂无真实竞价数据；腾讯免费源不生成模拟竞价结论"
        tvPositionPlans.text = state.positionPlans.joinToString("\n\n") { plan ->
            buildString {
                append("${plan.name.ifBlank { plan.code }}  ${plan.category.label}  ·  ${plan.action}")
                append("\n最大T仓 ${plan.maxTQuantity}股")
                plan.defensePrice?.let { append("  防守参考 ${price(it)}") }
                append("\n${plan.reason}")
            }
        }.ifBlank { "暂无隔日持仓计划" }
        tvR2.text = state.r2ScanRows.take(8).joinToString("\n") { "${it.code}  ${it.score}/${it.grade}  ${it.stageHint}\n${it.reason}" }.ifBlank { "暂无扫描结果" }
        tvSignals.text = state.signalStates.take(8).joinToString("\n") { "${it.code}  ${it.stage}  ${it.lastAction}  ${it.reason}" }.ifBlank { "暂无信号" }
        tvNews.text = with(state.newsRisk) {
            "$level  ·  风险分 $score  ·  来源 $sourceCount" +
                (if (stale) "  ·  缓存" else "") +
                evidence.take(3).joinToString(separator = "\n", prefix = if (evidence.isEmpty()) "" else "\n") { it.title }
        }
        klineView.setBars(state.dailyBars)
        tvDailyDataStatus.text = state.dailyBars.lastOrNull()?.let { latest ->
            val cache = if (state.dailyFromCache) " · 缓存，仅供参考" else ""
            val merged = if (state.dailyRealtimeMerged) " · 实时补齐今日K" else ""
            val fetched = state.dailyUpdatedAt.takeIf { it > 0L }?.let { " · 拉取${clock(it)}" }.orEmpty()
            "${latest.date}  开 ${price(latest.open)}  收/现 ${price(latest.close)}  " +
                "高 ${price(latest.high)}  低 ${price(latest.low)}\n来源 ${state.dailySource}$merged$cache$fetched"
        } ?: "暂无日K数据 · 来源 ${state.dailySource}"
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
            val inflow = it.rows.filter { row -> row.mainNet > 0.0 }.sortedByDescending { row -> row.mainNet }.take(6)
            val outflow = it.rows.filter { row -> row.mainNet < 0.0 }.sortedBy { row -> row.mainNet }.take(6)
            buildString {
                append("${it.type}  ${it.source}${if (it.stale) "  ·  缓存（仅供参考）" else ""}")
                append("\n行业主力流入\n")
                append(inflow.joinToString("\n") { row -> "${row.name}  ${money(row.mainNet)}  ${percentValue(row.changePct)}" }.ifBlank { "暂无净流入行业" })
                append("\n行业主力流出\n")
                append(outflow.joinToString("\n") { row -> "${row.name}  ${money(row.mainNet)}  ${percentValue(row.changePct)}" }.ifBlank { "暂无净流出行业" })
            }
        } ?: "暂无板块排名"
        tvEquity.text = state.equityCurve.takeLast(8).joinToString("  ") { "${it.first} ${String.format(Locale.CHINA, "%.2f", it.second)}" }.ifBlank { "暂无组合净值" }
        tvReview.text = with(state.alertHistoryStats) {
            buildString {
                append("全部真实提醒 $evaluated/$total  待评价 $pending  胜率 ${percentValue(winRatePct)}  平均净优势 ${percentValue(averageNetEdgePct)}")
                append("\n做T提醒 $tEvaluated/$tTotal  待评价 $tPending  胜率 ${percentValue(tWinRatePct)}  平均净优势 ${percentValue(tAverageNetEdgePct)}")
                append("\n量能雷达 $radarSignals 条  多周期评价 $radarEvaluations 条  有效 $radarEffective 条  命中率 ${percentValue(radarAccuracyPct)}")
                append("\n生命周期信号 ${state.signalReviewStats.evaluated}/${state.signalReviewStats.total}  " +
                    "真实成交 ${state.tradeReviewStats.trades}  已闭合 ${state.tradeReviewStats.closedTrades}  " +
                    "实现盈亏 ${money(state.tradeReviewStats.realizedPnl)}")
                recent.take(8).forEach { alert ->
                    val result = when (alert.status) { "WIN" -> "成功"; "LOSS" -> "失败"; "TRACKING" -> "多周期跟踪"; else -> "待评价" }
                    append("\n${alert.signalDate} ${alert.signalTime} ${alert.name} ${alert.action} $result")
                    if (alert.status != "PENDING") append(" ${percentValue(alert.netEdgePct)}")
                    val alertLabel = when (alert.alertType) {
                        "T_PLAN" -> "做T"
                        "VOLUME_RADAR" -> "量能雷达 ${alert.signalType} C${alert.confidence}"
                        else -> "分时"
                    }
                    append(" · $alertLabel · ${alert.strategyVersion}")
                }
            }
        }
        tvAccountLedger.text = with(state.accountLedgerSummary) {
            val truePnl = cumulativePnl?.let(::signedMoney) ?: "待记录期初资产"
            val capital = netInvestedCapital?.let(::money) ?: "--"
            buildString {
                append("净投入 $capital  ·  真实累计收益 $truePnl")
                append("\n持仓浮盈 ${signedMoney(unrealizedPnl)}  ·  分红利息 ${money(investmentIncome)}  ·  费用税费 ${money(explicitCosts)}")
                if (!hasOpeningBalance) append("\n请先记录一次期初资产，之后的转入/转出才能与投资收益分离。")
                entries.take(8).forEach { entry ->
                    val typeName = AccountLedgerType.from(entry.type)?.displayName ?: entry.type
                    append("\n$typeName  ${money(entry.amount)}")
                    if (entry.note.isNotBlank()) append("  ${entry.note}")
                }
            }
        }
        tvPaper.text = with(state.paperSummary) {
            "权益 ${money(equity)}  现金 ${money(cash)}  市值 ${money(marketValue)}  收益 ${pct(returnPct / 100.0)}\n" +
                positions.take(6).joinToString("  ") { "${it.code} ${it.quantity}股" }
        }
        tvReplay.text = renderReplaySummary(state)
        updateStateButtons(state)
        renderReplayChart(state)
        executePendingBindings()
    }

    /**
     * 把原有VWAP回放与新增四组策略对比合并为一个可读摘要。
     * 数据覆盖说明始终随结果展示，避免用户把缺少板块、资金流或历史风控的降级回放误认为完整实盘验证。
     */
    private fun renderReplaySummary(state: MainUiState): String {
        val legacy = state.replayReport?.let {
            "原回放 ${it.strategy}  收益 ${pct(it.returnPct / 100.0)}  最大回撤 ${pct(it.maxDrawdownPct / 100.0)}\n" +
                "闭合 ${it.closedTrades}  胜率 ${pct(it.winRatePct / 100.0)}  PF ${formatFactor(it.profitFactor)}  " +
                "费用 ${money(it.totalFees + it.totalTax + it.slippageCost)}"
        }
        val comparison = state.volumeReplayComparison?.let { result ->
            listOf(
                result.buyAndHold,
                result.fixedWidthT,
                result.dynamicVolumeT,
                result.riskControlledDynamicT
            ).joinToString("\n", postfix = "\n${result.dataCoverage}") { renderVolumeReplayRow(it) }
        }
        return listOfNotNull(legacy, comparison).joinToString("\n\n").ifBlank {
            "进度 ${state.replayIndex + 1}/${state.minuteBars.size}${if (state.replayRunning) "  ·  播放中" else ""}"
        }
    }

    /** 将一组量能策略压缩成收益/回撤、T净贡献、胜率、PF、次数和全成本行。 */
    private fun renderVolumeReplayRow(metrics: VolumeReplayMetrics): String =
        "${metrics.strategy}  收益 ${percentValue(metrics.totalReturnPct)}  回撤 ${percentValue(metrics.maxDrawdownPct)}  " +
            "收益回撤比 ${String.format(Locale.CHINA, "%.2f", metrics.returnDrawdownRatio)}\n" +
            "T净贡献 ${signedMoney(metrics.tNetContribution)}  胜率 ${percentValue(metrics.winRatePct)}  " +
            "PF ${formatFactor(metrics.profitFactor)}  交易 ${metrics.transactionCount}次  " +
            "全成本 ${money(metrics.fees + metrics.taxes + metrics.slippage)}  " +
            "卖飞/错补 ${money(metrics.soldAwayLoss)}/${money(metrics.wrongBuybackLoss)}"

    /** 无限Profit Factor显示为∞，有限值保留两位，避免直接渲染Infinity破坏中文摘要。 */
    private fun formatFactor(value: Double): String =
        if (value.isInfinite()) "∞" else String.format(Locale.CHINA, "%.2f", value)

    /**
     * 统一维护互斥操作按钮的颜色和可用状态。
     * 激活项使用主色，另一项自动退为浅色，让服务、回放和资金分类状态一眼可见。
     */
    private fun updateStateButtons(state: MainUiState) = with(binding) {
        btnStartMonitor.isActivated = !state.monitorRunning
        btnStartMonitor.isEnabled = !state.monitorRunning
        btnStopMonitor.isActivated = state.monitorRunning
        btnStopMonitor.isEnabled = state.monitorRunning

        btnReplayPlay.isActivated = !state.replayRunning
        btnReplayPlay.isEnabled = !state.replayRunning && state.minuteBars.isNotEmpty()
        btnReplayPause.isActivated = state.replayRunning
        btnReplayPause.isEnabled = state.replayRunning

        val flowType = state.sectorFundFlow?.type ?: "INDUSTRY"
        btnIndustryFlow.isActivated = flowType == "INDUSTRY"
        btnConceptFlow.isActivated = flowType == "CONCEPT"
    }

    private fun renderReplayChart(state: MainUiState) {
        val visibleCount = (state.replayIndex + 1).coerceIn(0, state.minuteBars.size)
        val candles = ChartDataMapper.aggregateMinutes(state.minuteBars.take(visibleCount))
        val markers = state.replayReport?.trades.orEmpty()
            .filter { it.index < visibleCount }
            .mapNotNull { trade ->
                val candle = candles.lastOrNull { it.time <= trade.time } ?: return@mapNotNull null
                IntradayChartSignal(
                    time = candle.time,
                    price = trade.price,
                    action = if (trade.side == "BUY") ChartSignalAction.BUY else ChartSignalAction.SELL,
                    score = 0,
                    reason = trade.reason
                )
            }
        binding.replayKlineView.render(
            period = ChartPeriod.MINUTE,
            candles = emptyList(),
            minutes = candles,
            minuteSignals = markers
        )
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
    override fun onRecordLedger() = showAccountLedgerDialog()
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

    private fun showAccountLedgerDialog() {
        val types = AccountLedgerType.entries
        val typeSpinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_dropdown_item, types.map { it.displayName }
            )
        }
        val amountInput = EditText(requireContext()).apply {
            hint = "金额"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val noteInput = EditText(requireContext()).apply { hint = "备注（可选）" }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(typeSpinner)
            addView(amountInput)
            addView(noteInput)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("记录账户流水")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val amount = amountInput.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                viewModel.recordAccountLedger(types[typeSpinner.selectedItemPosition], amount, noteInput.text.toString())
            }
            .show()
    }

    private fun openStockDetail(code: String) {
        viewModel.selectStock(code)
        parentFragmentManager.beginTransaction()
            .replace(R.id.mainContainer, StockDetailFragment.newInstance(code))
            .addToBackStack("stock:$code")
            .commit()
    }

    override fun onDestroyView() {
        binding.listStrategies.adapter = null
        binding.klineView.release()
        binding.replayKlineView.release()
        _binding = null
        super.onDestroyView()
    }

    private fun price(value: Double) = String.format(Locale.CHINA, "%.2f", value)
    private fun clock(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZoneId.of("Asia/Shanghai"))
        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    private fun pct(value: Double) = String.format(Locale.CHINA, "%+.2f%%", value * 100.0)
    private fun percentValue(value: Double) = String.format(Locale.CHINA, "%+.2f%%", value)
    private fun indexText(index: MarketIndexUi) = "${index.name}\n" +
        (index.value?.let { String.format(Locale.CHINA, "%.2f", it) } ?: "--") + "  " +
        (index.changePct?.let(::percentValue) ?: "")
    private fun signedMoney(value: Double) = String.format(Locale.CHINA, "%+.2f", value)
    private fun applyTone(view: android.widget.TextView, value: Double?) {
        val color = when {
            value == null || value == 0.0 -> com.locogo.astockguard.designsystem.R.color.astock_text_primary
            value > 0.0 -> com.locogo.astockguard.designsystem.R.color.astock_positive
            else -> com.locogo.astockguard.designsystem.R.color.astock_negative
        }
        view.setTextColor(ContextCompat.getColor(requireContext(), color))
    }
    private fun money(value: Double) = when {
        kotlin.math.abs(value) >= 100_000_000 -> String.format(Locale.CHINA, "%.2f亿", value / 100_000_000)
        kotlin.math.abs(value) >= 10_000 -> String.format(Locale.CHINA, "%.2f万", value / 10_000)
        else -> String.format(Locale.CHINA, "%.2f", value)
    }
}
