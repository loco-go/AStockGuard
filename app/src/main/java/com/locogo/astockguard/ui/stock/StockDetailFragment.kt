package com.locogo.astockguard.ui.stock

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

class StockDetailFragment : Fragment() {
    private var _binding: FragmentStockDetailBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val viewModel: StockDetailViewModel by viewModels {
        val container = requireContext().appContainer
        StockDetailViewModel.Factory(
            container.marketRepository,
            container.fundFlowRepository,
            container.strategySignalRepository
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
                            else -> "${state.period.name} ${state.candles.size} 根K线"
                        }
                        val score = state.strategy?.score
                        binding.tvTrendScore.text = score?.trendScore?.toString() ?: "--"
                        binding.tvVolumeScore.text = score?.volumeScore?.toString() ?: "--"
                        binding.tvCapitalScore.text = if (score?.capitalAvailable == true) score.capitalScore.toString() else "--"
                        binding.tvPositionScore.text = score?.positionScore?.toString() ?: "--"
                        binding.tvTotalScore.text = score?.totalScore?.toString() ?: "--"
                        binding.tvStrategyReason.text = state.strategy?.reasons?.joinToString("\n")
                            ?: "当前周期至少需要 60 根K线"
                        binding.tvIntradayBacktest.text = with(state.minuteBacktest) {
                            when {
                                state.period != ChartPeriod.MINUTE -> "切换到分时查看提醒回测"
                                evaluated == 0 -> "当前日期没有足够的已完成提醒可统计（推荐价位不计入胜率）"
                                else -> String.format(
                                    Locale.CHINA,
                                    "提醒回测：%d/%d 成功 · 胜率 %.1f%% · 平均净优势 %+.2f%%\n口径：信号后%d根5分钟K，先到+%.2f%%为成功、先到-%.2f%%为失败，已扣0.10%%成本%s",
                                    wins, evaluated, winRatePct, averageEdgePct, horizonBars, targetPct, stopPct,
                                    if (state.minuteFundFlowAvailable) " · 含同日主力分钟净流增量" else " · 无同日资金流，使用量能/VWAP降级策略"
                                )
                            }
                        }
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
                        viewModel.updateQuote(mainState.snapshot?.quotes?.firstOrNull { it.code == code })
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
