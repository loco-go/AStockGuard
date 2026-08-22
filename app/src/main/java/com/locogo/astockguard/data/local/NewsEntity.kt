package com.locogo.astockguard.data.local

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
