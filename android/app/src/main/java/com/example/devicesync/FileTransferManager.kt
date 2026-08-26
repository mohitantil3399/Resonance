package com.example.devicesync

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.devicesync.data.SyncDatabase
import com.example.devicesync.data.TransferRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages outgoing file transfers by creating tokens and staging content.
 * 
 * Staging files into internal cache solves the Android content URI permission
 * expiration problem (which occurs when the originating app or ShareReceiverActivity finishes).
 */
object FileTransferManager {

    private const val TAG = "FileTransfer"

    data class TransferInfo(
        val token: String,
        val file: File,
        val fileName: String,
        val mimeType: String,
        val size: Long
    )

    private val activeTransfers = ConcurrentHashMap<String, TransferInfo>()
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Register a single URI for transfer.
     */
    fun registerTransfer(context: Context, uri: Uri): TransferInfo? {
        val list = registerTransfers(context, listOf(uri))
        return list.firstOrNull()
    }

    /**
     * Register multiple URIs for transfer in batch.
     * Copies each item to temporary cache to guarantee readability over HTTP.
     */
    fun registerTransfers(context: Context, uris: List<Uri>): List<TransferInfo> {
        val results = mutableListOf<TransferInfo>()
        val stagingDir = File(context.cacheDir, "outgoing_transfers").apply { mkdirs() }
        val db = SyncDatabase.getInstance(context)
        val transferRepo = TransferRepository(db.transferDao())

        for (uri in uris) {
            try {
                val (fileName, size, mimeType) = extractMetadata(context, uri)
                val token = UUID.randomUUID().toString().take(8)

                // Sanitize filename for local storage
                val safeFileName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val stagedFile = File(stagingDir, "${token}_$safeFileName")

                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(stagedFile).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                } ?: continue

                val actualSize = if (size > 0) size else stagedFile.length()

                val info = TransferInfo(
                    token = token,
                    file = stagedFile,
                    fileName = fileName,
                    mimeType = mimeType,
                    size = actualSize
                )

                activeTransfers[token] = info
                results.add(info)

                Log.i(TAG, "Registered transfer: $token → $fileName ($actualSize bytes)")

                // Record in transfer history
                scope.launch {
                    transferRepo.addTransfer(
                        fileName = fileName,
                        fileSize = actualSize,
                        mimeType = mimeType,
                        direction = "sent",
                        status = "completed",
                        localUriOrPath = uri.toString(),
                        transferId = token
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register transfer for $uri", e)
            }
        }

        // Proactively notify any connected Windows clients via WebSocket
        if (results.isNotEmpty()) {
            SyncForegroundService.notifyFilesAvailable(results)
        }

        return results
    }

    private fun extractMetadata(context: Context, uri: Uri): Triple<String, Long, String> {
        var fileName = "file_${System.currentTimeMillis()}"
        var size = 0L

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.let { fileName = it }
                    }
                    if (sizeIdx >= 0) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query URI metadata for $uri", e)
        }

        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        return Triple(fileName, size, mimeType)
    }

    /** Get transfer info by token */
    fun getTransfer(token: String): TransferInfo? = activeTransfers[token]

    /** Remove and clean up a completed transfer */
    fun removeTransfer(token: String) {
        val info = activeTransfers.remove(token)
        info?.file?.delete()
    }

    /** List all active transfers */
    fun listTransfers(): List<TransferInfo> = activeTransfers.values.toList()
}
