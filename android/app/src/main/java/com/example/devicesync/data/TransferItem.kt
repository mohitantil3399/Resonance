package com.example.devicesync.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a single file transfer record (sent or received).
 * Up to 20 entries are retained at any time.
 */
@Entity(tableName = "file_transfers")
data class TransferItem(
    @PrimaryKey
    val transferId: String,          // Unique UUID for this transfer
    val fileName: String,            // Display filename (e.g. photo.jpg)
    val fileSize: Long,              // File size in bytes
    val mimeType: String,            // MIME type (e.g. image/jpeg, application/pdf)
    val direction: String,           // "sent" (phone -> pc) or "received" (pc -> phone)
    val status: String,              // "completed", "failed", or "in_progress"
    val localUriOrPath: String? = null, // Content URI or local path for tap-to-open
    val timestamp: Long              // Epoch milliseconds
)
