package com.locogo.astockguard.ui.main

import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.MonitorSnapshot
import com.locogo.astockguard.data.ai.AiStrategy
import com.locogo.astockguard.data.fundflow.SectorFundFlowResult
import com.locogo.astockguard.data.fundflow.StockFundFlow

data class MainUiState(
    val loading: Boolean = false,
    val snapshot: MonitorSnapshot? = null,
    val aiLoading: Boolean = false,
    val aiText: String = "设置页可粘贴配置；AI不可用时本地 E/M/R2 仍独立运行。",
    val aiStrategy: AiStrategy? = null,
    val selectedCode: String? = null,
    val dailyBars: List<DailyBar> = emptyList(),
    val minuteBars: List<MinuteBar> = emptyList(),
    val equityCurve: List<Pair<String, Double>> = emptyList(),
    val stockFundFlow: StockFundFlow? = null,
    val sectorFundFlow: SectorFundFlowResult? = null,
    val fundFlowLoading: Boolean = false,
    val error: String? = null
)
