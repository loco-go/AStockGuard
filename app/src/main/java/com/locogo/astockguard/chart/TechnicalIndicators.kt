package com.locogo.astockguard.chart

/*
 * 文件职责：提供图表侧技术指标计算入口；样本不足时返回不可用而不是补造数值。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

object TechnicalIndicators {

    fun ma(values: List<Double>, period: Int): List<Double?> {
        if (period <= 0) return emptyList()
        return values.mapIndexed { index, _ ->
            if (index + 1 < period) {
                null
            } else {
                values.subList(index + 1 - period, index + 1).average()
            }
        }
    }

    fun volumeRatio(current: Double, average: Double): Double {
        if (average <= 0) return 0.0
        return current / average
    }
}
