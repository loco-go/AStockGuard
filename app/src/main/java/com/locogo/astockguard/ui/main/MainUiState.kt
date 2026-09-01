package com.locogo.astockguard.ui.main

import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.MonitorSnapshot
import com.locogo.astockguard.Position
import com.locogo.astockguard.data.ai.AiStrategy
import com.locogo.astockguard.data.fundflow.SectorFundFlowResult
import com.locogo.astockguard.data.fundflow.StockFundFlow
import com.locogo.astockguard.data.level2.Level2Snapshot
import com.locogo.astockguard.data.local.SignalStateEntity
import com.locogo.astockguard.data.local.TradeRecordEntity
import com.locogo.astockguard.data.news.NewsRiskAssessment
import com.locogo.astockguard.domain.paper.PaperSummary
import com.locogo.astockguard.domain.replay.ReplayReport
import com.locogo.astockguard.domain.review.SignalReviewStats
import com.locogo.astockguard.domain.review.TradeReviewStats
import com.locogo.astockguard.domain.review.AccountLedgerSummary
import com.locogo.astockguard.domain.review.AlertHistoryStats
import com.locogo.astockguard.domain.signal.R2ScanRow
import com.locogo.astockguard.domain.trading.TTradePlan
import com.locogo.astockguard.domain.plan.AuctionPlan
import com.locogo.astockguard.domain.plan.PositionNextDayPlan
import com.locogo.astockguard.domain.plan.ExposureControlPlan

data class MainUiState(
    val loading: Boolean = false,
    val monitorRunning: Boolean = false,
    val snapshot: MonitorSnapshot? = null,
    val positions: List<Position> = emptyList(),
    val cashBalance: Double? = null,
    val aiLoading: Boolean = false,
    val aiText: String = "设置页可粘贴配置；AI不可用时本地 E/M/R2 仍独立运行。",
    val aiStrategy: AiStrategy? = null,
    val selectedCode: String? = null,
    val dailyBars: List<DailyBar> = emptyList(),
    val minuteBars: List<MinuteBar> = emptyList(),
    val minuteFromCache: Boolean = true,
    val minuteHistorical: Boolean = false,
    val minuteSource: String = "ROOM_CACHE",
    val equityCurve: List<Pair<String, Double>> = emptyList(),
    val stockFundFlow: StockFundFlow? = null,
    val sectorFundFlow: SectorFundFlowResult? = null,
    val fundFlowLoading: Boolean = false,
    val level2: Level2Snapshot? = null,
    val level2Loading: Boolean = false,
    val tradeRecords: List<TradeRecordEntity> = emptyList(),
    val tTradePlan: TTradePlan? = null,
    val auctionPlan: AuctionPlan? = null,
    val positionPlans: List<PositionNextDayPlan> = emptyList(),
    val exposurePlan: ExposureControlPlan = ExposureControlPlan(),
    val manualBuyAnchor: Double? = null,
    val paperSummary: PaperSummary = PaperSummary(),
    val paperLoading: Boolean = false,
    val replayIndex: Int = -1,
    val replayReport: ReplayReport? = null,
    val replayRunning: Boolean = false,
    val r2ScanRows: List<R2ScanRow> = emptyList(),
    val signalStates: List<SignalStateEntity> = emptyList(),
    val signalReviewStats: SignalReviewStats = SignalReviewStats(),
    val tradeReviewStats: TradeReviewStats = TradeReviewStats(),
    val accountLedgerSummary: AccountLedgerSummary = AccountLedgerSummary(),
    val alertHistoryStats: AlertHistoryStats = AlertHistoryStats(),
    val newsRisk: NewsRiskAssessment = NewsRiskAssessment(),
    val newsLoading: Boolean = false,
    val error: String? = null
)
