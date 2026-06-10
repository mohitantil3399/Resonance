package com.example.devicesync

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.example.devicesync.ui.screen.SyncScreen
import com.example.devicesync.ui.theme.DeviceSyncTheme
import com.example.devicesync.ui.viewmodel.SyncViewModel

/**
 * Entry point. Thin shell that:
 *  1. Requests notification permission (Android 13+)
 *  2. Starts the sync foreground service
 *  3. Renders the Compose UI via [SyncScreen] + [SyncViewModel]
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SyncViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _: Boolean ->
        startSyncService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DeviceSyncTheme {
                SyncScreen(viewModel = viewModel)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startSyncService()
        }
    }

    private fun startSyncService() {
        val serviceIntent = Intent(this, SyncForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}