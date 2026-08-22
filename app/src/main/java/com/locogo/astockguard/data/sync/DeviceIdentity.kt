package com.locogo.astockguard.data.sync

import android.content.Context
import java.util.UUID

class DeviceIdentity(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("device_identity", Context.MODE_PRIVATE)
    val id: String
        get() {
            prefs.getString("device_id", null)?.takeIf { it.isNotBlank() }?.let { return it }
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", generated).apply()
            return generated
        }
}
