package com.locogo.astockguard

/*
 * 文件职责：在应用进程内组装数据库、Repository 和领域依赖；集中创建共享单例，避免界面层重复实例化。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

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
import com.locogo.astockguard.domain.volume.IntradayVolumeCoordinator

class AppContainer(application: Application) {
    val thsPositionImportRepository by lazy {
        com.locogo.astockguard.integration.ths.ThsPositionImportRepository(settings, database.cacheDao())
    }
    val settings: SettingsRepository by lazy { SettingsRepository(application) }
    val database: AStockDatabase by lazy { AStockDatabase.get(application) }
    val ifindHttpClient: IFindHttpClient by lazy { IFindHttpClient(settings) }
    val marketRepository: MarketRepository by lazy {
        MarketRepository(settings = settings, ifind = ifindHttpClient, cacheDao = database.cacheDao())
    }
    val fundFlowRepository: FundFlowRepository by lazy { FundFlowRepository(cacheDao = database.cacheDao()) }
    val strategySignalRepository: StrategySignalRepository by lazy { StrategySignalRepository(database.cacheDao()) }
    val newsRepository: NewsRepository by lazy { NewsRepository(settings, database.cacheDao()) }
    val level2Repository: Level2Repository by lazy {
        Level2Repository(settings, database.cacheDao(), ifindHttpClient)
    }
    val paperTradingRepository: PaperTradingRepository by lazy { PaperTradingRepository(database) }
    val replayEngine: ReplayEngine by lazy { ReplayEngine() }
    val r2Scanner: R2Scanner by lazy { R2Scanner(marketRepository) }
    val signalLifecycle: SignalLifecycleManager by lazy { SignalLifecycleManager(database.cacheDao()) }
    val reviewRepository: ReviewRepository by lazy { ReviewRepository(database.cacheDao(), marketRepository) }
    val alertHistoryRepository: AlertHistoryRepository by lazy { AlertHistoryRepository(database.cacheDao()) }
    val monitorMessageRepository by lazy {
        com.locogo.astockguard.domain.review.MonitorMessageRepository(database.cacheDao())
    }
    /** 详情页和后台监控共享同一量价协调器，避免形成第二套行情与策略编排链路。 */
    val intradayVolumeCoordinator: IntradayVolumeCoordinator by lazy {
        IntradayVolumeCoordinator(marketRepository, fundFlowRepository, level2Repository)
    }
    val backupManager: BackupManager by lazy { BackupManager(settings, database) }
    val aiClient: AiClient by lazy { AiClient() }
}

class AStockGuardApp : Application() { val container: AppContainer by lazy { AppContainer(this) } }
val android.content.Context.appContainer: AppContainer get() = (applicationContext as AStockGuardApp).container
