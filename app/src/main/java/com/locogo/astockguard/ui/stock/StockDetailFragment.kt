package com.locogo.astockguard.ui.stock

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.locogo.astockguard.MainActivity
import com.locogo.astockguard.appContainer
import com.locogo.astockguard.chart.ChartPeriod
import com.locogo.astockguard.databinding.FragmentStockDetailBinding
import kotlinx.coroutines.launch
import java.util.Locale

class StockDetailFragment : Fragment() {
    private var _binding: FragmentStockDetailBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val viewModel: StockDetailViewModel by viewModels {
        StockDetailViewModel.Factory(requireContext().appContainer.marketRepository)
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
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        binding.state = state
                        binding.tvName.text = state.quote?.name?.ifBlank { state.code } ?: state.code
                        binding.tvPrice.text = state.quote?.latest?.let { String.format(Locale.CHINA, "%.2f", it) } ?: "--"
                        binding.tvChange.text = state.quote?.changeRatio?.let { String.format(Locale.CHINA, "%+.2f%%", it) } ?: "--"
                        binding.tvDataStatus.text = when {
                            state.loading -> "正在加载行情…"
                            state.error != null -> state.error
                            state.period == ChartPeriod.MINUTE -> "分时 ${state.minutes.size} 条"
                            else -> "${state.period.name} ${state.candles.size} 根K线"
                        }
                        binding.klineView.render(state.period, state.candles, state.minutes)
                        updatePeriodButtons(state.period)
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
