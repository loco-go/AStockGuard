package com.locogo.astockguard.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.locogo.astockguard.AiClient
import com.locogo.astockguard.MarketRepository
import com.locogo.astockguard.PromptBuilder
import com.locogo.astockguard.SettingsRepository
import com.locogo.astockguard.data.ai.AiStrategyParser
import com.locogo.astockguard.data.fundflow.FundFlowRepository
import com.locogo.astockguard.data.local.AiAnalysisEntity
import com.locogo.astockguard.data.local.CacheDao
import com.locogo.astockguard.domain.review.ReviewRepository
import com.locogo.astockguard.domain.signal.R2Scanner
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface MainEffect { data class RunHiddenWebAi(val prompt: String) : MainEffect }

class MainViewModel(
    private val settings: SettingsRepository,
    private val marketRepository: MarketRepository,
    private val fundFlowRepository: FundFlowRepository,
    private val r2Scanner: R2Scanner,
    private val reviewRepository: ReviewRepository,
    private val aiClient: AiClient,
    private val cacheDao: CacheDao
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val _effects = MutableSharedFlow<MainEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<MainEffect> = _effects.asSharedFlow()

    fun acceptSnapshot(snapshot: com.locogo.astockguard.MonitorSnapshot) {
        val selected = _uiState.value.selectedCode ?: snapshot.quotes.firstOrNull()?.code
        _uiState.update { it.copy(snapshot = snapshot, loading = false, selectedCode = selected, error = null) }
        selected?.let(::selectStock)
        viewModelScope.launch {
            val curve = runCatching { marketRepository.buildPortfolioCurve(settings.positions()) }.getOrDefault(emptyList())
            _uiState.update { it.copy(equityCurve = curve) }
        }
        if (_uiState.value.sectorFundFlow == null) refreshSectorFlow("INDUSTRY")
        viewModelScope.launch {
            val scan = runCatching { r2Scanner.scan(settings.allCodes(), snapshot.assessment.marketPhase) }.getOrDefault(emptyList())
            val states = runCatching { cacheDao.getSignalStates() }.getOrDefault(emptyList())
            _uiState.update { it.copy(r2ScanRows = scan, signalStates = states) }
        }
        refreshReviews()
    }

    fun selectStock(code: String) {
        _uiState.update { it.copy(selectedCode = code, fundFlowLoading = true) }
        viewModelScope.launch {
            val daily = runCatching { marketRepository.loadDailyBars(code, 30) }.getOrDefault(emptyList())
            val minute = runCatching { marketRepository.loadMinuteBars(code) }.getOrDefault(emptyList())
            val flow = runCatching { fundFlowRepository.stock(code) }.getOrNull()
            if (_uiState.value.selectedCode == code) _uiState.update { it.copy(dailyBars = daily, minuteBars = minute, stockFundFlow = flow, fundFlowLoading = false) }
        }
    }

    fun refreshSectorFlow(type: String) {
        viewModelScope.launch {
            val result = runCatching { fundFlowRepository.sectors(type) }.getOrNull()
            if (result != null) _uiState.update { it.copy(sectorFundFlow = result) }
        }
    }

    fun refreshReviews() {
        viewModelScope.launch {
            val signals = runCatching { reviewRepository.signalStats() }.getOrDefault(com.locogo.astockguard.domain.review.SignalReviewStats())
            val trades = runCatching { reviewRepository.tradeStats() }.getOrDefault(com.locogo.astockguard.domain.review.TradeReviewStats())
            _uiState.update { it.copy(signalReviewStats = signals, tradeReviewStats = trades) }
        }
    }

    fun recordTrade(side: String, quantity: Int, price: Double) {
        val code = _uiState.value.selectedCode ?: return
        viewModelScope.launch {
            runCatching { reviewRepository.recordTrade(code, side, quantity, price) }
                .onSuccess { refreshReviews() }
                .onFailure { t -> _uiState.update { it.copy(error = t.message ?: "交易记录失败") } }
        }
    }

    fun refresh() {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching { marketRepository.refresh() }.onSuccess(::acceptSnapshot)
                .onFailure { t -> _uiState.update { it.copy(loading = false, error = t.message ?: "行情刷新失败") } }
        }
    }

    fun analyze(question: String) {
        val snapshot = _uiState.value.snapshot ?: run { _uiState.update { it.copy(error = "请先刷新行情") }; return }
        val prompt = PromptBuilder.build(snapshot, settings.positions(), question, _uiState.value.stockFundFlow, _uiState.value.sectorFundFlow)
        _uiState.update { it.copy(aiLoading = true, aiText = "AI分析中…", error = null) }
        if (settings.primaryType == "CHATGPT_WEB") { _effects.tryEmit(MainEffect.RunHiddenWebAi(prompt)); return }
        viewModelScope.launch {
            runCatching { aiClient.analyze(settings.primaryProvider(), settings.backupProvider(), prompt) }
                .onSuccess { onAiAnswer(prompt, it) }
                .onFailure { t -> _uiState.update { it.copy(aiLoading = false, aiText = "AI调用失败：${t.message}") } }
        }
    }

    fun onAiAnswer(prompt: String, answer: String) {
        val strategy = AiStrategyParser.parse(answer)
        _uiState.update { it.copy(aiLoading = false, aiText = AiStrategyParser.displayText(answer), aiStrategy = strategy, error = null) }
        viewModelScope.launch { runCatching { cacheDao.insertAiAnalysis(AiAnalysisEntity(
            createdAt = System.currentTimeMillis(), prompt = prompt, rawAnswer = answer,
            marketAction = strategy?.marketAction ?: "UNPARSED", confidence = strategy?.confidence ?: 0,
            targetPositionPct = strategy?.targetPositionPct ?: 0, strategyJson = strategy?.json.orEmpty()
        )) } }
    }

    fun onAiStatus(message: String) { _uiState.update { it.copy(aiLoading = true, aiText = message, error = null) } }
    fun onAiFailed(message: String) { _uiState.update { it.copy(aiLoading = false, aiText = "AI分析需要人工处理：$message") } }
    fun consumeError() { _uiState.update { it.copy(error = null) } }

    class Factory(
        private val settings: SettingsRepository,
        private val marketRepository: MarketRepository,
        private val fundFlowRepository: FundFlowRepository,
        private val r2Scanner: R2Scanner,
        private val reviewRepository: ReviewRepository,
        private val aiClient: AiClient,
        private val cacheDao: CacheDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(settings, marketRepository, fundFlowRepository, r2Scanner, reviewRepository, aiClient, cacheDao) as T
    }
}
