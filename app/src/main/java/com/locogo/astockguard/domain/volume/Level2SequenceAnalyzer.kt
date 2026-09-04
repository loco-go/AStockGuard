package com.locogo.astockguard.domain.volume

import com.locogo.astockguard.data.level2.Level2Level
import com.locogo.astockguard.data.level2.Level2Snapshot
import kotlin.math.roundToInt

/*
 * 文件职责：分析连续十档盘口快照和可选逐笔成交，识别持续买压、卖压以及挂单快速撤减。
 * 重要限制：快照差分只能估计挂单变化，无法区分撤单与成交；只有带方向逐笔成交才可标记 tradeConfirmed。
 */

object Level2SequenceAnalyzer {
    /**
     * 从同一证券的多帧Level2快照提取时序特征。
     *
     * 方法会过滤模拟、缓存、代码不匹配和超过最大年龄的快照，并按实际接收时间排序。
     * 有效样本不足两帧时返回 unavailable，禁止使用单帧盘口触发真假突破结论。
     */
    fun analyze(
        code: String,
        snapshots: List<Level2Snapshot>,
        now: Long,
        config: VolumeAnalysisConfig = VolumeAnalysisConfig()
    ): Level2SequenceFeatures {
        val valid = snapshots.asSequence()
            .filter { sameCode(it.code, code) }
            .filterNot { it.simulated || it.stale }
            .filter { snapshotTime(it) > 0L && now - snapshotTime(it) in 0..config.maximumLevel2AgeMs }
            .filter { it.bids.isNotEmpty() && it.asks.isNotEmpty() }
            .sortedBy(::snapshotTime)
            .toList()
        if (valid.size < config.minimumLevel2Samples) {
            return Level2SequenceFeatures(
                available = false,
                sampleCount = valid.size,
                evidence = listOf("有效Level2快照不足${config.minimumLevel2Samples}帧，盘口时序不参与分类")
            )
        }

        val latest = valid.last()
        val bidVolume = latest.bids.take(10).sumOf { it.volume.coerceAtLeast(0L) }.toDouble()
        val askVolume = latest.asks.take(10).sumOf { it.volume.coerceAtLeast(0L) }.toDouble()
        val total = bidVolume + askVolume
        val imbalance = if (total > 0.0) (bidVolume - askVolume) / total else 0.0
        val pairs = valid.zipWithNext()
        val bidCancel = pairs.map { removedRatio(it.first.bids, it.second.bids) }.averageOrNull()
        val askCancel = pairs.map { removedRatio(it.first.asks, it.second.asks) }.averageOrNull()
        val trades = valid.flatMap { it.trades }.filter { it.volume > 0L }
        val buyVolume = trades.filter { isBuy(it.side) }.sumOf { it.volume }.toDouble()
        val sellVolume = trades.filter { isSell(it.side) }.sumOf { it.volume }.toDouble()
        val tradeTotal = buyVolume + sellVolume
        val activeBuyRatio = if (tradeTotal > 0.0) buyVolume / tradeTotal else null
        val bookScore = 50.0 + imbalance * 35.0 + ((askCancel ?: 0.0) - (bidCancel ?: 0.0)) * 15.0
        val score = if (activeBuyRatio == null) {
            // 只有挂单序列时把分数向中性值收缩，避免盘口推断获得与真实逐笔相同的权重。
            (bookScore * 0.75 + 12.5).roundToInt().coerceIn(0, 100)
        } else {
            (activeBuyRatio * 100.0).roundToInt().coerceIn(0, 100)
        }

        return Level2SequenceFeatures(
            available = true,
            tradeConfirmed = activeBuyRatio != null,
            sampleCount = valid.size,
            imbalance = imbalance,
            bidCancelRatio = bidCancel,
            askCancelRatio = askCancel,
            activeBuyRatio = activeBuyRatio,
            buyPressureScore = score,
            evidence = buildList {
                add("连续${valid.size}帧十档盘口，最新委托失衡 ${"%+.2f".format(imbalance)}")
                if (activeBuyRatio != null) add("带方向逐笔成交确认，主动买入占比 ${"%.1f".format(activeBuyRatio * 100)}%")
                else add("数据源未提供逐笔成交，买卖压力仅按盘口序列推断")
                if ((bidCancel ?: 0.0) > 0.45) add("买盘挂单快速撤减，谨防挂单诱导")
            }
        )
    }

    /**
     * 估算旧快照已有价位在新快照中减少的委托比例。
     * 同价位数量减少可能来自撤单或真实成交，因此该值只作为风险辅助证据，不命名为真实撤单率。
     */
    private fun removedRatio(before: List<Level2Level>, after: List<Level2Level>): Double {
        val old = before.take(10).associate { normalizedPrice(it.price) to it.volume.coerceAtLeast(0L).toDouble() }
        val current = after.take(10).associate { normalizedPrice(it.price) to it.volume.coerceAtLeast(0L).toDouble() }
        val base = old.values.sum()
        if (base <= 0.0) return 0.0
        val removed = old.entries.sumOf { (price, volume) -> (volume - (current[price] ?: 0.0)).coerceAtLeast(0.0) }
        return (removed / base).coerceIn(0.0, 1.0)
    }

    /** 使用万分之一元精度生成稳定价位键，避免Double微小误差破坏相邻快照的同价匹配。 */
    private fun normalizedPrice(price: Double): Long = (price * 10_000.0).roundToInt().toLong()

    /** 优先使用App接收时间排列快照；旧数据没有接收时间时退回供应商更新时间。 */
    private fun snapshotTime(snapshot: Level2Snapshot): Long = snapshot.receivedAt.takeIf { it > 0L } ?: snapshot.updatedAt

    /** 比较标准化后的六位代码和交易所后缀，防止把其他证券盘口混入当前分析。 */
    private fun sameCode(left: String, right: String): Boolean = left.trim().uppercase() == right.trim().uppercase()

    /** 兼容供应商常见的B、BUY和中文买入方向标记。 */
    private fun isBuy(side: String): Boolean = side.trim().uppercase() in setOf("B", "BUY", "买", "买入")

    /** 兼容供应商常见的S、SELL和中文卖出方向标记。 */
    private fun isSell(side: String): Boolean = side.trim().uppercase() in setOf("S", "SELL", "卖", "卖出")

    /** 空集合不应被 Kotlin 的 average() 转成 NaN，因此统一转换为可空结果。 */
    private fun List<Double>.averageOrNull(): Double? = takeIf { it.isNotEmpty() }?.average()
}
