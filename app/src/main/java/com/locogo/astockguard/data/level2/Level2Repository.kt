package com.locogo.astockguard.data.level2

/*
 * 文件职责：在 Provider 与 Room 之间选择盘口来源并标记真实、模拟和过期状态；只有通过质量验证的数据才能进入实时策略。
 * 架构边界：解析失败、超时和字段缺失要显式返回失败或不可用状态，不能用零值伪造有效行情。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import com.locogo.astockguard.SettingsRepository
import com.locogo.astockguard.data.ifind.IFindHttpClient
import com.locogo.astockguard.data.local.CacheDao
import com.locogo.astockguard.data.local.Level2SnapshotHistoryEntity
import com.locogo.astockguard.data.local.Level2SnapshotEntity
import org.json.JSONArray
import org.json.JSONObject

class Level2Repository(
    private val settings: SettingsRepository,
    private val cacheDao: CacheDao,
    private val ifindClient: IFindHttpClient? = null
) {
    private var lastHistoryPruneAt = 0L

    suspend fun snapshot(code: String, referencePrice: Double? = null): Level2Snapshot? {
        val provider = provider()
        val fresh = runCatching { provider.snapshot(code, referencePrice) }.getOrNull()
        if (fresh != null) {
            val capturedAt = System.currentTimeMillis()
            val normalized = fresh.copy(receivedAt = capturedAt)
            cacheDao.upsertLevel2(normalized.toEntity(code, capturedAt))
            // 模拟和缓存盘口不能进入真实连续性样本，避免开发数据污染实盘策略。
            val providerTimeFresh = normalized.updatedAt > 0L &&
                capturedAt - normalized.updatedAt in -MAX_PROVIDER_FUTURE_SKEW_MS..STRATEGY_HISTORY_WINDOW_MS
            val fullIFindDepth = normalized.source != "IFIND_HTTP_DEPTH_LIMITED"
            if (!normalized.simulated && !normalized.stale && providerTimeFresh && fullIFindDepth) {
                cacheDao.insertLevel2History(
                    Level2SnapshotHistoryEntity(
                        code = code,
                        capturedAt = capturedAt,
                        payloadJson = normalized.payloadJson(),
                        source = normalized.source,
                        providerUpdatedAt = normalized.updatedAt
                    )
                )
                pruneHistoryIfDue(capturedAt)
            }
            return normalized
        }
        return cacheDao.getLevel2(code)?.toModel()?.copy(stale = true, message = "Level2 实时源失败，显示本地缓存")
    }

    /** 返回最近真实盘口序列；上层仍需按行情时间和证券代码做二次质量校验。 */
    suspend fun recentSnapshots(code: String, now: Long = System.currentTimeMillis()): List<Level2Snapshot> =
        cacheDao.getRecentLevel2History(code, now - STRATEGY_HISTORY_WINDOW_MS, MAX_HISTORY_SAMPLES)
            .mapNotNull { it.toModel() }
            .sortedBy { it.receivedAt }

    /** 连接测试绕过缓存回退，确保设置页展示的是本次真实接口结果。 */
    suspend fun testConnection(code: String): Level2Snapshot = provider().snapshot(code)

    private fun provider(): Level2Provider = when (settings.level2ProviderType.uppercase()) {
        "IFIND_HTTP" -> IFindLevel2Provider(requireNotNull(ifindClient) { "iFinD客户端尚未初始化" })
        "HTTP_JSON" -> HttpJsonLevel2Provider(settings.level2BaseUrl, settings.level2ApiToken)
        else -> MockLevel2Provider()
    }

    private fun Level2Snapshot.toEntity(cacheCode: String, cachedAt: Long): Level2SnapshotEntity =
        Level2SnapshotEntity(cacheCode, payloadJson(), source, simulated, cachedAt)

    private fun Level2Snapshot.payloadJson(): String {
        val root = JSONObject().apply {
            put("code", code)
            put("updatedAt", updatedAt)
            put("message", message)
            put("bids", JSONArray().apply { bids.forEach { put(JSONObject().put("price", it.price).put("volume", it.volume)) } })
            put("asks", JSONArray().apply { asks.forEach { put(JSONObject().put("price", it.price).put("volume", it.volume)) } })
            put("trades", JSONArray().apply { trades.forEach { put(JSONObject().put("time", it.time).put("price", it.price).put("volume", it.volume).put("side", it.side)) } })
        }
        return root.toString()
    }

    private fun Level2SnapshotEntity.toModel(): Level2Snapshot? = runCatching {
        val root = JSONObject(payloadJson)
        fun levels(key: String): List<Level2Level> {
            val arr = root.optJSONArray(key) ?: return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(Level2Level(o.optDouble("price"), o.optLong("volume")))
                }
            }
        }
        val trades = buildList {
            val arr = root.optJSONArray("trades")
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(Level2Trade(o.optString("time"), o.optDouble("price"), o.optLong("volume"), o.optString("side")))
            }
        }
        Level2Snapshot(
            code = root.optString("code", code),
            bids = levels("bids"),
            asks = levels("asks"),
            trades = trades,
            source = source,
            simulated = simulated,
            stale = true,
            updatedAt = root.optLong("updatedAt", cachedAt),
            receivedAt = cachedAt,
            message = root.optString("message")
        )
    }.getOrNull()

    private fun Level2SnapshotHistoryEntity.toModel(): Level2Snapshot? =
        Level2SnapshotEntity(code, payloadJson, source, false, capturedAt).toModel()?.copy(
            stale = false,
            updatedAt = providerUpdatedAt,
            receivedAt = capturedAt
        )

    /** 每小时清理一次历史，库内只保留两天样本，兼顾盘中分析和数据库体积。 */
    private suspend fun pruneHistoryIfDue(now: Long) {
        if (now - lastHistoryPruneAt < HISTORY_PRUNE_INTERVAL_MS) return
        cacheDao.deleteOldLevel2History(now - HISTORY_RETENTION_MS)
        lastHistoryPruneAt = now
    }

    private companion object {
        const val STRATEGY_HISTORY_WINDOW_MS = 120_000L
        const val MAX_HISTORY_SAMPLES = 6
        const val HISTORY_RETENTION_MS = 2 * 24 * 60 * 60 * 1000L
        const val HISTORY_PRUNE_INTERVAL_MS = 60 * 60 * 1000L
        const val MAX_PROVIDER_FUTURE_SKEW_MS = 5_000L
    }
}
