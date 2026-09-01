package com.locogo.astockguard

import android.app.Application
import com.locogo.astockguard.backup.BackupManager
import com.locogo.astockguard.data.fundflow.FundFlowRepository
import com.locogo.astockguard.data.level2.Level2Repository
import com.locogo.astockguard.data.ifind.IFindHttpClient
import com.locogo.astockguard.data.local.AStockDatabase
import com.locogo.astockguard.data.news.NewsRepository
import com.locogo.astockguard.data.repository.StrategySignalRepository
import com.locogo.astockguard.domain.paper.PaperTradingRepository
import com.locogo.astockguard.domain.replay.ReplayEngine
import com.locogo.astockguard.domain.review.ReviewRepository
import com.locogo.astockguard.domain.review.AlertHistoryRepository
import com.locogo.astockguard.domain.signal.R2Scanner
import com.locogo.astockguard.domain.signal.SignalLifecycleManager

class AppContainer(application: Application) {
    val settings: SettingsRepository by lazy { SettingsRepository(application) }
    val database: AStockDatabase by lazy { AStockDatabase.get(application) }
    val ifindHttpClient: IFindHttpClient by lazy { IFindHttpClient(settings) }
    val marketRepository: MarketRepository by lazy {
        MarketRepository(settings = settings, ifind = ifindHttpClient, cacheDao = database.cacheDao())
    }
    val fundFlowRepository: FundFlowRepository by lazy { FundFlowRepository(cacheDao = database.cacheDao()) }
    val strategySignalRepository: StrategySignalRepository by lazy { StrategySignalRepository(database.cacheDao()) }
    val newsRepository: NewsRepository by lazy { NewsRepository(settings, database.cacheDao()) }
    val level2Repository: Level2Repository by lazy { Level2Repository(settings, database.cacheDao()) }
    val paperTradingRepository: PaperTradingRepository by lazy { PaperTradingRepository(database) }
    val replayEngine: ReplayEngine by lazy { ReplayEngine() }
    val r2Scanner: R2Scanner by lazy { R2Scanner(marketRepository) }
    val signalLifecycle: SignalLifecycleManager by lazy { SignalLifecycleManager(database.cacheDao()) }
    val reviewRepository: ReviewRepository by lazy { ReviewRepository(database.cacheDao(), marketRepository) }
    val alertHistoryRepository: AlertHistoryRepository by lazy { AlertHistoryRepository(database.cacheDao()) }
    val backupManager: BackupManager by lazy { BackupManager(settings, database) }
    val aiClient: AiClient by lazy { AiClient() }
}

class AStockGuardApp : Application() { val container: AppContainer by lazy { AppContainer(this) } }
val android.content.Context.appContainer: AppContainer get() = (applicationContext as AStockGuardApp).container
