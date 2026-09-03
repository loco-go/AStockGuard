package com.locogo.astockguard.data.local

/*
 * 文件职责：保存已规范化的新闻条目与发布时间；URL 或稳定标识用于去重。
 * 架构边界：修改实体或字段前先设计 Room 版本迁移、导出 schema，并验证旧库升级。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "news_item", indices = [Index(value = ["publishedAt"])])
data class NewsItemEntity(
    @PrimaryKey val id: String,
    val source: String,
    val title: String,
    val url: String,
    val publishedAt: Long,
    val fetchedAt: Long
)
