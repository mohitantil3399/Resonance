package com.example.devicesync

import android.content.Context
import android.net.Uri
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages file transfers by creating transfer tokens.
 * When a file is shared to the app, a unique token is generated.
 * The Windows agent can then download the file via:
 *   GET /transfer/{token}
 * 
 * The phone serves the file directly from its content provider URI.
 */
object FileTransferManager {

    private const val TAG = "FileTransfer"

    data class TransferInfo(
        val token: String,
        val uri: Uri,
        val fileName: String,
        val mimeType: String,
        val size: Long
    )

    // Active transfers indexed by token
    private val activeTransfers = ConcurrentHashMap<String, TransferInfo>()

    /**
     * Register a new file for transfer.
     * Returns the transfer token.
     */
    fun registerTransfer(context: Context, uri: Uri): String {
        val token = UUID.randomUUID().toString().take(8)

        val contentResolver = context.contentResolver
        val cursor = contentResolver.query(uri, null, null, null, null)

        var fileName = "unknown"
        var size = 0L

        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (nameIndex >= 0) fileName = it.getString(nameIndex) ?: "unknown"
                if (sizeIndex >= 0) size = it.getLong(sizeIndex)
            }
        }

        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

        val info = TransferInfo(
            token = token,
            uri = uri,
            fileName = fileName,
            mimeType = mimeType,
            size = size
        )

        activeTransfers[token] = info
        Log.i(TAG, "Registered transfer: $token → $fileName ($size bytes)")

        return token
    }

    /** Get transfer info by token */
    fun getTransfer(token: String): TransferInfo? = activeTransfers[token]

    /** Remove a completed transfer */
    fun removeTransfer(token: String) {
        activeTransfers.remove(token)
    }

    /** List all active transfers */
    fun listTransfers(): List<TransferInfo> = activeTransfers.values.toList()
}
