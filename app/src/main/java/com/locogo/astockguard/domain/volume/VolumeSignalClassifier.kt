package com.locogo.astockguard.domain.volume

/*
 * 文件职责：依据已提取的客观特征生成真假拉升分类；本类不计算价格区间，也不产生买卖委托。
 * 决策顺序：数据安全门优先，其次放量滞涨、诱多、真实突破、弱突破，最后回落到普通状态。
 */

object VolumeSignalClassifier {
    /**
     * 对单个时点进行确定性分类，相同特征和配置必然产生相同结果。
     * 缓存数据或基础样本不足直接返回 NO_SIGNAL，避免局部高分绕过数据质量中心。
     */
    fun classify(
        features: VolumeFeatures,
        config: VolumeAnalysisConfig = VolumeAnalysisConfig()
    ): VolumeSignal {
        val reasons = mutableListOf<String>()
        val type = when {
            !features.dataFresh || features.sampleCount < config.minimumSamples -> {
                reasons += "DATA_NOT_REALTIME"
                VolumeSignalType.NO_SIGNAL
            }
            isExhaustion(features, config) -> {
                reasons += listOf("HIGH_AMOUNT", "LOW_PRICE_EFFICIENCY", "HIGH_LEVEL_STALL")
                VolumeSignalType.EXHAUSTION
            }
            isBullTrap(features, config) -> {
                if (features.volumeDecay) reasons += "VOLUME_DECAY"
                if (features.failedVwapReclaim) reasons += "VWAP_RECLAIM_FAILED"
                if ((features.sector.boardSyncScore ?: 100) < config.realBreakoutBoardSync) reasons += "BOARD_NOT_SYNCED"
                VolumeSignalType.BULL_TRAP
            }
            isRealBreakout(features, config) -> {
                reasons += listOf("SUSTAINED_VOLUME", "ABOVE_RISING_VWAP", "BUY_PRESSURE_CONFIRMED", "BOARD_SYNCED")
                VolumeSignalType.REAL_BREAKOUT
            }
            isWeakBreakout(features, config) -> {
                reasons += "BREAKOUT_NOT_CONFIRMED"
                VolumeSignalType.WEAK_BREAKOUT
            }
            else -> {
                reasons += "NO_MATERIAL_ANOMALY"
                VolumeSignalType.NORMAL
            }
        }
        return VolumeSignal(
            type = type,
            volumeScore = features.volumeScore,
            buyPressureScore = features.buyPressureScore,
            priceEfficiency = features.priceEfficiency,
            boardSyncScore = features.sector.boardSyncScore,
            trendScore = features.trendScore,
            bullTrapRisk = features.bullTrapRisk,
            confidence = features.confidence,
            reasonCodes = reasons,
            explanations = explanations(type, features)
        )
    }

    /** 放量滞涨要求高位或创新高、成交额显著放大且价格推动效率下降，避免把普通低位放量误判为派发。 */
    private fun isExhaustion(features: VolumeFeatures, config: VolumeAnalysisConfig): Boolean =
        features.newHigh && features.amountRatio >= config.highAmountRatio &&
            features.priceEfficiency <= config.exhaustionEfficiencyCeiling && features.priceChange1mPct <= 0.45

    /** 诱多至少需要脉冲衰减或VWAP失败之一，并由综合诱多风险达到配置阈值确认。 */
    private fun isBullTrap(features: VolumeFeatures, config: VolumeAnalysisConfig): Boolean =
        (features.volumeDecay || features.failedVwapReclaim) && features.bullTrapRisk >= config.bullTrapThreshold

    /**
     * 真实突破必须同时满足持续量能、上升VWAP、买压、板块同步和足够置信度。
     * 板块数据缺失不会被当作通过，防止“未知”被误解释为“同步”。
     */
    private fun isRealBreakout(features: VolumeFeatures, config: VolumeAnalysisConfig): Boolean =
        features.sustainedVolume && features.volumeScore >= config.realBreakoutVolumeScore &&
            features.priceVsVwapPct > config.vwapTolerancePct && features.vwapSlopePct > 0.0 &&
            features.buyPressureScore >= config.realBreakoutBuyPressure &&
            (features.sector.boardSyncScore ?: -1) >= config.realBreakoutBoardSync &&
            features.confidence >= 65

    /** 价格已经向上离开VWAP但持续量能、买压或板块确认不足时，归类为弱突破而不是追涨信号。 */
    private fun isWeakBreakout(features: VolumeFeatures, config: VolumeAnalysisConfig): Boolean =
        features.priceChange5mPct > 0.25 && features.priceVsVwapPct > config.vwapTolerancePct

    /** 根据分类生成面向UI的解释；详细数值证据仍由 VolumeFeatures.evidence 提供。 */
    private fun explanations(type: VolumeSignalType, features: VolumeFeatures): List<String> = buildList {
        when (type) {
            VolumeSignalType.REAL_BREAKOUT -> add("上涨同时获得持续成交、上升VWAP、买盘和板块共振确认")
            VolumeSignalType.WEAK_BREAKOUT -> add("价格已经突破，但量能、买盘或板块证据尚未形成一致确认")
            VolumeSignalType.BULL_TRAP -> add("拉升后的量能衰减或VWAP失守提高了脉冲诱多风险")
            VolumeSignalType.EXHAUSTION -> add("高位成交显著放大，但单位成交额推动价格的效率下降")
            VolumeSignalType.NORMAL -> add("当前量价结构没有达到异动阈值")
            VolumeSignalType.NO_SIGNAL -> add("实时性或基础样本不足，仅保留观察数据，不生成实时分类")
        }
        addAll(features.evidence)
    }
}
