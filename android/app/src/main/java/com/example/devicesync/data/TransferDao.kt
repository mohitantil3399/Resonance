package com.example.devicesync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {

    /**
     * Get all transfer items ordered newest-first.
     * Returns a Flow so the UI reacts to changes automatically.
     */
    @Query("SELECT * FROM file_transfers ORDER BY timestamp DESC")
    fun getAllTransfers(): Flow<List<TransferItem>>

    /**
     * Insert a new item, replacing if the same ID arrives twice.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: TransferItem)

    /**
     * Update the status and optional path of a transfer.
     */
    @Query("UPDATE file_transfers SET status = :status, localUriOrPath = :localUriOrPath WHERE transferId = :id")
    suspend fun updateStatus(id: String, status: String, localUriOrPath: String? = null)

    /**
     * Get total count of items currently stored.
     */
    @Query("SELECT COUNT(*) FROM file_transfers")
    suspend fun getCount(): Int

    /**
     * Delete the oldest item (used to enforce the 20-item cap).
     */
    @Query("DELETE FROM file_transfers WHERE transferId = (SELECT transferId FROM file_transfers ORDER BY timestamp ASC LIMIT 1)")
    suspend fun deleteOldest()
}
