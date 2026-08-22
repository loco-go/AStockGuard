package com.locogo.astockguard.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.locogo.astockguard.AiClient
import com.locogo.astockguard.MarketRepository
import com.locogo.astockguard.PromptBuilder
import com.locogo.astockguard.SettingsRepository
import com.locogo.astockguard.data.ai.AiStrategyParser
import com.locogo.astockguard.data.local.AiAnalysisEntity
import com.locogo.astockguard.data.local.CacheDao
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface MainEffect {
    data class RunHiddenWebAi(val prompt: String) : MainEffect
}

class MainViewModel(
    private val settings: SettingsRepository,
    private val marketRepository: MarketRepository,
    private val aiClient: AiClient,
    private val cacheDao: CacheDao
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<MainEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<MainEffect> = _effects.asSharedFlow()

    fun acceptSnapshot(snapshot: com.locogo.astockguard.MonitorSnapshot) {
        _uiState.update { it.copy(snapshot = snapshot, loading = false, error = null) }
    }

    fun refresh() {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching { marketRepository.refresh() }
                .onSuccess(::acceptSnapshot)
                .onFailure { t -> _uiState.update { it.copy(loading = false, error = t.message ?: "行情刷新失败") } }
        }
    }

    fun analyze(question: String) {
        val snapshot = _uiState.value.snapshot ?: run {
            _uiState.update { it.copy(error = "请先刷新行情") }
            return
        }
        val prompt = PromptBuilder.build(snapshot, settings.positions(), question)
        _uiState.update { it.copy(aiLoading = true, aiText = "AI分析中…", error = null) }
        if (settings.primaryType == "CHATGPT_WEB") {
            _effects.tryEmit(MainEffect.RunHiddenWebAi(prompt))
            return
        }
        viewModelScope.launch {
            runCatching { aiClient.analyze(settings.primaryProvider(), settings.backupProvider(), prompt) }
                .onSuccess { onAiAnswer(prompt, it) }
                .onFailure { t ->
                    _uiState.update { it.copy(aiLoading = false, aiText = "AI调用失败：${t.message}") }
                }
        }
    }

    fun onAiAnswer(prompt: String, answer: String) {
        val strategy = AiStrategyParser.parse(answer)
        _uiState.update { it.copy(aiLoading = false, aiText = AiStrategyParser.displayText(answer), error = null) }
        viewModelScope.launch {
            runCatching {
                cacheDao.insertAiAnalysis(
                    AiAnalysisEntity(
                        createdAt = System.currentTimeMillis(),
                        prompt = prompt,
                        rawAnswer = answer,
                        marketAction = strategy?.marketAction ?: "UNPARSED",
                        confidence = strategy?.confidence ?: 0,
                        targetPositionPct = strategy?.targetPositionPct ?: 0,
                        strategyJson = strategy?.json.orEmpty()
                    )
                )
            }
        }
    }

    fun onAiStatus(message: String) {
        _uiState.update { it.copy(aiLoading = true, aiText = message, error = null) }
    }

    fun onAiFailed(message: String) {
        _uiState.update { it.copy(aiLoading = false, aiText = "AI分析需要人工处理：$message") }
    }

    fun consumeError() {
        _uiState.update { it.copy(error = null) }
    }

    class Factory(
        private val settings: SettingsRepository,
        private val marketRepository: MarketRepository,
        private val aiClient: AiClient,
        private val cacheDao: CacheDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(settings, marketRepository, aiClient, cacheDao) as T
    }
}
