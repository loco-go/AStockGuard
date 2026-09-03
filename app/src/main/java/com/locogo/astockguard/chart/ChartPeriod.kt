package com.locogo.astockguard.chart

/*
 * 文件职责：定义日、周、月等图表周期及其显示语义，避免使用散落字符串判断周期。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

/**
 * V3.5 multi timeframe chart period.
 */
enum class ChartPeriod {
    MINUTE,
    DAY,
    WEEK,
    MONTH
}
