package com.locogo.astockguard.ui.stock

/*
 * 文件职责：渲染个股详情页并将交互交给 ViewModel；图表点击只设置研究锚点，不代表真实委托。
 * 架构边界：生命周期内只收集可观察状态；耗时任务、持久化和网络请求交给 ViewModel/Repository。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import android.os.Bundle
import android.app.DatePickerDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.locogo.astockguard.MainActivity
import com.locogo.astockguard.appContainer
import com.locogo.astockguard.chart.ChartPeriod
import com.locogo.astockguard.databinding.FragmentStockDetailBinding
import kotlinx.coroutines.launch
import java.util.Locale
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter
import com.locogo.astockguard.domain.trading.DynamicTAction
import com.locogo.astockguard.domain.volume.VolumeSignalType

class StockDetailFragment : Fragment() {
    private var _binding: FragmentStockDetailBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val viewModel: StockDetailViewModel by viewModels {
        val container = requireContext().appContainer
        StockDetailViewModel.Factory(
            container.marketRepository,
            container.strategySignalRepository,
            container.settings,
            container.intradayVolumeCoordinator
        )
    }
    private val code: String get() = requireArguments().getString(ARG_CODE).orEmpty()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStockDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.lifecycleOwner = viewLifecycleOwner
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.btnMinute.setOnClickListener { viewModel.selectPeriod(ChartPeriod.MINUTE) }
        binding.btnDay.setOnClickListener { viewModel.selectPeriod(ChartPeriod.DAY) }
        binding.btnWeek.setOnClickListener { viewModel.selectPeriod(ChartPeriod.WEEK) }
        binding.btnMonth.setOnClickListener { viewModel.selectPeriod(ChartPeriod.MONTH) }
        binding.btnPreviousDate.setOnClickListener { viewModel.shiftMinuteDate(-1) }
        binding.btnNextDate.setOnClickListener { viewModel.shiftMinuteDate(1) }
        binding.btnMinuteDate.setOnClickListener { showMinuteDatePicker() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        binding.state = state
                        binding.tvName.text = state.quote?.name?.ifBlank { state.code } ?: state.code
                        binding.tvPrice.text = state.quote?.latest?.let { String.format(Locale.CHINA, "%.2f", it) } ?: "--"
                        binding.tvChange.text = state.quote?.changeRatio?.let { String.format(Locale.CHINA, "%+.2f%%", it) } ?: "--"
                        val changeRatio = state.quote?.changeRatio
                        val quoteTone = when {
                            changeRatio == null || changeRatio == 0.0 -> com.locogo.astockguard.designsystem.R.color.astock_on_primary
                            changeRatio > 0.0 -> com.locogo.astockguard.designsystem.R.color.astock_positive
                            else -> com.locogo.astockguard.designsystem.R.color.astock_negative
                        }
                        binding.tvPrice.setTextColor(ContextCompat.getColor(requireContext(), quoteTone))
                        binding.tvChange.setTextColor(ContextCompat.getColor(requireContext(), quoteTone))
                        binding.tvDataStatus.text = when {
                            state.loading -> "正在加载行情…"
                            state.minuteLoading -> "正在加载 ${state.selectedMinuteDate} 分时数据…"
                            state.error != null -> state.error
                            state.period == ChartPeriod.MINUTE && state.minuteCandles.isEmpty() ->
                                "${state.selectedMinuteDate} 暂无分时数据"
                            state.period == ChartPeriod.MINUTE && state.minuteDataIsHistorical ->
                                "历史${if (state.minuteDataFromCache) "缓存" else "接口并已缓存"} · ${state.minuteCandles.size} 根 · 回看点 ${state.minuteSignals.size} 个"
                            state.period == ChartPeriod.MINUTE && state.minuteDataFromCache ->
                                "今日缓存 · ${state.minuteCandles.size} 根 · 参考点 ${state.minuteSignals.size} 个（非实时信号）"
                            state.period == ChartPeriod.MINUTE ->
                                "分时 ${state.minuteCandles.size} 根 · 买卖点 ${state.minuteSignals.size} 个"
                            else -> {
                                val latest = state.candles.lastOrNull()
                                val ohlc = latest?.let {
                                    " · ${it.date} 开${price(it.open)} 收${price(it.close)} 高${price(it.high)} 低${price(it.low)}"
                                }.orEmpty()
                                val flags = buildString {
                                    if (state.dailyRealtimeMerged) append(" · 实时补齐今日K")
                                    if (state.dailyFromCache) append(" · 缓存仅供参考")
                                    if (state.dailyUpdatedAt > 0L) append(" · 拉取${clock(state.dailyUpdatedAt)}")
                                }
                                "${state.period.name} ${state.candles.size} 根K线 · ${state.dailySource}$ohlc$flags"
                            }
                        }
                        val score = state.strategy?.score
                        binding.tvTrendScore.text = score?.let { "${it.trendScore}/100" } ?: "--"
                        binding.tvVolumeScore.text = score?.let { "${it.volumeScore}/100" } ?: "--"
                        binding.tvCapitalScore.text = if (score?.capitalAvailable == true) "${score.capitalScore}/100" else "--"
                        binding.tvPositionScore.text = score?.let { "${it.positionScore}/100" } ?: "--"
                        binding.tvTotalScore.text = score?.totalScore?.toString() ?: "--"
                        binding.tvStrategyMeta.text = score?.let {
                            val weights = it.factors.joinToString(" · ") { factor -> "${factor.name}${factor.weightPct}%" }
                            "版本 ${it.strategyVersion} · 数据完整度 ${it.dataCompletenessPct}%\n$weights"
                        } ?: "等待策略数据"
                        binding.tvStrategyReason.text = state.strategy?.reasons?.joinToString("\n")
                            ?: "当前周期至少需要 60 根K线"
                        binding.tvIntradayBacktest.text = with(state.minuteBacktest) {
                            when {
                                state.period != ChartPeriod.MINUTE -> "切换到分时查看提醒回测"
                                evaluated == 0 -> "当前日期没有足够的已完成提醒可统计（推荐价位不计入胜率）"
                                else -> String.format(
                                    Locale.CHINA,
                                    "提醒回测：%d/%d 成功 · 胜率 %.1f%% · 平均净优势 %+.2f%% · 成本合计 %.2f元\n口径：信号后%d根5分钟K，先到+%.2f%%为成功、先到-%.2f%%为失败；%s%s",
                                    wins, evaluated, winRatePct, averageEdgePct, totalCosts, horizonBars, targetPct, stopPct,
                                    rulesDescription,
                                    if (state.minuteFundFlowAvailable) " · 含同日主力分钟净流增量" else " · 无同日资金流，使用量能/VWAP降级策略"
                                )
                            }
                        }
                        renderVolumeRadar(state)
                        binding.klineView.render(
                            state.period,
                            state.candles,
                            state.minuteCandles,
                            state.signals,
                            state.minuteSignals
                        )
                        updatePeriodButtons(state.period)
                        binding.btnMinuteDate.text = state.selectedMinuteDate.toString()
                        binding.btnNextDate.isEnabled = state.selectedMinuteDate.isBefore(com.locogo.astockguard.MarketRepository.marketDate())
                        binding.executePendingBindings()
                    }
                }
                launch {
                    (requireActivity() as MainActivity).dashboardViewModel.uiState.collect { mainState ->
                        viewModel.updateMarketContext(
                            quote = mainState.snapshot?.quotes?.firstOrNull { it.code == code },
                            assessment = mainState.snapshot?.assessment,
                            exposureStatus = mainState.exposurePlan.status,
                            marketDataStale = mainState.snapshot?.dataHealth?.isStale != false
                        )
                    }
                }
            }
        }
        viewModel.load(code)
    }

    private fun updatePeriodButtons(period: ChartPeriod) {
        binding.btnMinute.isChecked = period == ChartPeriod.MINUTE
        binding.btnDay.isChecked = period == ChartPeriod.DAY
        binding.btnWeek.isChecked = period == ChartPeriod.WEEK
        binding.btnMonth.isChecked = period == ChartPeriod.MONTH
    }

    /**
     * 将领域层量能分类、动态T计划和证据渲染为详情页雷达卡片。
     * 本方法只做中文映射和格式化，不重新计算阈值，确保UI不会绕过风险与数据质量门禁。
     */
    private fun renderVolumeRadar(state: StockDetailUiState) = with(binding) {
        val analysis = state.volumeAnalysis
        val features = analysis?.features
        val signal = analysis?.signal
        val plan = state.dynamicTPlan
        tvRadarStatus.text = signal?.let {
            "${signalLabel(it.type)} · 置信度${it.confidence}"
        } ?: if (state.selectedMinuteDate == com.locogo.astockguard.MarketRepository.marketDate()) {
            "等待实时量价数据"
        } else {
            "历史回看暂不生成实时雷达"
        }
        tvRadarMetrics.text = if (signal == null) {
            "量能真实性 --  主动买盘 --\n板块共振 --  趋势强度 --  诱多风险 --"
        } else {
            val board = signal.boardSyncScore?.toString() ?: "不可用"
            "量能真实性 ${signal.volumeScore}  主动买盘 ${signal.buyPressureScore}\n" +
                "板块共振 $board  趋势强度 ${signal.trendScore}  诱多风险 ${signal.bullTrapRisk}"
        }
        tvRadarAction.text = plan?.let { dynamicPlanText(it) } ?: "建议：等待"
        val evidence = (signal?.explanations.orEmpty() + features?.evidence.orEmpty())
            .distinct().take(8)
        tvRadarEvidence.text = evidence.joinToString("\n") { item -> "• $item" }
            .ifBlank { "当前没有足够证据；缺失数据不会按0分参与判断。" }
        tvRadarDataSource.text = "数据源：${state.radarDataSource}"
    }

    /** 将稳定英文分类转换为面向用户的中文名称，避免持久化枚举随展示文案变化。 */
    private fun signalLabel(type: VolumeSignalType): String = when (type) {
        VolumeSignalType.REAL_BREAKOUT -> "真实资金攻击"
        VolumeSignalType.WEAK_BREAKOUT -> "弱突破"
        VolumeSignalType.BULL_TRAP -> "疑似诱多"
        VolumeSignalType.EXHAUSTION -> "放量滞涨"
        VolumeSignalType.NORMAL -> "普通震荡"
        VolumeSignalType.NO_SIGNAL -> "无实时信号"
    }

    /**
     * 格式化动态T建议，SELL_T/REDUCE展示数量和回补观察区，其他状态只展示安全原因。
     * WAIT_BUYBACK即使eligible=true也明确要求人工确认，不使用“立即买入”等误导性文案。
     */
    private fun dynamicPlanText(plan: com.locogo.astockguard.domain.trading.DynamicTPlan): String = when (plan.action) {
        DynamicTAction.HOLD -> "建议：继续持有，暂缓T出"
        DynamicTAction.SELL_T -> "建议：反T卖出${plan.suggestedQuantity}股；回补观察 ${price(plan.suggestedBuybackLow)}～${price(plan.suggestedBuybackHigh)}"
        DynamicTAction.REDUCE -> "建议：降低交易仓${plan.suggestedQuantity}股；风险状态下不承诺回补"
        DynamicTAction.WAIT -> "建议：等待，不追涨也不急于卖出核心仓"
        DynamicTAction.WAIT_BUYBACK -> if (plan.buybackEligible) {
            "建议：回补条件改善（${plan.buybackConditionsMet}项），仍需人工确认"
        } else {
            "建议：等待回补，当前满足${plan.buybackConditionsMet}项条件"
        }
        DynamicTAction.NO_T -> "建议：NO_T · ${plan.reasons.firstOrNull().orEmpty()}"
    }

    private fun showMinuteDatePicker() {
        val selected = viewModel.state.value.selectedMinuteDate
        DatePickerDialog(
            requireContext(),
            { _, year, month, day -> viewModel.selectMinuteDate(LocalDate.of(year, month + 1, day)) },
            selected.year,
            selected.monthValue - 1,
            selected.dayOfMonth
        ).apply {
            datePicker.maxDate = com.locogo.astockguard.MarketRepository.marketDate()
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.show()
    }

    private fun price(value: Double): String = String.format(Locale.CHINA, "%.2f", value)

    private fun clock(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZoneId.of("Asia/Shanghai"))
        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))

    override fun onDestroyView() {
        binding.klineView.release()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_CODE = "stock_code"
        fun newInstance(code: String) = StockDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_CODE, code) }
        }
    }
}
