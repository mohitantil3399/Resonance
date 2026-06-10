package com.example.devicesync.data

import java.util.UUID

/**
 * Central repository for clipboard operations.
 * Enforces the 10-item retention cap and loop prevention.
 */
class ClipboardRepository(private val dao: ClipboardDao) {

    companion object {
        const val MAX_ITEMS = 10
    }

    /** All items as a reactive Flow for UI observation */
    fun getAllItems() = dao.getAllItems()

    /**
     * Add a new clipboard entry.
     * @return true if the item was new and inserted, false if it was a duplicate (loop prevention).
     */
    suspend fun addItem(content: String, source: String, clipboardId: String? = null): Boolean {
        val id = clipboardId ?: UUID.randomUUID().toString()

        // Loop prevention: skip if we already have this exact ID
        if (dao.existsById(id) > 0) return false

        val item = ClipboardItem(
            clipboardId = id,
            source = source,
            content = content,
            timestamp = System.currentTimeMillis()
        )

        dao.insert(item)

        // Enforce the 10-item cap by deleting the oldest entries
        while (dao.getCount() > MAX_ITEMS) {
            dao.deleteOldest()
        }

        return true
    }

    /** Check if a clipboardId has already been processed */
    suspend fun alreadyExists(clipboardId: String): Boolean {
        return dao.existsById(clipboardId) > 0
    }

    /** Get the most recent item */
    suspend fun getLatest(): ClipboardItem? {
        return dao.getLatestItem()
    }
}
