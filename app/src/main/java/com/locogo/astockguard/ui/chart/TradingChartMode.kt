package com.locogo.astockguard.ui.chart

/*
 * 文件职责：枚举分时和 K 线等交易图模式，避免界面以魔法字符串切换。
 * 架构边界：生命周期内只收集可观察状态；耗时任务、持久化和网络请求交给 ViewModel/Repository。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

enum class TradingChartMode { MINUTE, DAILY }
