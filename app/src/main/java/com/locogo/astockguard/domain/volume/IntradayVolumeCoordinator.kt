package com.locogo.astockguard.domain.volume

import com.locogo.astockguard.MarketAssessment
import com.locogo.astockguard.MarketRepository
import com.locogo.astockguard.Position
import com.locogo.astockguard.data.fundflow.FundFlowRepository
import com.locogo.astockguard.data.fundflow.StockFundFlow
import com.locogo.astockguard.data.level2.Level2Repository
import com.locogo.astockguard.domain.trading.DynamicTPlan
import com.locogo.astockguard.domain.trading.DynamicTPlanner
import com.locogo.astockguard.domain.trading.TradingRiskContext
import com.locogo.astockguard.domain.trading.TradingRiskLevel

/*
 * 文件职责：复用现有Repository组合盘中量价分析所需数据，并向UI和后台服务提供同一份领域结果。
 * 架构边界：本类不持有View或Android Context；所有远端/Room选择仍由既有Repository负责。
 */

/** 协调器输出，保留分钟序列和资金流以供详情页现有图表、旧策略回看继续复用。 */
data class IntradayRadarSnapshot(
    val minuteSeries: MarketRepository.MinuteSeries,
    val fundFlow: StockFundFlow?,
    val analysis: IntradayVolumeAnalysis,
    val plan: DynamicTPlan,
    val dataSource: String
)

class IntradayVolumeCoordinator(
    private val marketRepository: MarketRepository,
    private val fundFlowRepository: FundFlowRepository,
    private val level2Repository: Level2Repository,
    private val analyzer: IntradayVolumeAnalyzer = IntradayVolumeAnalyzer()
) {
    /**
     * 加载一只证券当前交易日的完整雷达快照。
     *
     * 分钟行情是实时动作的必需输入；资金流和Level2属于增强证据，失败时允许降级但会降低置信度。
     * 当前板块源尚无可靠个股映射，因此明确传入 unavailable，绝不使用市场指数冒充所属板块。
     */
    suspend fun load(
        code: String,
        position: Position?,
        assessment: MarketAssessment?,
        exposureStatus: String? = null,
        marketDataStale: Boolean = false,
        now: Long = System.currentTimeMillis()
    ): IntradayRadarSnapshot {
        val minuteSeries = marketRepository.loadMinuteSeries(code)
        val fundFlow = runCatching { fundFlowRepository.stock(code) }.getOrNull()
        val referencePrice = minuteSeries.bars.lastOrNull()?.price
        val currentLevel2 = runCatching { level2Repository.snapshot(code, referencePrice) }.getOrNull()
        val level2History = if (currentLevel2?.simulated == false && !currentLevel2.stale) {
            runCatching { level2Repository.recentSnapshots(code, now) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val snapshots = (level2History + listOfNotNull(currentLevel2))
            .distinctBy { it.receivedAt.takeIf { time -> time > 0L } ?: it.updatedAt }
        val dataFresh = !marketDataStale && !minuteSeries.fromCache && !minuteSeries.isHistorical
        val analysis = analyzer.analyze(
            code = code,
            bars = minuteSeries.bars,
            fundFlow = fundFlow?.minute?.takeUnless { fundFlow.minuteStale }.orEmpty(),
            level2Snapshots = snapshots,
            sectorInput = SectorAnalysisInput(fresh = false),
            dataFresh = dataFresh,
            now = now
        )
        val risk = mapRiskContext(assessment, exposureStatus, dataFresh)
        val plan = DynamicTPlanner.plan(position, minuteSeries.bars, analysis.features, analysis.signal, risk)
        return IntradayRadarSnapshot(
            minuteSeries = minuteSeries,
            fundFlow = fundFlow,
            analysis = analysis,
            plan = plan,
            dataSource = buildString {
                append(minuteSeries.source)
                append("+FUND_").append(fundFlow?.source ?: "NONE")
                append("+L2_").append(currentLevel2?.source ?: "NONE")
                append("+SECTOR_UNAVAILABLE")
            }
        )
    }

    /**
     * 将现有E/M风险和组合减仓状态适配为动态T风险等级，而不修改RiskEngine既有确定性输出。
     * 优先级为缓存阻断、组合减仓、E2冲击、M1下行防守，其他状态保持NORMAL。
     */
    internal fun mapRiskContext(
        assessment: MarketAssessment?,
        exposureStatus: String?,
        dataFresh: Boolean
    ): TradingRiskContext {
        if (!dataFresh) return TradingRiskContext(false, TradingRiskLevel.RISK_LOCKED, false, "实时数据不可用")
        val level = when {
            exposureStatus == "REDUCE_EXPOSURE" -> TradingRiskLevel.REDUCE_EXPOSURE
            assessment?.eventRisk == "E2" -> TradingRiskLevel.SHOCK_DOWN
            assessment?.marketPhase in setOf("M0", "M1") -> TradingRiskLevel.TREND_DOWN
            else -> TradingRiskLevel.NORMAL
        }
        return TradingRiskContext(
            dataFresh = true,
            marketLevel = level,
            allowBuyback = level == TradingRiskLevel.NORMAL,
            reason = when (level) {
                TradingRiskLevel.REDUCE_EXPOSURE -> "组合仓位超过风险上限"
                TradingRiskLevel.SHOCK_DOWN -> "市场事件风险为E2"
                TradingRiskLevel.TREND_DOWN -> "市场处于防守阶段"
                TradingRiskLevel.RISK_LOCKED -> "账户风险锁定"
                TradingRiskLevel.NORMAL -> "风险门禁允许观察动态T"
            }
        )
    }
}
