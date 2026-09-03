package com.locogo.astockguard.ui.main

/*
 * 文件职责：集中描述主界面可观察状态；默认值必须表达“尚未加载”而不能伪装成真实行情或真实策略结论。
 * 架构边界：生命周期内只收集可观察状态；耗时任务、持久化和网络请求交给 ViewModel/Repository。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

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
import com.locogo.astockguard.domain.plan.ExposureBacktestReport

data class MainUiState(
    // 顶层加载与监控状态：loading 表示前台刷新，monitorRunning 表示后台盯盘服务是否运行。
    val loading: Boolean = false,
    val monitorRunning: Boolean = false,
    // 真实账户摘要。cashBalance 为空表示用户尚未配置，不能按 0 元现金计算总资产。
    val snapshot: MonitorSnapshot? = null,
    val positions: List<Position> = emptyList(),
    val cashBalance: Double? = null,
    // AI 是可选能力层；解析失败或不可用时，本地 E/M/R2 规则仍应独立工作。
    val aiLoading: Boolean = false,
    val aiText: String = "设置页可粘贴配置；AI不可用时本地 E/M/R2 仍独立运行。",
    val aiStrategy: AiStrategy? = null,
    // 当前证券及 K 线来源诊断。缓存、历史与盘中合并状态分别保存，不能只看 bars 是否非空。
    val selectedCode: String? = null,
    val dailyBars: List<DailyBar> = emptyList(),
    val dailySource: String = "UNKNOWN",
    val dailyFromCache: Boolean = true,
    val dailyRealtimeMerged: Boolean = false,
    val dailyUpdatedAt: Long = 0L,
    // 分钟线来源决定其能否参与实时动作；historical=true 的数据只用于查看和回放。
    val minuteBars: List<MinuteBar> = emptyList(),
    val minuteFromCache: Boolean = true,
    val minuteHistorical: Boolean = false,
    val minuteSource: String = "ROOM_CACHE",
    // 资金与盘口属于增强证据，缺失时基础行情仍可展示，但策略理由必须说明证据不可用。
    val equityCurve: List<Pair<String, Double>> = emptyList(),
    val stockFundFlow: StockFundFlow? = null,
    val sectorFundFlow: SectorFundFlowResult? = null,
    val fundFlowLoading: Boolean = false,
    val level2: Level2Snapshot? = null,
    val level2Loading: Boolean = false,
    // 真实成交与计划严格分离：tradeRecords 是事实，tTradePlan/auctionPlan 是尚未执行的建议。
    val tradeRecords: List<TradeRecordEntity> = emptyList(),
    val tTradePlan: TTradePlan? = null,
    val auctionPlan: AuctionPlan? = null,
    val positionPlans: List<PositionNextDayPlan> = emptyList(),
    val exposurePlan: ExposureControlPlan = ExposureControlPlan(),
    val exposureBacktest: ExposureBacktestReport = ExposureBacktestReport(),
    val exposureBacktestLoading: Boolean = false,
    // 用户从图表选中的买点只影响计划重算，不是订单价格，也不会触发券商操作。
    val manualBuyAnchor: Double? = null,
    // 模拟盘和 Replay 使用独立账户/游标，任何操作都不能写入真实持仓和真实成交表。
    val paperSummary: PaperSummary = PaperSummary(),
    val paperLoading: Boolean = false,
    val replayIndex: Int = -1,
    val replayReport: ReplayReport? = null,
    val replayRunning: Boolean = false,
    // 信号生命周期、真实交易、账户流水和提醒评价共同组成复盘区，空样本不等于 0% 胜率。
    val r2ScanRows: List<R2ScanRow> = emptyList(),
    val signalStates: List<SignalStateEntity> = emptyList(),
    val signalReviewStats: SignalReviewStats = SignalReviewStats(),
    val tradeReviewStats: TradeReviewStats = TradeReviewStats(),
    val accountLedgerSummary: AccountLedgerSummary = AccountLedgerSummary(),
    val alertHistoryStats: AlertHistoryStats = AlertHistoryStats(),
    // 新闻仅作为风险覆盖层；错误文本用于可恢复的前台提示，不存放凭据或完整服务端响应。
    val newsRisk: NewsRiskAssessment = NewsRiskAssessment(),
    val newsLoading: Boolean = false,
    val error: String? = null
)
