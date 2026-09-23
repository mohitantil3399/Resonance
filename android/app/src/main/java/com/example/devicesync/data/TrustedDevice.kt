package com.example.devicesync.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent record of a Windows PC that has been approved by the user.
 * Once stored, future connections from this deviceId skip the approval dialog.
 */
@Entity(tableName = "trusted_devices")
data class TrustedDevice(
    @PrimaryKey
    val deviceId: String,           // Unique ID sent by Windows during key_exchange
    val deviceName: String,         // Human-readable machine name (Environment.MachineName)
    val approvedAt: Long            // Epoch milliseconds when first approved
)
