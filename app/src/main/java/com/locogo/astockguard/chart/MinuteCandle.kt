package com.locogo.astockguard.chart

/*
 * 文件职责：定义分钟蜡烛及成交字段，是回放与提醒评价共享的最小时间序列单元。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

data class MinuteCandle(
    val time: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val average: Double,
    val volume: Double
)
