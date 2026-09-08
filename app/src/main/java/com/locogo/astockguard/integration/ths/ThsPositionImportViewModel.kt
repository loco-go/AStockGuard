package com.locogo.astockguard.integration.ths

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locogo.astockguard.Position
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.locogo.astockguard.data.local.ImportedAccountMetricEntity

data class ThsImportRow(
    val candidate: ThsPositionCandidate,
    val existing: Boolean,
    val selected: Boolean,
    val edited: Boolean = false
)

data class ThsAccountImportRow(
    val metric: ThsAccountMetric,
    val candidate: ThsAccountCandidate? = null,
    val selected: Boolean = false,
    val existing: Boolean = false,
    val edited: Boolean = false
)

/** 旧扫描不能盖过较新的导入/修正值；清空候选后仍返回四个可补全字段。 */
internal fun reconcileThsAccountRows(
    candidates: List<ThsAccountCandidate>, previous: List<ThsAccountImportRow>,
    imported: List<ImportedAccountMetricEntity>
): List<ThsAccountImportRow> = ThsAccountMetric.entries.map { metric ->
    val old = previous.find { it.metric == metric }
    val saved = imported.find { it.metric == metric.name }
    val savedCandidate = saved?.let { ThsAccountCandidate(metric, it.value, it.observedAt, it.source) }
    val candidate = listOfNotNull(savedCandidate, candidates.find { it.metric == metric }).maxByOrNull { it.observedAt }
    if (old?.edited == true) old.copy(existing = saved != null)
    else ThsAccountImportRow(metric, candidate, old?.selected ?: (saved != null), saved != null)
}

/** 数字输入框不支持科学计数法；否则 Android 的输入过滤可能吞掉 E 并改变金额。 */
internal fun accountAmountInput(value: Double?): String = value?.takeIf { it.isFinite() }
    ?.let { java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }.orEmpty()

/** 已有股票首次默认选中；后续识别保留用户取消勾选和人工修正。 */
internal fun reconcileThsImportRows(
    candidates: List<ThsPositionCandidate>, previous: List<ThsImportRow>, existing: List<Position>
): List<ThsImportRow> = candidates.map { candidate ->
    val old = previous.find { it.candidate.name == candidate.name }
    val effective = if (old?.edited == true) old.candidate else candidate
    val present = existing.any { it.code == effective.code ||
        (effective.code == null && it.name == effective.name) }
    if (old?.edited == true) old.copy(existing = present)
    else ThsImportRow(candidate, present, old?.selected ?: present)
}

/** 勾选和人工修正由 ViewModel 持有，旋转或重新扫描不覆盖用户选择。 */
class ThsPositionImportViewModel(private val repository: ThsPositionImportRepository) : ViewModel() {
    private val mutableRows = MutableStateFlow<List<ThsImportRow>>(emptyList())
    val rows = mutableRows.asStateFlow()
    private val mutableMessage = MutableStateFlow("")
    val message = mutableMessage.asStateFlow()
    private val mutableAccountRows = MutableStateFlow<List<ThsAccountImportRow>>(emptyList())
    val accountRows = mutableAccountRows.asStateFlow()
    private val mutableAccountMessage = MutableStateFlow("")
    val accountMessage = mutableAccountMessage.asStateFlow()
    private var latestImportedAccount = emptyList<ImportedAccountMetricEntity>()

    init {
        viewModelScope.launch {
            combine(repository.accountCandidates, repository.importedAccount) { candidates, imported ->
                latestImportedAccount = imported
                reconcileThsAccountRows(candidates, accountRows.value, imported)
            }.collect { mutableAccountRows.value = it }
        }
        viewModelScope.launch {
            repository.candidates.collect { candidates ->
                mutableRows.value = reconcileThsImportRows(candidates, rows.value, repository.existing())
            }
        }
    }

    fun selectAccount(metric: ThsAccountMetric, selected: Boolean) {
        mutableAccountRows.value = accountRows.value.map { if (it.metric == metric) it.copy(selected = selected) else it }
    }

    fun editAccount(metric: ThsAccountMetric, value: Double) {
        require(metric.valid(value))
        mutableAccountRows.value = accountRows.value.map {
            if (it.metric == metric) it.copy(
                candidate = it.candidate?.takeIf { candidate -> candidate.value == value }
                    ?: ThsAccountCandidate(metric, value, System.currentTimeMillis(), "THS_MANUAL"),
                edited = true
            ) else it
        }
    }

    fun confirmAccount() {
        val selected = accountRows.value.filter { it.selected }
        if (selected.isEmpty() || selected.any { it.candidate == null }) {
            mutableAccountMessage.value = "请勾选账户字段，并补全未识别的数值"
            return
        }
        viewModelScope.launch {
            runCatching { repository.confirmAccount(selected.mapNotNull { it.candidate }) }
                .onSuccess { mutableAccountMessage.value = "已导入 ${selected.size} 项账户数据，首页显示来源和采集时间" }
                .onFailure { mutableAccountMessage.value = "账户导入失败：${it.message}" }
        }
    }

    fun clearImportedAccount() = viewModelScope.launch {
        runCatching { repository.clearImportedAccount() }
            .onSuccess { mutableAccountMessage.value = "已移除账户导入值，首页恢复本地计算" }
            .onFailure { mutableAccountMessage.value = "移除失败：${it.message}" }
    }

    fun select(name: String, selected: Boolean) {
        mutableRows.value = mutableRows.value.map {
            if (it.candidate.name == name) it.copy(selected = selected) else it
        }
        mutableMessage.value = ""
    }

    fun edit(name: String, position: ParsedThsPosition) {
        mutableRows.value = mutableRows.value.map {
            if (it.candidate.name == name) it.copy(
                candidate = ThsPositionCandidate(name, position.code, position), edited = true
            ) else it
        }
    }

    fun confirm() {
        val selected = rows.value.filter { it.selected }
        if (selected.isEmpty()) { mutableMessage.value = "请先勾选要导入的股票"; return }
        // 已有且仅识别到名称的行保持原值；新股票必须补齐真实字段。
        val existing = repository.existing()
        val positions = selected.map { row ->
            row.candidate.position ?: existing.find {
                it.code == row.candidate.code || (row.candidate.code == null && it.name == row.candidate.name)
            }?.let { ParsedThsPosition(it.code, it.name, it.shares, it.cost, availableShares = it.availableShares) }
        }
        if (positions.any { it == null }) {
            mutableMessage.value = "请点“核对/补全”填写所选新股票的代码、持仓数量和成本"
            return
        }
        runCatching { repository.confirm(positions.filterNotNull()) }
            .onSuccess {
                mutableRows.value = rows.value.map { if (it.selected) it.copy(existing = true) else it }
                mutableMessage.value = "已确认导入 ${positions.filterNotNull().distinctBy { it.code }.size} 只；其他本地持仓保持不变"
            }.onFailure { mutableMessage.value = "导入失败：${it.message}" }
    }

    fun clear() {
        repository.clear()
        // StateFlow 相同空列表不会再次发射，不能先置空后依赖仓库通知重建表单。
        mutableAccountRows.value = reconcileThsAccountRows(emptyList(), emptyList(), latestImportedAccount)
        mutableRows.value = emptyList()
        mutableMessage.value = "已清空候选。请重新打开同花顺持仓页识别"
    }
}
