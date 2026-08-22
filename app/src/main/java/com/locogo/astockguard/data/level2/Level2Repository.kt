package com.locogo.astockguard.data.level2

import com.locogo.astockguard.SettingsRepository
import com.locogo.astockguard.data.local.CacheDao
import com.locogo.astockguard.data.local.Level2SnapshotEntity
import org.json.JSONArray
import org.json.JSONObject

class Level2Repository(
    private val settings: SettingsRepository,
    private val cacheDao: CacheDao
) {
    suspend fun snapshot(code: String, referencePrice: Double? = null): Level2Snapshot? {
        val provider = provider()
        val fresh = runCatching { provider.snapshot(code, referencePrice) }.getOrNull()
        if (fresh != null) {
            cacheDao.upsertLevel2(fresh.toEntity())
            return fresh
        }
        return cacheDao.getLevel2(code)?.toModel()?.copy(stale = true, message = "Level2 实时源失败，显示本地缓存")
    }

    private fun provider(): Level2Provider = when (settings.level2ProviderType.uppercase()) {
        "HTTP_JSON" -> HttpJsonLevel2Provider(settings.level2BaseUrl, settings.level2ApiToken)
        else -> MockLevel2Provider()
    }

    private fun Level2Snapshot.toEntity(): Level2SnapshotEntity {
        val root = JSONObject().apply {
            put("code", code)
            put("updatedAt", updatedAt)
            put("message", message)
            put("bids", JSONArray().apply { bids.forEach { put(JSONObject().put("price", it.price).put("volume", it.volume)) } })
            put("asks", JSONArray().apply { asks.forEach { put(JSONObject().put("price", it.price).put("volume", it.volume)) } })
            put("trades", JSONArray().apply { trades.forEach { put(JSONObject().put("time", it.time).put("price", it.price).put("volume", it.volume).put("side", it.side)) } })
        }
        return Level2SnapshotEntity(code, root.toString(), source, simulated, System.currentTimeMillis())
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
            message = root.optString("message")
        )
    }.getOrNull()
}
