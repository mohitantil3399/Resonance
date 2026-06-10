package com.example.devicesync.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a single clipboard entry in the shared journal.
 * Max 10 entries are retained at any time.
 */
@Entity(tableName = "clipboard_items")
data class ClipboardItem(
    @PrimaryKey
    val clipboardId: String,         // UUID to uniquely identify this clip
    val source: String,              // "android" or "windows"
    val content: String,             // The actual clipboard text
    val timestamp: Long              // epoch millis when copied
)
