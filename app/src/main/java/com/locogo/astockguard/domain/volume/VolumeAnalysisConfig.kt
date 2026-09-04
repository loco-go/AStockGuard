package com.locogo.astockguard.domain.volume

/*
 * 文件职责：集中保存盘中量价雷达的默认阈值，避免在提取器和分类器中散落不可审计的魔法数字。
 * 参数口径：百分比字段使用百分点，例如 0.15 表示 0.15%，倍率字段使用 1.5 表示均值的 1.5 倍。
 */

/**
 * Phase 2 使用的量价分析参数。后续可由 SettingsRepository 提供覆盖值，但领域层始终只依赖此不可变配置。
 */
data class VolumeAnalysisConfig(
    val rollingWindow: Int = 20,
    val shortWindow: Int = 3,
    val mediumWindow: Int = 5,
    val trendWindow: Int = 20,
    val sustainedVolume3mRatio: Double = 1.50,
    val sustainedVolume5mRatio: Double = 1.30,
    val pulseVolumeRatio: Double = 2.50,
    val decayEndRatio: Double = 1.00,
    val highAmountRatio: Double = 1.80,
    val vwapTolerancePct: Double = 0.15,
    val realBreakoutVolumeScore: Int = 68,
    val realBreakoutBuyPressure: Int = 60,
    val realBreakoutBoardSync: Int = 60,
    val bullTrapThreshold: Int = 68,
    val exhaustionEfficiencyCeiling: Int = 42,
    val minimumSamples: Int = 21,
    val minimumLevel2Samples: Int = 2,
    val maximumLevel2AgeMs: Long = 120_000L
)
