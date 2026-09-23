package com.example.devicesync.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ClipboardItem::class, TransferItem::class, TrustedDevice::class], version = 3, exportSchema = false)
abstract class SyncDatabase : RoomDatabase() {

    abstract fun clipboardDao(): ClipboardDao
    abstract fun transferDao(): TransferDao
    abstract fun trustedDeviceDao(): TrustedDeviceDao

    companion object {
        @Volatile
        private var INSTANCE: SyncDatabase? = null

        fun getInstance(context: Context): SyncDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SyncDatabase::class.java,
                    "sync_database"
                )
                .fallbackToDestructiveMigration(true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
