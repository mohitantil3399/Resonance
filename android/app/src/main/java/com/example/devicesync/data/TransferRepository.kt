package com.example.devicesync.data

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Repository managing file transfer records with a 20-item cap.
 */
class TransferRepository(private val dao: TransferDao) {

    companion object {
        const val MAX_ITEMS = 20
    }

    /** All transfers as a reactive Flow */
    fun getAllTransfers(): Flow<List<TransferItem>> = dao.getAllTransfers()

    /**
     * Add a new transfer record.
     */
    suspend fun addTransfer(
        fileName: String,
        fileSize: Long,
        mimeType: String,
        direction: String,
        status: String = "completed",
        localUriOrPath: String? = null,
        transferId: String? = null
    ): String {
        val id = transferId ?: UUID.randomUUID().toString()
        val item = TransferItem(
            transferId = id,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            direction = direction,
            status = status,
            localUriOrPath = localUriOrPath,
            timestamp = System.currentTimeMillis()
        )

        dao.insert(item)

        while (dao.getCount() > MAX_ITEMS) {
            dao.deleteOldest()
        }

        return id
    }

    /**
     * Update the status of an existing transfer.
     */
    suspend fun updateTransferStatus(id: String, status: String, localUriOrPath: String? = null) {
        dao.updateStatus(id, status, localUriOrPath)
    }
}
