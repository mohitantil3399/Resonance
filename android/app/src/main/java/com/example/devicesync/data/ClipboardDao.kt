package com.example.devicesync.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardDao {

    /**
     * Get all clipboard items ordered newest-first.
     * Returns a Flow so the UI (and sync engine) react to changes automatically.
     */
    @Query("SELECT * FROM clipboard_items ORDER BY timestamp DESC")
    fun getAllItems(): Flow<List<ClipboardItem>>

    /**
     * Get the most recent clipboard item.
     */
    @Query("SELECT * FROM clipboard_items ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestItem(): ClipboardItem?

    /**
     * Check if a specific clipboardId already exists (for loop prevention).
     */
    @Query("SELECT COUNT(*) FROM clipboard_items WHERE clipboardId = :id")
    suspend fun existsById(id: String): Int

    /**
     * Insert a new item, replacing if the same ID somehow arrives twice.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ClipboardItem)

    /**
     * Get total count of items currently stored.
     */
    @Query("SELECT COUNT(*) FROM clipboard_items")
    suspend fun getCount(): Int

    /**
     * Delete the oldest item (used to enforce the 10-item cap).
     */
    @Query("DELETE FROM clipboard_items WHERE clipboardId = (SELECT clipboardId FROM clipboard_items ORDER BY timestamp ASC LIMIT 1)")
    suspend fun deleteOldest()
}
