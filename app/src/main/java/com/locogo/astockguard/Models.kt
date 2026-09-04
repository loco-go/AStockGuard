package com.locogo.astockguard

/*
 * 文件职责：定义持仓、行情、分钟线、日线、市场评估与 AI 配置等跨层共享模型；字段是 Repository、领域引擎与 UI 之间的稳定契约。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

/**
 * 用户真实账户中的一条持仓配置。
 *
 * @property code 规范化证券代码，格式为六位数字加交易所后缀，例如 `000001.SZ`。
 * @property name 证券展示名称，仅用于 UI；策略关联始终以 [code] 为准。
 * @property shares 当前总持仓股数，单位为股；尚未包含券商“可用数量”语义。
 * @property cost 持仓摊薄成本，单位为元/股；必须为正数才可计算累计收益。
 * @property role 持仓角色字符串，由 PositionCategory 做兼容解析，决定底仓和做 T 上限。
 */
data class Position(
    val code: String,
    val name: String,
    val shares: Int,
    val cost: Double,
    val role: String,
    /** 券商明确返回的当日可卖数量；null 表示未知，不能把总持仓直接当成可卖数量。 */
    val availableShares: Int? = null,
    /** 用户明确保护的核心仓数量；0表示交由交易风格的默认核心仓比例保护。 */
    val coreShares: Int = 0,
    /** 交易风格使用稳定英文枚举名保存；未知值由领域层安全降级为TREND。 */
    val tradingStyle: String = "TREND"
)

/**
 * 单只证券的一帧规范化行情。
 *
 * 所有价格字段单位均为元/股；[changeRatio] 使用数据源返回的百分数口径，例如 `1.25`
 * 表示上涨 1.25%，不是小数 `0.0125`。[amount] 统一为元，[volume] 沿用供应商成交量
 * 口径并在具体解析器中说明。可空字段代表供应商缺失、盘前占位或校验失败，调用方不得把
 * `null` 自动替换为 0 后参与收益或实时信号计算。
 *
 * [time] 是数据源展示时间，不替代快照的系统接收时间；是否新鲜由 Repository 和
 * DataQualityEvaluator 根据来源、请求结果及时间戳共同判断。
 */
data class Quote(
    val code: String,
    val name: String = "",
    val time: String = "",
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val latest: Double? = null,
    val previousClose: Double? = null,
    val changeRatio: Double? = null,
    val volume: Double? = null,
    val amount: Double? = null,
    val vwap: Double? = null,
    val ma5: Double? = null,
    val ma10: Double? = null,
    val r2Score: Int = 0,
    val r2Grade: String = "-",
    val r2Reason: String = ""
)

/**
 * 规范化分钟行情。价格及 OHLC 单位为元/股，成交量和成交额口径由数据源适配器统一。
 * 列表必须按交易时间升序排列；历史分钟线只用于查看、回放和评价，不能冒充当日实时数据。
 */
data class MinuteBar(
    val time: String,
    val price: Double,
    val avgPrice: Double,
    val high: Double,
    val low: Double,
    val volume: Double,
    val amount: Double
)

/** 日 K 线；[date] 使用可排序交易日期，OHLC 单位为元/股，列表按日期升序。 */
data class DailyBar(
    val date: String,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Double
)

/** R2 确定性评分结果；[reason] 保存可直接展示的证据说明，而不仅保存最终等级。 */
data class R2Result(
    val score: Int,
    val grade: String,
    val reason: String
)

/** 单只股票的本地规则信号；动作只是决策辅助状态，不表示已提交或已成交委托。 */
data class StockSignal(
    val code: String,
    val level: String,
    val action: String,
    val reason: String
)

/**
 * 风险引擎对当前持仓集合及市场宽度的汇总判断。
 * [maxPositionRatio] 使用 0..1 比例；[avgChange]、[redRatio] 和 [severeDropRatio]
 * 的具体百分数口径由 RiskEngine 固定，调用 UI 时应统一格式化，避免重复乘以 100。
 */
data class MarketAssessment(
    val eventRisk: String,
    val marketPhase: String,
    val maxPositionRatio: Double,
    val avgChange: Double,
    val redRatio: Double,
    val severeDropRatio: Double,
    val advice: String,
    val signals: List<StockSignal>
)

/** 行情快照的数据来源与缓存状态；[isStale] 为 true 时不得生成 actionable 实时动作。 */
data class DataHealth(
    val source: String = "TENCENT",
    val isStale: Boolean = false,
    val message: String = ""
)

/**
 * 一轮市场监控的不可变输出。[updatedAt] 为 epoch 毫秒，用于判断整帧新鲜度；
 * [quotes] 是持仓行情，[marketIndices] 是指数行情，两者不能按列表位置相互假设对应关系。
 */
data class MonitorSnapshot(
    val updatedAt: Long,
    val quotes: List<Quote>,
    val marketIndices: List<Quote> = emptyList(),
    val assessment: MarketAssessment,
    val positionRatio: Double,
    val dataHealth: DataHealth = DataHealth()
)

/**
 * AI Provider 配置。API Key、Session Token 与 Cookie 都属于敏感数据，只能通过 CryptoStore
 * 加密保存，不得写入日志、通知、备份或测试样本；[extraHeadersJson] 同样可能包含秘密。
 */
data class AiProviderConfig(
    val type: String = "RESPONSES",
    val name: String = "primary",
    val baseUrl: String = "",
    val apiKey: String = "",
    val sessionToken: String = "",
    val cookie: String = "",
    val model: String = "",
    val extraHeadersJson: String = "{}"
)
