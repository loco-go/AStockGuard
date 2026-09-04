package com.locogo.astockguard.domain.volume

import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.data.fundflow.FundFlowPoint
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * 文件职责：把原始一分钟行情、资金流、板块和盘口输入转换为可复现的量价特征。
 * 无未来数据约束：所有滚动基线均排除当前观察段，调用者在Replay中只需传入当前索引之前的切片。
 */

object VolumeFeatureExtractor {
    /**
     * 提取当前最后一分钟的完整特征。
     *
     * 无效价格和非有限数值会被过滤；样本不足时仍返回可审计的低置信度结果，由分类器输出 NO_SIGNAL。
     * 资金流为数据商累计净额，只能推断大单方向，不能产生真实主动买入占比。
     */
    fun extract(
        code: String,
        bars: List<MinuteBar>,
        fundFlow: List<FundFlowPoint> = emptyList(),
        level2: Level2SequenceFeatures = Level2SequenceFeatures(),
        sector: SectorSyncFeatures = SectorSyncFeatures(),
        dataFresh: Boolean,
        config: VolumeAnalysisConfig = VolumeAnalysisConfig()
    ): VolumeFeatures {
        val validBars = bars.filter(::isValidBar)
        if (validBars.isEmpty()) return emptyFeatures(code, dataFresh, level2, sector)
        val latest = validBars.last()
        val ratio1 = rollingRatio(validBars, 1, config.rollingWindow) { it.volume }
        val ratio3 = rollingRatio(validBars, config.shortWindow, config.rollingWindow) { it.volume }
        val ratio5 = rollingRatio(validBars, config.mediumWindow, config.rollingWindow) { it.volume }
        val amountRatio = rollingRatio(validBars, 1, config.rollingWindow) { it.amount }
        val recentMinuteRatios = lastMinuteRatios(validBars, 3, config.rollingWindow) { it.volume }
        val volumeDecay = recentMinuteRatios.size == 3 &&
            recentMinuteRatios[0] >= config.pulseVolumeRatio &&
            recentMinuteRatios[0] > recentMinuteRatios[1] &&
            recentMinuteRatios[1] > recentMinuteRatios[2] &&
            recentMinuteRatios[2] <= config.decayEndRatio
        val sustainedVolume = ratio3 >= config.sustainedVolume3mRatio && ratio5 >= config.sustainedVolume5mRatio
        val price1 = returnPct(validBars, 1)
        val price5 = returnPct(validBars, 5)
        val vwap = latest.avgPrice.takeIf { it.isFinite() && it > 0.0 } ?: latest.price
        val priceVsVwap = percentChange(latest.price, vwap)
        val vwapReference = validBars.getOrNull(validBars.lastIndex - config.mediumWindow)?.avgPrice
            ?.takeIf { it.isFinite() && it > 0.0 } ?: vwap
        val vwapSlope = percentChange(vwap, vwapReference)
        val recentTrendBars = validBars.takeLast(config.trendWindow)
        val minutesAboveVwap = recentTrendBars.count { bar ->
            bar.avgPrice > 0.0 && percentChange(bar.price, bar.avgPrice) > config.vwapTolerancePct
        }
        val pullbacks = countVwapPullbacks(recentTrendBars, config.vwapTolerancePct)
        val failedReclaim = hasFailedVwapReclaim(recentTrendBars, config.vwapTolerancePct)
        val previousHigh = validBars.dropLast(1).takeLast(config.rollingWindow).maxOfOrNull { it.high }
        val newHigh = previousHigh != null && latest.high >= previousHigh && price1 >= 0.0
        val priceEfficiency = priceEfficiency(price5, amountRatio)
        val fundPressure = inferFundPressure(fundFlow, config.mediumWindow)
        val buyPressure = combinedBuyPressure(fundPressure.score, level2.buyPressureScore, level2.tradeConfirmed)
        val volumeScore = volumeScore(ratio1, ratio3, ratio5, amountRatio, volumeDecay)
        val trendScore = trendScore(price5, priceVsVwap, vwapSlope, minutesAboveVwap, recentTrendBars.size)
        val bullTrapRisk = bullTrapRisk(
            volumeDecay = volumeDecay,
            failedVwapReclaim = failedReclaim,
            amountRatio = amountRatio,
            priceEfficiency = priceEfficiency,
            buyPressureScore = buyPressure,
            boardSyncScore = sector.boardSyncScore
        )
        val confidence = confidence(dataFresh, validBars.size, fundPressure.score != null, level2, sector, config)

        return VolumeFeatures(
            code = code,
            sampleCount = validBars.size,
            dataFresh = dataFresh,
            volumeRatio1m = ratio1,
            volumeRatio3m = ratio3,
            volumeRatio5m = ratio5,
            amountRatio = amountRatio,
            volumeDecay = volumeDecay,
            sustainedVolume = sustainedVolume,
            priceChange1mPct = price1,
            priceChange5mPct = price5,
            priceEfficiency = priceEfficiency,
            priceVsVwapPct = priceVsVwap,
            vwapSlopePct = vwapSlope,
            minutesAboveVwap = minutesAboveVwap,
            pullbackToVwapCount = pullbacks,
            failedVwapReclaim = failedReclaim,
            newHigh = newHigh,
            largeOrderNet = fundPressure.largeNet,
            superLargeOrderNet = fundPressure.superLargeNet,
            inferredFundBuyPressure = fundPressure.score,
            level2 = level2,
            sector = sector,
            volumeScore = volumeScore,
            buyPressureScore = buyPressure,
            trendScore = trendScore,
            bullTrapRisk = bullTrapRisk,
            confidence = confidence,
            evidence = buildEvidence(ratio1, ratio3, ratio5, amountRatio, volumeDecay, sustainedVolume,
                priceVsVwap, vwapSlope, failedReclaim, fundPressure, level2, sector)
        )
    }

    /** 当没有任何有效分钟线时构造零置信度快照，保留数据缺失事实供日志和测试检查。 */
    private fun emptyFeatures(
        code: String,
        dataFresh: Boolean,
        level2: Level2SequenceFeatures,
        sector: SectorSyncFeatures
    ) = VolumeFeatures(
        code = code, sampleCount = 0, dataFresh = dataFresh, volumeRatio1m = 0.0,
        volumeRatio3m = 0.0, volumeRatio5m = 0.0, amountRatio = 0.0, volumeDecay = false,
        sustainedVolume = false, priceChange1mPct = 0.0, priceChange5mPct = 0.0,
        priceEfficiency = 0, priceVsVwapPct = 0.0, vwapSlopePct = 0.0, minutesAboveVwap = 0,
        pullbackToVwapCount = 0, failedVwapReclaim = false, newHigh = false, level2 = level2,
        sector = sector, volumeScore = 0, buyPressureScore = 50, trendScore = 0,
        bullTrapRisk = 0, confidence = 0, evidence = listOf("没有有效分钟行情")
    )

    /** 验证价格、均价、成交量和成交额是否可参与计算；负成交数据会被视为供应商异常。 */
    private fun isValidBar(bar: MinuteBar): Boolean = bar.price.isFinite() && bar.price > 0.0 &&
        bar.avgPrice.isFinite() && bar.avgPrice >= 0.0 && bar.volume.isFinite() && bar.volume >= 0.0 &&
        bar.amount.isFinite() && bar.amount >= 0.0

    /**
     * 计算最近 segmentSize 根的均值相对其前方 rollingWindow 根均值的倍率。
     * 基线明确排除观察段，避免当前爆量同时抬高自身基线；可用历史不足时使用已有历史但至少要求一根。
     */
    private fun rollingRatio(
        bars: List<MinuteBar>,
        segmentSize: Int,
        rollingWindow: Int,
        selector: (MinuteBar) -> Double
    ): Double {
        if (bars.size <= segmentSize) return 1.0
        val segmentStart = bars.size - segmentSize
        val segment = bars.subList(segmentStart, bars.size).map(selector).filter { it > 0.0 }
        val baseline = bars.subList((segmentStart - rollingWindow).coerceAtLeast(0), segmentStart)
            .map(selector).filter { it > 0.0 }
        if (segment.isEmpty() || baseline.isEmpty()) return 1.0
        return (segment.average() / baseline.average()).takeIf { it.isFinite() }?.coerceIn(0.0, 20.0) ?: 1.0
    }

    /** 为脉冲衰减判断计算最后若干单分钟倍率，每一分钟均只使用它之前的滚动基线。 */
    private fun lastMinuteRatios(
        bars: List<MinuteBar>,
        count: Int,
        rollingWindow: Int,
        selector: (MinuteBar) -> Double
    ): List<Double> {
        if (bars.size <= count) return emptyList()
        return (bars.size - count until bars.size).map { index ->
            val value = selector(bars[index])
            val baseline = bars.subList((index - rollingWindow).coerceAtLeast(0), index)
                .map(selector).filter { it > 0.0 }
            if (value <= 0.0 || baseline.isEmpty()) 1.0 else (value / baseline.average()).coerceIn(0.0, 20.0)
        }
    }

    /** 计算最近 window 根收盘价收益率，单位为百分点；样本不足时返回0并由置信度反映不足。 */
    private fun returnPct(bars: List<MinuteBar>, window: Int): Double {
        if (bars.size <= window) return 0.0
        return percentChange(bars.last().price, bars[bars.lastIndex - window].price)
    }

    /** 统一计算 current 相对 reference 的百分点变化，reference无效时安全返回0。 */
    private fun percentChange(current: Double, reference: Double): Double =
        if (reference.isFinite() && reference > 0.0 && current.isFinite()) (current / reference - 1.0) * 100.0 else 0.0

    /**
     * 统计从VWAP上方回落至容差区、随后重新站上的完整次数。
     * 只有完成“上方→附近→重新上方”三段结构才计数，单纯跌破不会误记为成功回踩。
     */
    private fun countVwapPullbacks(bars: List<MinuteBar>, tolerancePct: Double): Int {
        if (bars.size < 3) return 0
        return (1 until bars.lastIndex).count { index ->
            val before = percentChange(bars[index - 1].price, bars[index - 1].avgPrice)
            val current = abs(percentChange(bars[index].price, bars[index].avgPrice))
            val after = percentChange(bars[index + 1].price, bars[index + 1].avgPrice)
            before > tolerancePct && current <= tolerancePct && after > tolerancePct
        }
    }

    /** 判断近期曾站上VWAP但最后已明显跌破，作为拉升后反抽失败的保守代理特征。 */
    private fun hasFailedVwapReclaim(bars: List<MinuteBar>, tolerancePct: Double): Boolean {
        if (bars.size < 3) return false
        val hadAbove = bars.dropLast(1).any { percentChange(it.price, it.avgPrice) > tolerancePct }
        val latestBelow = percentChange(bars.last().price, bars.last().avgPrice) < -tolerancePct
        return hadAbove && latestBelow
    }

    /**
     * 将5分钟价格扩张幅度除以成交额倍率后归一到0..100。
     * 0代表成交没有推动价格扩张；分数越高代表每单位相对成交额带来的价格位移越明显。
     * 涨跌方向由 priceChange5mPct 独立保存，避免把“高效下跌”错误解释成上涨强度。
     */
    private fun priceEfficiency(priceChangePct: Double, amountRatio: Double): Int {
        val normalized = abs(priceChangePct) / amountRatio.coerceAtLeast(0.25)
        return (normalized * 25.0).roundToInt().coerceIn(0, 100)
    }

    /** 根据多周期量比与成交额倍率生成量能真实性分；脉冲后快速衰减会受到明确扣分。 */
    private fun volumeScore(ratio1: Double, ratio3: Double, ratio5: Double, amountRatio: Double, decay: Boolean): Int {
        val raw = 35.0 + ratio1.coerceAtMost(3.0) * 8.0 + ratio3.coerceAtMost(3.0) * 10.0 +
            ratio5.coerceAtMost(3.0) * 7.0 + amountRatio.coerceAtMost(3.0) * 5.0 - if (decay) 28.0 else 0.0
        return raw.roundToInt().coerceIn(0, 100)
    }

    /** 综合价格扩张、VWAP位置、VWAP斜率和站稳时长生成0..100趋势分。 */
    private fun trendScore(price5: Double, priceVsVwap: Double, slope: Double, above: Int, observed: Int): Int {
        val persistence = if (observed > 0) above.toDouble() / observed else 0.0
        return (48.0 + price5 * 7.0 + priceVsVwap * 10.0 + slope * 18.0 + persistence * 18.0)
            .roundToInt().coerceIn(0, 100)
    }

    /**
     * 从累计大单/超大单净额的相邻增量推断资金方向。
     * 由于缺少总主动买卖成交额，该分数命名为 inferred，绝不输出伪造的 activeBuyRatio。
     */
    private fun inferFundPressure(points: List<FundFlowPoint>, window: Int): FundPressure {
        val recent = points.takeLast(window.coerceAtLeast(2))
        if (recent.size < 2) return FundPressure()
        val first = recent.first()
        val last = recent.last()
        val large = last.largeNet - first.largeNet
        val superLarge = last.superLargeNet - first.superLargeNet
        val small = last.smallNet - first.smallNet
        val medium = last.mediumNet - first.mediumNet
        val gross = abs(large) + abs(superLarge) + abs(small) + abs(medium)
        val direction = if (gross > 0.0) (large + superLarge) / gross else 0.0
        return FundPressure(
            score = (50.0 + direction.coerceIn(-1.0, 1.0) * 50.0).roundToInt().coerceIn(0, 100),
            largeNet = large,
            superLargeNet = superLarge
        )
    }

    /** 逐笔盘口优先级高于累计资金流；没有逐笔时盘口推断和资金推断采用较低权重组合。 */
    private fun combinedBuyPressure(fund: Int?, book: Int?, tradeConfirmed: Boolean): Int = when {
        tradeConfirmed && book != null -> ((book * 0.7) + ((fund ?: 50) * 0.3)).roundToInt()
        book != null && fund != null -> ((book * 0.4) + (fund * 0.6)).roundToInt()
        fund != null -> fund
        book != null -> book
        else -> 50
    }.coerceIn(0, 100)

    /** 按衰减、VWAP失败、效率、买压和板块独涨风险累计诱多分，各证据缺失时不擅自扣分。 */
    private fun bullTrapRisk(
        volumeDecay: Boolean,
        failedVwapReclaim: Boolean,
        amountRatio: Double,
        priceEfficiency: Int,
        buyPressureScore: Int,
        boardSyncScore: Int?
    ): Int {
        var risk = 12
        if (volumeDecay) risk += 35
        if (failedVwapReclaim) risk += 28
        if (amountRatio >= 1.8 && priceEfficiency < 45) risk += 18
        if (buyPressureScore < 42) risk += 12
        if (boardSyncScore != null && boardSyncScore < 35) risk += 15
        return risk.coerceIn(0, 100)
    }

    /**
     * 按可验证的数据维度计算置信度；缓存数据直接归零，防止高分结构绕过实时安全门。
     * 分钟价量是基础35分，资金、板块、盘口按各自可用性增量计分，逐笔成交再提供额外确认分。
     */
    private fun confidence(
        fresh: Boolean,
        samples: Int,
        hasFund: Boolean,
        level2: Level2SequenceFeatures,
        sector: SectorSyncFeatures,
        config: VolumeAnalysisConfig
    ): Int {
        if (!fresh) return 0
        var score = if (samples >= config.minimumSamples) 50 else samples * 50 / config.minimumSamples
        if (hasFund) score += 15
        if (sector.available && sector.fresh) score += 18
        if (level2.available) score += 10
        if (level2.tradeConfirmed) score += 7
        return score.coerceIn(0, 100)
    }

    /** 汇总正反证据，所有文本均说明数据口径，便于UI直接解释而不重复推导策略逻辑。 */
    private fun buildEvidence(
        ratio1: Double,
        ratio3: Double,
        ratio5: Double,
        amountRatio: Double,
        decay: Boolean,
        sustained: Boolean,
        priceVsVwap: Double,
        slope: Double,
        failedReclaim: Boolean,
        fund: FundPressure,
        level2: Level2SequenceFeatures,
        sector: SectorSyncFeatures
    ): List<String> = buildList {
        add("量比：1分钟 ${"%.2f".format(ratio1)}、3分钟 ${"%.2f".format(ratio3)}、5分钟 ${"%.2f".format(ratio5)}")
        add("成交额倍率 ${"%.2f".format(amountRatio)}")
        if (sustained) add("连续3至5分钟保持放量")
        if (decay) add("首分钟脉冲放量后连续衰减")
        add("价格相对VWAP ${"%+.2f%%".format(priceVsVwap)}，VWAP斜率 ${"%+.2f%%".format(slope)}")
        if (failedReclaim) add("拉升后跌回VWAP下方，反抽结构失败")
        if (fund.score != null) add("大单资金为累计净额增量推断，非逐笔主动成交")
        addAll(level2.evidence)
        addAll(sector.evidence)
    }

    /** 内部资金推断结果，避免把大单净额与推断分数作为多个松散返回值传递。 */
    private data class FundPressure(
        val score: Int? = null,
        val largeNet: Double? = null,
        val superLargeNet: Double? = null
    )
}
