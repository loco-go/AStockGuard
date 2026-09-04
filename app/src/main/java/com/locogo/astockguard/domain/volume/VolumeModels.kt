package com.locogo.astockguard.domain.volume

/*
 * 文件职责：定义盘中量价雷达在特征提取、分类、动态做T和展示层之间传递的稳定领域契约。
 * 设计约束：缺失数据使用 null 或 available=false 明确表达，禁止用 0 分伪装成真实的中性数据。
 * 风险说明：本模块只生成研究和决策辅助结论，不提交任何真实账户委托，也不承诺策略收益。
 */

/** 盘中量价结构的统一分类；枚举顺序不代表风险或交易优先级。 */
enum class VolumeSignalType {
    REAL_BREAKOUT,
    WEAK_BREAKOUT,
    BULL_TRAP,
    EXHAUSTION,
    NORMAL,
    NO_SIGNAL
}

/**
 * 股票交易风格。后续动态T规划器会按风格选择不同参数组，避免把趋势股规则直接套到情绪股。
 */
enum class StockTradingStyle {
    CORE_TREND,
    TREND,
    RECOVERY,
    ARBITRAGE,
    EMOTION
}

/**
 * Level2 连续快照产生的时序特征。
 *
 * @property available 是否至少具备两帧同证券、非模拟、未过期快照。
 * @property tradeConfirmed 是否存在可以按买卖方向统计的真实逐笔成交；为 false 时盘口只能作为推断证据。
 * @property imbalance 最新十档买卖委托失衡，范围 -1..1，正值表示买盘挂单更多。
 * @property bidCancelRatio 相邻快照中原买档被撤减的比例估计值，不能等同于交易所真实撤单率。
 * @property askCancelRatio 相邻快照中原卖档被撤减的比例估计值，不能等同于交易所真实撤单率。
 * @property activeBuyRatio 仅在存在带方向逐笔成交时提供，范围 0..1。
 * @property buyPressureScore 综合逐笔成交和盘口序列得到的 0..100 买压分。
 */
data class Level2SequenceFeatures(
    val available: Boolean = false,
    val tradeConfirmed: Boolean = false,
    val sampleCount: Int = 0,
    val imbalance: Double? = null,
    val bidCancelRatio: Double? = null,
    val askCancelRatio: Double? = null,
    val activeBuyRatio: Double? = null,
    val buyPressureScore: Int? = null,
    val evidence: List<String> = emptyList()
)
/**
 * 板块、同行和市场指数与个股的同步结果。
 * 相对强度统一使用百分点，例如个股上涨 2%、板块上涨 1% 时结果为 1.0，而不是 0.01。
 */
data class SectorSyncFeatures(
    val available: Boolean = false,
    val fresh: Boolean = false,
    val boardSyncScore: Int? = null,
    val relativeStrength1m: Double? = null,
    val relativeStrength5m: Double? = null,
    val relativeStrength15m: Double? = null,
    val relativeStrength60m: Double? = null,
    val evidence: List<String> = emptyList()
)

/**
 * 分类器使用的完整量价特征快照。所有字段只允许由传入时点及其之前的数据计算，禁止引用未来K线。
 */
data class VolumeFeatures(
    val code: String,
    val sampleCount: Int,
    val dataFresh: Boolean,
    val volumeRatio1m: Double,
    val volumeRatio3m: Double,
    val volumeRatio5m: Double,
    val amountRatio: Double,
    val volumeDecay: Boolean,
    val sustainedVolume: Boolean,
    val priceChange1mPct: Double,
    val priceChange5mPct: Double,
    val priceEfficiency: Int,
    val priceVsVwapPct: Double,
    val vwapSlopePct: Double,
    val minutesAboveVwap: Int,
    val pullbackToVwapCount: Int,
    val failedVwapReclaim: Boolean,
    val newHigh: Boolean,
    val largeOrderNet: Double? = null,
    val superLargeOrderNet: Double? = null,
    val inferredFundBuyPressure: Int? = null,
    val level2: Level2SequenceFeatures = Level2SequenceFeatures(),
    val sector: SectorSyncFeatures = SectorSyncFeatures(),
    val volumeScore: Int,
    val buyPressureScore: Int,
    val trendScore: Int,
    val bullTrapRisk: Int,
    val confidence: Int,
    val evidence: List<String> = emptyList()
)

/**
 * 分类器输出。reasonCodes 使用稳定英文键便于持久化和回测，explanations 保存面向用户的中文解释。
 */
data class VolumeSignal(
    val type: VolumeSignalType,
    val volumeScore: Int,
    val buyPressureScore: Int,
    val priceEfficiency: Int,
    val boardSyncScore: Int?,
    val trendScore: Int,
    val bullTrapRisk: Int,
    val confidence: Int,
    val reasonCodes: List<String>,
    val explanations: List<String>
)
