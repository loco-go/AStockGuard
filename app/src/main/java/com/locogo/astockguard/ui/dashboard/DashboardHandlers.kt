package com.locogo.astockguard.ui.dashboard

/*
 * 文件职责：定义驾驶舱用户操作契约，使 Fragment 只依赖抽象事件而不直接依赖 Repository。
 * 架构边界：生命周期内只收集可观察状态；耗时任务、持久化和网络请求交给 ViewModel/Repository。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

interface DashboardHandlers {
    fun onDecisionTab()
    fun onChartTab()
    fun onFlowTab()
    fun onReviewTab()
    fun onRefresh()
    fun onStartMonitor()
    fun onStopMonitor()
    fun onSettings()
    fun onAnalyze()
    fun onRefreshNews()
    fun onRefreshLevel2()
    fun onRecordBuy()
    fun onRecordSell()
    fun onRecordLedger()
    fun onIndustryFlow()
    fun onConceptFlow()
    fun onClearAnchor()
    fun onPaperBuy()
    fun onPaperSell()
    fun onResetPaper()
    fun onReplayReset()
    fun onReplayStep()
    fun onReplayPlay()
    fun onReplayPause()
    fun onRunBacktest()
}
