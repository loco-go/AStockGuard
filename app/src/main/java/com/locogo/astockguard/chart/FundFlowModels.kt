package com.locogo.astockguard.chart

/*
 * 文件职责：定义图表层资金流展示模型，与数据层供应商响应结构解耦。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

data class FundFlowPoint(val time: String, val netInflow: Double)
data class FundFlowRank(val name: String, val netInflow: Double, val changeRatio: Double)
data class MarketFundFlow(
    val totalAmount: Double,
    val netInflow: Double,
    val timeline: List<FundFlowPoint>,
    val industries: List<FundFlowRank>,
    val concepts: List<FundFlowRank>
)
