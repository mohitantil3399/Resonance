package com.example.devicesync

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.devicesync.ui.components.SyncColors
import com.example.devicesync.ui.theme.DeviceSyncTheme
import kotlinx.coroutines.*

/**
 * Handles incoming share intents from other apps (e.g. Gallery, Photos, Files).
 * Supports single file (ACTION_SEND) and batch files (ACTION_SEND_MULTIPLE).
 */
class ShareReceiverActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ShareReceiver"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure background sync service is alive to serve the files
        ensureSyncServiceRunning()

        val shareResult = processIncomingIntent(intent)

        setContent {
            DeviceSyncTheme {
                ShareReceiverScreen(
                    result = shareResult,
                    onDismiss = { finish() }
                )
            }
        }
    }

    private fun ensureSyncServiceRunning() {
        val serviceIntent = Intent(this, SyncForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    data class ShareResult(
        val isSuccess: Boolean,
        val title: String,
        val detail: String,
        val count: Int = 0
    )

    private fun processIncomingIntent(intent: Intent?): ShareResult {
        if (intent == null) {
            return ShareResult(false, "No data received", "Empty share request")
        }

        val action = intent.action
        val uris = mutableListOf<Uri>()

        when (action) {
            Intent.ACTION_SEND -> {
                // Check if it's text
                if (intent.type == "text/plain" && !intent.hasExtra(Intent.EXTRA_STREAM)) {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!text.isNullOrBlank()) {
                        val newId = java.util.UUID.randomUUID().toString()
                        val payload = ClipboardPayload(newId, "android", text)
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            val db = com.example.devicesync.data.SyncDatabase.getInstance(applicationContext)
                            com.example.devicesync.data.ClipboardRepository(db.clipboardDao()).addItem(text, "android", newId)
                            SyncForegroundService.sendToAllClients(com.google.gson.Gson().toJson(payload))
                        }
                        return ShareResult(true, "Text Synced 📋", text.take(60), 1)
                    }
                }

                // Check for single file stream
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    uris.add(uri)
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                // Check for multiple file streams
                val streamList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (streamList != null) {
                    uris.addAll(streamList)
                }
            }
        }

        // Also check ClipData if EXTRA_STREAM didn't populate
        if (uris.isEmpty() && intent.clipData != null) {
            val clip = intent.clipData
            for (i in 0 until (clip?.itemCount ?: 0)) {
                val itemUri = clip?.getItemAt(i)?.uri
                if (itemUri != null && !uris.contains(itemUri)) {
                    uris.add(itemUri)
                }
            }
        }

        if (uris.isNotEmpty()) {
            val transfers = FileTransferManager.registerTransfers(applicationContext, uris)
            val count = transfers.size
            val sizeSum = transfers.sumOf { it.size }
            val formattedSize = formatFileSize(sizeSum)
            val names = transfers.take(2).joinToString(", ") { it.fileName } +
                    if (transfers.size > 2) " and ${transfers.size - 2} more" else ""

            return ShareResult(
                isSuccess = true,
                title = if (count == 1) "File Queued for PC ⚡" else "$count Files Queued for PC ⚡",
                detail = "$names ($formattedSize)",
                count = count
            )
        }

        return ShareResult(false, "Unsupported Content", "Could not process shared item")
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, 3)
        return String.format(java.util.Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}

@Composable
fun ShareReceiverScreen(
    result: ShareReceiverActivity.ShareResult,
    onDismiss: () -> Unit
) {
    // Auto-dismiss after 2.5 seconds on success
    LaunchedEffect(result.isSuccess) {
        if (result.isSuccess) {
            delay(2500)
            onDismiss()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = SyncColors.Base
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SyncColors.Surface0),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (result.isSuccess) "⚡" else "⚠️",
                    fontSize = 36.sp
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = result.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (result.isSuccess) SyncColors.Green else SyncColors.Red
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = result.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = SyncColors.Text,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (result.isSuccess) "Connected PC will automatically download this file" else "Please try again",
                style = MaterialTheme.typography.bodySmall,
                color = SyncColors.Overlay
            )

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = SyncColors.Surface1),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Done", color = SyncColors.Text)
            }
        }
    }
}
