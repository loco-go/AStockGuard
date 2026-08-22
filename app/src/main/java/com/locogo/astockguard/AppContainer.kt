package com.locogo.astockguard

import android.app.Application
import com.locogo.astockguard.data.fundflow.FundFlowRepository
import com.locogo.astockguard.data.local.AStockDatabase
import com.locogo.astockguard.domain.signal.R2Scanner
import com.locogo.astockguard.domain.signal.SignalLifecycleManager

class AppContainer(application: Application) {
    val settings: SettingsRepository by lazy { SettingsRepository(application) }
    val database: AStockDatabase by lazy { AStockDatabase.get(application) }
    val marketRepository: MarketRepository by lazy { MarketRepository(settings = settings, cacheDao = database.cacheDao()) }
    val fundFlowRepository: FundFlowRepository by lazy { FundFlowRepository(cacheDao = database.cacheDao()) }
    val r2Scanner: R2Scanner by lazy { R2Scanner(marketRepository) }
    val signalLifecycle: SignalLifecycleManager by lazy { SignalLifecycleManager(database.cacheDao()) }
    val aiClient: AiClient by lazy { AiClient() }
}

class AStockGuardApp : Application() { val container: AppContainer by lazy { AppContainer(this) } }
val android.content.Context.appContainer: AppContainer get() = (applicationContext as AStockGuardApp).container
