package com.example.devicesync

import java.util.Locale

/**
 * Shared formatting utilities used across UI components, ViewModels, and Activities.
 */
object FormatUtils {

    /**
     * Formats a byte count into a human-readable string (e.g. "1.5 MB", "300 KB").
     */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, 3)
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
