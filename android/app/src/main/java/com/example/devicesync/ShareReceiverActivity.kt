package com.example.devicesync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.devicesync.ui.theme.DeviceSyncTheme
import java.util.UUID

/**
 * Handles incoming share intents from other apps.
 * When a user shares a file/text from any app to DeviceSync,
 * this activity receives it and registers it for transfer.
 */
class ShareReceiverActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ShareReceiver"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = handleShareIntent(intent)

        setContent {
            DeviceSyncTheme {
                ShareReceiverScreen(sharedText) {
                    finish()
                }
            }
        }
    }

    private fun handleShareIntent(intent: Intent?): String? {
        if (intent == null) return null

        return when (intent.action) {
            Intent.ACTION_SEND -> {
                when {
                    intent.type == "text/plain" -> {
                        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                        Log.i(TAG, "Received shared text: ${text?.take(50)}")
                        text
                    }
                    intent.type?.startsWith("image/") == true ||
                    intent.type?.startsWith("video/") == true ||
                    intent.type?.startsWith("application/") == true -> {
                        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                        if (uri != null) {
                            FileTransferManager.registerTransfer(applicationContext, uri)
                            "File queued for transfer"
                        } else null
                    }
                    else -> {
                        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                        if (uri != null) {
                            FileTransferManager.registerTransfer(applicationContext, uri)
                            "File queued for transfer"
                        } else {
                            intent.getStringExtra(Intent.EXTRA_TEXT)
                        }
                    }
                }
            }
            else -> null
        }
    }
}

@Composable
fun ShareReceiverScreen(message: String?, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "⚡",
                style = MaterialTheme.typography.displayLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (message != null) "Shared successfully!" else "Nothing to share",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (message != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message.take(100),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onDismiss) {
                Text("Done")
            }
        }
    }
}
