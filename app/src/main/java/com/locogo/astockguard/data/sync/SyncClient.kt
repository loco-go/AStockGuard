package com.locogo.astockguard.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SyncClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    suspend fun exchange(baseUrl: String, token: String, request: SyncRequest): SyncResponse = withContext(Dispatchers.IO) {
        require(baseUrl.startsWith("https://")) { "同步服务必须使用 HTTPS" }
        require(token.isNotBlank()) { "同步 Token 不能为空" }
        val body = JSONObject().apply {
            put("deviceId", request.deviceId)
            put("cursor", request.cursor)
            put("records", JSONArray().apply { request.records.forEach { put(it.toJson()) } })
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val http = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/sync")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post(body)
            .build()
        client.newCall(http).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Sync HTTP ${response.code}: ${text.take(300)}")
            parseResponse(JSONObject(text))
        }
    }

    suspend fun health(baseUrl: String, token: String): String = withContext(Dispatchers.IO) {
        require(baseUrl.startsWith("https://")) { "同步服务必须使用 HTTPS" }
        val builder = Request.Builder().url(baseUrl.trimEnd('/') + "/health").get()
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("Sync health HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private fun SyncRecord.toJson() = JSONObject().apply {
        put("id", id); put("deviceId", deviceId); put("type", type); put("updatedAt", updatedAt)
        put("deleted", deleted); put("payload", JSONObject(payload))
    }

    private fun parseResponse(json: JSONObject): SyncResponse {
        val records = buildList {
            val arr = json.optJSONArray("records") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val payload = o.optJSONObject("payload")?.toString() ?: "{}"
                add(SyncRecord(
                    id = o.optString("id"),
                    deviceId = o.optString("deviceId"),
                    type = o.optString("type"),
                    updatedAt = o.optLong("updatedAt"),
                    deleted = o.optBoolean("deleted"),
                    payload = payload
                ))
            }
        }
        return SyncResponse(
            cursor = json.optLong("cursor"),
            records = records,
            hasMore = json.optBoolean("hasMore", false)
        )
    }
}
