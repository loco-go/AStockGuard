package com.locogo.astockguard

class MarketRepository(
    private val settings: SettingsRepository,
    private val ifind: IfindClient = IfindClient()
) {
    private var previousCloses: Map<String, Double> = emptyMap()
    private var previousCloseFetchedAt = 0L

    suspend fun refresh(): MonitorSnapshot {
        val codes = settings.allCodes()
        val token = ifind.ensureAccessToken(settings.ifindRefreshToken)
        if (previousCloses.isEmpty() || System.currentTimeMillis() - previousCloseFetchedAt > 30 * 60 * 1000L) {
            previousCloses = ifind.fetchPreviousCloses(token, codes)
            previousCloseFetchedAt = System.currentTimeMillis()
        }
        val quotes = ifind.fetchQuotes(token, codes, previousCloses)
        val assessment = RiskEngine.assess(quotes, settings.positions(), settings.positionRatio)
        return MonitorSnapshot(System.currentTimeMillis(), quotes, assessment, settings.positionRatio)
    }
}
