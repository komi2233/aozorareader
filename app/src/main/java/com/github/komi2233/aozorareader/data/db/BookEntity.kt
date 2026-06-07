package com.github.komi2233.aozorareader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books", primaryKeys = ["personId", "bookId"])
data class BookEntity(
    val personId: String,
    val bookId: String,
    val title: String,
    val author: String,
    val isDownloaded: Boolean = false,
    val rawText: String? = null,
    val lastReadPosition: Int = 0,
    val scrollY: Int = 0,
    val lastOpenedAt: Long = 0
)
