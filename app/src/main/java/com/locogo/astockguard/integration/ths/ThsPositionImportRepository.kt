package com.locogo.astockguard.integration.ths

import com.locogo.astockguard.Position
import com.locogo.astockguard.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 待确认候选仅在本次进程内保留；扫描不能修改持仓或账户资产。 */
data class ThsPositionCandidate(
    val name: String,
    val code: String? = null,
    val position: ParsedThsPosition? = null
)

class ThsPositionImportRepository(
    private val settings: SettingsRepository,
    private val dao: com.locogo.astockguard.data.local.CacheDao
) {
    private val accountPending = MutableStateFlow<List<ThsAccountCandidate>>(emptyList())
    val accountCandidates = accountPending.asStateFlow()
    val importedAccount = dao.observeImportedAccountMetrics()

    fun collectAccount(values: List<ThsAccountCandidate>) {
        // 不同字段各自保留采集时间，滚动后无账户区不会清空已识别值。
        accountPending.value = (accountPending.value.associateBy { it.metric } + values.associateBy { it.metric }).values.toList()
    }

    suspend fun confirmAccount(values: List<ThsAccountCandidate>) {
        require(values.isNotEmpty()) { "请先选择账户字段" }
        require(values.all { it.metric.valid(it.value) }) { "账户数值无效" }
        val now = System.currentTimeMillis()
        dao.upsertImportedAccountMetrics(values.map {
            com.locogo.astockguard.data.local.ImportedAccountMetricEntity(
                it.metric.name, it.value, it.observedAt, now,
                it.source
            )
        })
        ThsSyncBus.notifyDataChanged()
    }

    suspend fun clearImportedAccount() { dao.clearImportedAccountMetrics() }
    private val pending = MutableStateFlow<List<ThsPositionCandidate>>(emptyList())
    val candidates = pending.asStateFlow()

    fun collect(names: List<String>, positions: List<ParsedThsPosition>, codes: Map<String, String>) {
        val incoming = positions.map { ThsPositionCandidate(it.name, it.code, it) } +
            names.filter { name -> positions.none { it.name == name } }
                .map { ThsPositionCandidate(it, codes[it]) }
        // 滚动扫描累积候选；只识别到名字的新帧不覆盖已有完整字段。
        val merged = pending.value.toMutableList()
        incoming.forEach { candidate ->
            val index = merged.indexOfFirst {
                (candidate.code != null && it.code == candidate.code) || it.name == candidate.name
            }
            if (index < 0) merged += candidate
            else if (candidate.position != null || merged[index].position == null) merged[index] = candidate
        }
        pending.value = merged.take(500)
    }

    fun existing(): List<Position> = settings.positions()

    fun confirm(positions: List<ParsedThsPosition>) {
        require(positions.isNotEmpty()) { "请先选择股票" }
        settings.savePositions(ThsPositionMerger.mergeSelected(settings.positions(), positions))
        settings.recordThsSync("已手动确认导入 ${positions.distinctBy { it.code }.size} 只股票")
        ThsSyncBus.notifyDataChanged()
    }

    fun clear() { pending.value = emptyList(); accountPending.value = emptyList() }
}
