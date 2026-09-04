package com.locogo.astockguard.domain.volume

import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.data.fundflow.FundFlowPoint
import com.locogo.astockguard.data.level2.Level2Level
import com.locogo.astockguard.data.level2.Level2Snapshot
import com.locogo.astockguard.data.level2.Level2Trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 文件职责：验证盘中量价雷达Phase 2的分类、板块共振、盘口时序和缓存安全门。
 * 测试约束：样本按时间正序构造，分析器只能看到传入时点之前的数据，避免测试掩盖未来函数问题。
 */

class IntradayVolumeAnalyzerTest {
    /** 持续3至5分钟放量、站上上升VWAP、资金与板块同步时应识别为真实突破。 */
    @Test
    fun sustainedVolumeWithBoardAndBuyPressureIsRealBreakout() {
        val bars = baselineBars() + (1..5).map { index ->
            bar(20 + index, price = 100.0 + index * 0.45, avg = 99.9 + index * 0.18, volume = 220.0, amount = 220_000.0)
        }
        val sector = SectorSyncFeatures(available = true, fresh = true, boardSyncScore = 82)
        val flow = listOf(
            flow("09:50", large = 0.0, superLarge = 0.0),
            flow("09:55", large = 800_000.0, superLarge = 600_000.0)
        )

        val features = VolumeFeatureExtractor.extract("000001.SZ", bars, flow, sector = sector, dataFresh = true)
        val signal = VolumeSignalClassifier.classify(features)

        assertTrue(features.sustainedVolume)
        assertTrue(features.priceVsVwapPct > 0.0)
        assertEquals(VolumeSignalType.REAL_BREAKOUT, signal.type)
    }

    /** 单分钟脉冲爆量后连续缩量，并最终跌回VWAP，应识别为诱多风险。 */
    @Test
    fun oneMinutePulseThenDecayAndVwapFailureIsBullTrap() {
        val bars = baselineBars() + listOf(
            bar(21, 102.0, 100.8, 380.0, 380_000.0),
            bar(22, 102.2, 101.0, 170.0, 170_000.0),
            bar(23, 100.4, 101.0, 80.0, 80_000.0)
        )
        val sector = SectorSyncFeatures(available = true, fresh = true, boardSyncScore = 20)

        val features = VolumeFeatureExtractor.extract("000001.SZ", bars, sector = sector, dataFresh = true)
        val signal = VolumeSignalClassifier.classify(features)

        assertTrue(features.volumeDecay)
        assertTrue(features.failedVwapReclaim)
        assertEquals(VolumeSignalType.BULL_TRAP, signal.type)
    }

    /** 高位成交额显著放大但价格不再扩张时应优先识别为放量滞涨。 */
    @Test
    fun highAmountWithoutPriceExpansionIsExhaustion() {
        val bars = baselineBars() + bar(21, 100.0, 99.9, 600.0, 600_000.0, high = 100.3)
        val features = VolumeFeatureExtractor.extract(
            code = "000001.SZ",
            bars = bars,
            sector = SectorSyncFeatures(available = true, fresh = true, boardSyncScore = 50),
            dataFresh = true
        )

        val signal = VolumeSignalClassifier.classify(features)

        assertTrue(features.newHigh)
        assertTrue(features.amountRatio > 1.8)
        assertTrue(features.priceEfficiency <= VolumeAnalysisConfig().exhaustionEfficiencyCeiling)
        assertEquals(VolumeSignalType.EXHAUSTION, signal.type)
    }

    /** 个股、板块和多数核心同行同步上涨时，板块共振分应达到真实突破阈值。 */
    @Test
    fun sectorAndPeersRisingIncreaseBoardSyncScore() {
        val stock = returnBars(100.0, 103.0)
        val sector = returnBars(100.0, 102.0)
        val index = returnBars(100.0, 100.6)
        val peers = listOf(returnBars(100.0, 101.5), returnBars(100.0, 101.0), returnBars(100.0, 99.8))

        val result = SectorSyncAnalyzer.analyze(stock, sector, index, peers, fresh = true)

        assertTrue(result.available)
        assertNotNull(result.boardSyncScore)
        assertTrue(result.boardSyncScore!! >= 60)
        assertTrue(result.relativeStrength5m!! > 0.0)
    }

    /** 个股上涨而板块、指数和同行下跌时，应显著降低板块共振分。 */
    @Test
    fun stockRisingAloneWhileBoardFallsLowersSyncScore() {
        val result = SectorSyncAnalyzer.analyze(
            stockBars = returnBars(100.0, 103.0),
            sectorBars = returnBars(100.0, 99.0),
            indexBars = returnBars(100.0, 99.5),
            peerBars = listOf(returnBars(100.0, 98.5), returnBars(100.0, 99.0)),
            fresh = true
        )

        assertTrue(result.boardSyncScore!! < 35)
        assertTrue(result.evidence.any { it.contains("独涨") })
    }

    /** 缓存分钟数据即使形态很强也只能返回NO_SIGNAL，不能生成实时真假突破结论。 */
    @Test
    fun staleMinuteDataNeverProducesRealtimeSignal() {
        val bars = baselineBars() + (1..5).map { index ->
            bar(20 + index, 100.0 + index, 99.5 + index * 0.4, 300.0, 300_000.0)
        }
        val analysis = IntradayVolumeAnalyzer().analyze(
            code = "000001.SZ",
            bars = bars,
            sectorInput = SectorAnalysisInput(returnBars(100.0, 102.0), returnBars(100.0, 101.0), fresh = true),
            dataFresh = false,
            now = 1_000_000L
        )

        assertEquals(0, analysis.signal.confidence)
        assertEquals(VolumeSignalType.NO_SIGNAL, analysis.signal.type)
    }

    /** 两帧真实十档并带方向逐笔成交时，应使用逐笔成交确认主动买入占比。 */
    @Test
    fun multiFrameLevel2WithTradesProducesTradeConfirmedPressure() {
        val now = 1_000_000L
        val first = snapshot(now - 20_000L, bidVolume = 1_000L, askVolume = 1_200L)
        val second = snapshot(now - 5_000L, bidVolume = 1_600L, askVolume = 900L).copy(
            trades = listOf(
                Level2Trade("10:01:01", 10.01, 800L, "BUY"),
                Level2Trade("10:01:02", 10.00, 200L, "SELL")
            )
        )

        val result = Level2SequenceAnalyzer.analyze("000001.SZ", listOf(first, second), now)

        assertTrue(result.available)
        assertTrue(result.tradeConfirmed)
        assertEquals(0.8, result.activeBuyRatio!!, 0.0001)
        assertEquals(80, result.buyPressureScore)
    }

    /** 单帧、模拟或缓存盘口不得被包装成连续Level2证据。 */
    @Test
    fun singleOrInvalidLevel2FrameIsUnavailable() {
        val now = 1_000_000L
        val result = Level2SequenceAnalyzer.analyze(
            "000001.SZ",
            listOf(snapshot(now - 1_000L, 1_000L, 1_000L).copy(simulated = true)),
            now
        )

        assertFalse(result.available)
        assertFalse(result.tradeConfirmed)
    }

    /** 构造20根平稳分钟基线，确保测试中的放量倍率都相对已完成的历史窗口计算。 */
    private fun baselineBars(): List<MinuteBar> = (1..20).map { index ->
        bar(index, price = 100.0, avg = 99.8 + index * 0.005, volume = 100.0, amount = 100_000.0)
    }

    /** 构造至少六根等步长价格序列，供1分钟和5分钟相对强度同时计算。 */
    private fun returnBars(start: Double, end: Double): List<MinuteBar> = (0..6).map { index ->
        val price = start + (end - start) * index / 6.0
        bar(index + 1, price, price, 100.0, price * 100.0)
    }

    /** 创建字段口径完整的一分钟行情；high可覆盖以构造创新高但收盘滞涨场景。 */
    private fun bar(
        minute: Int,
        price: Double,
        avg: Double,
        volume: Double,
        amount: Double,
        high: Double = price
    ): MinuteBar = MinuteBar(
        time = "09:${(30 + minute).toString().padStart(2, '0')}",
        price = price,
        avgPrice = avg,
        high = high,
        low = price - 0.1,
        volume = volume,
        amount = amount
    )

    /** 创建累计资金流采样点，大单和超大单变化用于验证“推断买压”而非伪造逐笔成交。 */
    private fun flow(time: String, large: Double, superLarge: Double): FundFlowPoint = FundFlowPoint(
        time = time,
        mainNet = large + superLarge,
        smallNet = -large * 0.2,
        mediumNet = -large * 0.1,
        largeNet = large,
        superLargeNet = superLarge
    )

    /** 创建十档等量盘口快照，使测试只控制总买卖委托量而不依赖某个特定档位。 */
    private fun snapshot(time: Long, bidVolume: Long, askVolume: Long): Level2Snapshot = Level2Snapshot(
        code = "000001.SZ",
        bids = (1..10).map { Level2Level(10.0 - it * 0.01, bidVolume / 10) },
        asks = (1..10).map { Level2Level(10.0 + it * 0.01, askVolume / 10) },
        source = "TEST_LEVEL2",
        updatedAt = time,
        receivedAt = time
    )
}
