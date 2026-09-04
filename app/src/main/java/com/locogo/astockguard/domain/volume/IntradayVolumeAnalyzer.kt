package com.locogo.astockguard.domain.volume

import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.data.fundflow.FundFlowPoint
import com.locogo.astockguard.data.level2.Level2Snapshot

/*
 * 文件职责：作为Phase 2统一入口，按固定顺序编排板块、Level2、量价特征和分类器。
 * 架构边界：该门面是纯领域服务，不持有Android Context，不访问网络、Room或ViewModel。
 */

/** 原始板块上下文；Repository 后续负责加载和验证这些分钟序列。 */
data class SectorAnalysisInput(
    val sectorBars: List<MinuteBar> = emptyList(),
    val indexBars: List<MinuteBar> = emptyList(),
    val peerBars: List<List<MinuteBar>> = emptyList(),
    val fresh: Boolean = false
)

/** 单次盘中量价分析的完整结果，特征与分类同时返回以支持解释、持久化和Replay审计。 */
data class IntradayVolumeAnalysis(
    val features: VolumeFeatures,
    val signal: VolumeSignal
)

class IntradayVolumeAnalyzer(
    private val config: VolumeAnalysisConfig = VolumeAnalysisConfig()
) {
    /**
     * 分析当前时点的量价结构。
     *
     * 调用者必须只传入当前时点已经发生的数据；方法会先验证板块与盘口，再提取价格成交特征，最后分类。
     * dataFresh=false 时仍返回可展示特征，但分类固定为 NO_SIGNAL，满足缓存数据不得产生实时动作的规则。
     */
    fun analyze(
        code: String,
        bars: List<MinuteBar>,
        fundFlow: List<FundFlowPoint> = emptyList(),
        level2Snapshots: List<Level2Snapshot> = emptyList(),
        sectorInput: SectorAnalysisInput = SectorAnalysisInput(),
        dataFresh: Boolean,
        now: Long
    ): IntradayVolumeAnalysis {
        val sector = SectorSyncAnalyzer.analyze(
            stockBars = bars,
            sectorBars = sectorInput.sectorBars,
            indexBars = sectorInput.indexBars,
            peerBars = sectorInput.peerBars,
            fresh = sectorInput.fresh
        )
        val level2 = Level2SequenceAnalyzer.analyze(code, level2Snapshots, now, config)
        val features = VolumeFeatureExtractor.extract(
            code = code,
            bars = bars,
            fundFlow = fundFlow,
            level2 = level2,
            sector = sector,
            dataFresh = dataFresh,
            config = config
        )
        return IntradayVolumeAnalysis(features, VolumeSignalClassifier.classify(features, config))
    }
}
