package com.locogo.astockguard.data.level2

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
