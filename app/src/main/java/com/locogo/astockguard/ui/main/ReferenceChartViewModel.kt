package com.locogo.astockguard.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.locogo.astockguard.MarketRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 一个独立图表序列的样本和来源，不沿用页面其他模块的实时标记。 */
data class ReferenceTrend(val values: List<Double>, val labels: List<String>, val source: String)

/** 参考界面的图表输入；未接入的历史/情绪保持 null 或空集合，不推测填值。 */
data class ReferenceVisualData(
    val indices: Map<String, ReferenceTrend> = emptyMap(),
    val sentiment: Double? = null,
    val upHistory: List<Double> = emptyList(),
    val downHistory: List<Double> = emptyList(),
    val streakHistory: List<Double> = emptyList(),
    val historyDates: List<String> = emptyList(),
    val accountReturns: List<Double> = emptyList(),
    val benchmarkReturns: List<Double> = emptyList(),
    val returnDates: List<String> = emptyList()
)

/** 首页和账户共用指数走势，网络及缓存决策继续由 MarketRepository 负责。 */
class ReferenceChartViewModel(private val repository: MarketRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(ReferenceVisualData())
    val state = mutableState.asStateFlow()
    private var loading = false
    private var requestedAt = 0L

    /** 同一 Activity 一分钟内共享结果，切换主页面不重复请求三组指数。 */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (loading || (!force && now - requestedAt < 60_000L)) return
        loading = true
        requestedAt = now
        viewModelScope.launch {
            try {
                val rows = listOf("000001.SH", "399001.SZ", "399006.SZ").map { code ->
                    async {
                        try {
                            val series = repository.loadMinuteSeries(code)
                            code to ReferenceTrend(
                                series.bars.map { it.price }, series.bars.map { it.time.takeLast(5) },
                                "${series.intervalMinutes}分钟${if (series.fromCache) "缓存" else "采样"} · ${series.source}"
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            code to ReferenceTrend(emptyList(), emptyList(), "走势暂不可用")
                        }
                    }
                }.awaitAll().toMap()
                mutableState.value = ReferenceVisualData(indices = rows)
            } finally {
                loading = false
            }
        }
    }

    /** 由应用容器提供 Repository，图表 View 不直接访问网络。 */
    class Factory(private val repository: MarketRepository) : ViewModelProvider.Factory {
        /** 创建共享指数图表状态对象。 */
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReferenceChartViewModel(repository) as T
    }
}
