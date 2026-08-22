package com.locogo.astockguard

import android.app.Application
import com.locogo.astockguard.data.local.AStockDatabase

class AppContainer(application: Application) {
    val settings: SettingsRepository by lazy { SettingsRepository(application) }
    val database: AStockDatabase by lazy { AStockDatabase.get(application) }
    val marketRepository: MarketRepository by lazy {
        MarketRepository(settings = settings, cacheDao = database.cacheDao())
    }
    val aiClient: AiClient by lazy { AiClient() }
}

class AStockGuardApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as AStockGuardApp).container
