package com.locogo.astockguard.domain.volume

import com.locogo.astockguard.MinuteBar
import kotlin.math.roundToInt

/*
 * 文件职责：把个股、所属板块、核心同行和市场指数的分钟收益对齐为可解释的板块共振与相对强度特征。
 * 数据边界：本类不请求网络、不读数据库；Repository 必须在调用前完成证券映射、新鲜度校验与分钟序列加载。
 */

object SectorSyncAnalyzer {
    /**
     * 计算当前时点的板块共振。
     *
     * 算法只读取每条输入序列的最后 N 根及以前数据，因此同一方法可安全用于实时分析和逐根 Replay。
     * 板块序列缺失或被标记为缓存时返回 unavailable，而不是把板块同步分数写成 0。
     */
    fun analyze(
        stockBars: List<MinuteBar>,
        sectorBars: List<MinuteBar>,
        indexBars: List<MinuteBar>,
        peerBars: List<List<MinuteBar>> = emptyList(),
        fresh: Boolean
    ): SectorSyncFeatures {
        if (!fresh || stockBars.size < 2 || sectorBars.size < 2 || indexBars.size < 2) {
            return SectorSyncFeatures(
                available = false,
                fresh = false,
                evidence = listOf("板块或指数分钟数据缺失/过期，板块共振不参与实时分类")
            )
        }

        val stockReturns = WINDOWS.associateWith { returnPct(stockBars, it) }
        val sectorReturns = WINDOWS.associateWith { returnPct(sectorBars, it) }
        val indexReturns = WINDOWS.associateWith { returnPct(indexBars, it) }
        val relative = WINDOWS.associateWith { window ->
            val stock = stockReturns[window]
            val sector = sectorReturns[window]
            val index = indexReturns[window]
            if (stock == null || sector == null || index == null) null else stock - (sector * 0.7 + index * 0.3)
        }

        val latestStock = stockReturns[5] ?: stockReturns[1] ?: 0.0
        val latestSector = sectorReturns[5] ?: sectorReturns[1] ?: 0.0
        val validPeers = peerBars.mapNotNull { returnPct(it, 5) }
        val peerUpRatio = validPeers.takeIf { it.isNotEmpty() }?.count { it > 0.0 }?.toDouble()?.div(validPeers.size)
        val directionScore = when {
            latestStock > 0.0 && latestSector > 0.0 -> 78.0
            latestStock < 0.0 && latestSector < 0.0 -> 62.0
            latestStock > 0.0 && latestSector <= 0.0 -> 24.0
            else -> 45.0
        }
        val peerAdjustment = peerUpRatio?.let { (it - 0.5) * 30.0 } ?: 0.0
        val boardScore = (directionScore + peerAdjustment).roundToInt().coerceIn(0, 100)

        return SectorSyncFeatures(
            available = true,
            fresh = true,
            boardSyncScore = boardScore,
            relativeStrength1m = relative[1],
            relativeStrength5m = relative[5],
            relativeStrength15m = relative[15],
            relativeStrength60m = relative[60],
            evidence = buildList {
                add("个股5分钟涨跌 ${formatPct(latestStock)}，板块 ${formatPct(latestSector)}")
                if (peerUpRatio != null) add("核心同行5分钟上涨比例 ${(peerUpRatio * 100).roundToInt()}%")
                if (latestStock > 0.0 && latestSector <= 0.0) add("个股独涨但板块未同步，共振分数降低")
                if (latestStock > 0.0 && latestSector > 0.0) add("个股与板块同向上涨")
            }
        )
    }

    /**
     * 计算最近 window 根分钟线的收益率，单位为百分点。
     * 样本不足、起点价格无效或终点价格无效时返回 null，调用方据此降低数据完整度。
     */
    private fun returnPct(bars: List<MinuteBar>, window: Int): Double? {
        if (bars.size <= window) return null
        val start = bars[bars.lastIndex - window].price
        val end = bars.last().price
        if (!start.isFinite() || !end.isFinite() || start <= 0.0 || end <= 0.0) return null
        return (end / start - 1.0) * 100.0
    }

    /** 将百分点格式化为带正负号的中文证据文本，避免各调用方重复决定展示精度。 */
    private fun formatPct(value: Double): String = "%+.2f%%".format(value)

    private val WINDOWS = listOf(1, 5, 15, 60)
}
