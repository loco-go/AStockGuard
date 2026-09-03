package com.locogo.astockguard.domain.strategy

/*
 * 文件职责：定义策略动作、总分和分项证据契约；不可用输入不得用零分伪装为中性证据。
 * 架构边界：领域层不直接访问 Android View；需要网络或 Room 的输入由 Repository 在调用前准备。
 * 风险说明：本应用只提供交易研究和决策辅助，不保证收益，也不会自动提交真实账户委托。
 */

data class StrategyScore(
    val trendScore: Int,
    val volumeScore: Int,
    val capitalScore: Int,
    val positionScore: Int,
    val totalScore: Int,
    val capitalAvailable: Boolean,
    val strategyVersion: String = StrategyVersions.CURRENT,
    val dataCompletenessPct: Int = if (capitalAvailable) 100 else 80,
    val factors: List<StrategyFactorScore> = emptyList()
)

data class StrategyFactorScore(
    val key: String,
    val name: String,
    val score: Int,
    val weightPct: Int,
    val available: Boolean,
    val evidence: String
)

object StrategyVersions {
    const val CURRENT = "V4.2.0"
    const val LEGACY = "LEGACY"
}

enum class ChartSignalAction { BUY, SELL, RISK }

data class ChartSignal(
    val timestamp: Long,
    val price: Double,
    val action: ChartSignalAction,
    val score: Int,
    val reason: String,
    val strategyVersion: String = StrategyVersions.CURRENT
)

data class StrategyScoreResult(
    val score: StrategyScore,
    val reasons: List<String>,
    val signal: ChartSignal?
)
