package com.example.devicesync.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for trusted Windows device records.
 */
@Dao
interface TrustedDeviceDao {

    /** All trusted devices, newest first — used for the UI list. */
    @Query("SELECT * FROM trusted_devices ORDER BY approvedAt DESC")
    fun getAllDevices(): Flow<List<TrustedDevice>>

    /** Check if a deviceId is already trusted (single-shot lookup on IO thread). */
    @Query("SELECT COUNT(*) FROM trusted_devices WHERE deviceId = :deviceId")
    suspend fun countById(deviceId: String): Int

    /** Persist a newly approved device. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: TrustedDevice)

    /** Remove a trusted device (revoke access). */
    @Query("DELETE FROM trusted_devices WHERE deviceId = :deviceId")
    suspend fun deleteById(deviceId: String)
}
