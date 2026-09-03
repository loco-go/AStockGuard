package com.locogo.astockguard.data.level2

/*
 * 文件职责：定义盘口档位、逐笔成交与快照契约；更新时间、接收时间、来源和模拟标记是判断证据有效性的必要字段。
 * 架构边界：解析失败、超时和字段缺失要显式返回失败或不可用状态，不能用零值伪造有效行情。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

data class Level2Level(val price: Double, val volume: Long)

data class Level2Trade(
    val time: String,
    val price: Double,
    val volume: Long,
    val side: String
)

data class Level2Snapshot(
    val code: String,
    val bids: List<Level2Level> = emptyList(),
    val asks: List<Level2Level> = emptyList(),
    val trades: List<Level2Trade> = emptyList(),
    val source: String = "NONE",
    val simulated: Boolean = false,
    val stale: Boolean = false,
    val updatedAt: Long = 0L,
    /** App实际收到快照的时间，用于连续盘口排序；服务端行情时间仍由updatedAt表示。 */
    val receivedAt: Long = 0L,
    val message: String = ""
)

interface Level2Provider {
    suspend fun snapshot(code: String, referencePrice: Double? = null): Level2Snapshot
}
