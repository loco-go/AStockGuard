package com.locogo.astockguard.ui.main

import com.locogo.astockguard.MonitorSnapshot

data class MainUiState(
    val loading: Boolean = false,
    val snapshot: MonitorSnapshot? = null,
    val aiLoading: Boolean = false,
    val aiText: String = "设置页可粘贴配置；AI不可用时本地 E/M/R2 仍独立运行。",
    val error: String? = null
)
