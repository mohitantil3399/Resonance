package com.example.devicesync.ui.screen

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.devicesync.data.TransferItem
import com.example.devicesync.notifications.DeviceSyncNotificationListener
import com.example.devicesync.ui.components.*
import com.example.devicesync.ui.viewmodel.SyncViewModel

/**
 * Main sync screen — assembles UI components and wires them to the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(viewModel: SyncViewModel) {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipboardItems by viewModel.clipboardItems.collectAsState()
    val transferItems by viewModel.transferItems.collectAsState()
    val connectionRequest by viewModel.pendingConnectionRequest.collectAsState()

    // ── Modern Pickers ──
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                viewModel.sendFiles(uris)
            }
        }
    )

    val docPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                viewModel.sendFiles(uris)
            }
        }
    )

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { treeUri ->
            if (treeUri != null) {
                viewModel.updateCustomStorageUri(treeUri)
            }
        }
    )

    Scaffold(
        containerColor = SyncColors.Base,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚡", fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Local Sync Agent",
                                color = SyncColors.Text,
                                fontWeight = FontWeight.Bold,
                                fontSize = 19.sp
                            )
                            Text(
                                text = "Android ↔ Windows",
                                color = SyncColors.Overlay,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SyncColors.Base
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // ── Connection status card ──
            ConnectionStatusCard(connectedClients = viewModel.connectedClientsCount)

            Spacer(modifier = Modifier.height(10.dp))

            // ── Mode Tab Selector ──
            ModeTabSelector(
                selectedIndex = viewModel.selectedTabIndex,
                onTabSelected = viewModel::setTabIndex
            )

            // ── Transient status message ──
            StatusMessage(
                message = viewModel.sendStatus,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            connectionRequest?.let { request ->
                AlertDialog(
                    onDismissRequest = { request.onResult(false) },
                    title = { Text("New Device Connection") },
                    text = { Text("Allow connection from ${request.deviceName}?") },
                    confirmButton = {
                        TextButton(onClick = { request.onResult(true) }) {
                            Text("Approve")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { request.onResult(false) }) {
                            Text("Decline")
                        }
                    }
                )
            }

            if (viewModel.selectedTabIndex == 0) {
                // ══════════════════════════════════════════════════════════
                // 📋 CLIPBOARD SYNC VIEW
                // ══════════════════════════════════════════════════════════

                // Quick send button (reads system clipboard on tap)
                SendClipboardButton(
                    onClick = { viewModel.readAndSendClipboard(clipboardManager) }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Paste / type & send card
                PasteAndSendCard(
                    pasteText = viewModel.pasteText,
                    onTextChange = viewModel::onPasteTextChanged,
                    onPasteFromClipboard = { viewModel.pasteFromClipboard(clipboardManager) },
                    onSend = viewModel::sendPasteText
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "📋 Clipboard Journal (last 10)",
                    color = SyncColors.Subtext,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = clipboardItems,
                        key = { it.clipboardId }
                    ) { item ->
                        ClipboardHistoryCard(item = item)
                    }

                    if (clipboardItems.isEmpty()) {
                        item { ClipboardEmptyState() }
                    }
                }
            } else if (viewModel.selectedTabIndex == 1) {
                // ══════════════════════════════════════════════════════════
                // 📁 FILE TRANSFERS VIEW
                // ══════════════════════════════════════════════════════════

                // Quick send actions
                FileTransferActionsRow(
                    onPickPhotos = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    onPickDocs = {
                        docPickerLauncher.launch(arrayOf("*/*"))
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Storage location settings card
                StorageSettingsCard(
                    storagePathDescription = viewModel.storagePathDescription,
                    onChangeFolder = { folderPickerLauncher.launch(null) },
                    onResetDefault = viewModel::resetStorageToDefault
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "📁 Transfer Activity",
                    color = SyncColors.Subtext,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = transferItems,
                        key = { it.transferId }
                    ) { item ->
                        TransferHistoryCard(
                            item = item,
                            onOpen = { openFile(context, it) }
                        )
                    }

                    if (transferItems.isEmpty()) {
                        item { TransferEmptyState() }
                    }
                }
            } else if (viewModel.selectedTabIndex == 2) {
                // ══════════════════════════════════════════════════════════
                // 🔔 NOTIFICATIONS VIEW
                // ══════════════════════════════════════════════════════════

                NotificationSettingsView(viewModel = viewModel)
            }
        }
    }
}

private fun openFile(context: Context, item: TransferItem) {
    val pathString = item.localUriOrPath ?: return
    try {
        val uri = Uri.parse(pathString)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(viewIntent)
    } catch (e: Exception) {
        Log.e("SyncScreen", "Unable to open file ${item.fileName}", e)
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 🔔 NOTIFICATION SETTINGS VIEW
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun NotificationSettingsView(viewModel: SyncViewModel) {
    val context = LocalContext.current
    val isListenerEnabled = DeviceSyncNotificationListener.isEnabled(context)
    val isMirroringOn = viewModel.notificationMirroringEnabled

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Status Card ──
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SyncColors.Surface0,
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isListenerEnabled && isMirroringOn)
                            Icons.Default.NotificationsActive
                        else
                            Icons.Default.Notifications,
                        contentDescription = "Notifications",
                        tint = if (isListenerEnabled && isMirroringOn)
                            SyncColors.Green else SyncColors.Overlay
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Notification Mirroring",
                            color = SyncColors.Text,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = if (isListenerEnabled && isMirroringOn)
                                "Active — notifications forwarding to Windows"
                            else if (!isListenerEnabled)
                                "Notification access not granted"
                            else
                                "Mirroring paused",
                            color = SyncColors.Overlay,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // ── Notification Access Permission ──
        if (!isListenerEnabled) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SyncColors.Surface1,
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "⚠️ Grant Notification Access",
                        color = SyncColors.Peach,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "DeviceSync needs permission to read your notifications " +
                                "so it can mirror them to your PC. Tap below to open Settings.",
                        color = SyncColors.Subtext,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { DeviceSyncNotificationListener.requestAccess(context) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SyncColors.Mauve)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = SyncColors.Base
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Open Notification Settings",
                            color = SyncColors.Base,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // ── Mirroring Toggle ──
        if (isListenerEnabled) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SyncColors.Surface0,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Mirroring",
                            color = SyncColors.Text,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Forward notifications to Windows PC",
                            color = SyncColors.Overlay,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = isMirroringOn,
                        onCheckedChange = { viewModel.setNotificationMirroring(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SyncColors.Base,
                            checkedTrackColor = SyncColors.Green,
                            uncheckedThumbColor = SyncColors.Overlay,
                            uncheckedTrackColor = SyncColors.Surface1
                        )
                    )
                }
            }
        }

        // ── Info Card ──
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SyncColors.Surface0,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "ℹ️ How it works",
                    color = SyncColors.Blue,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "• WhatsApp, SMS, and app notifications appear as Windows toasts\n" +
                            "• Incoming calls show Answer/Decline buttons on your PC\n" +
                            "• Notifications are session-only — cleared when you disconnect\n" +
                            "• All data is encrypted with AES-256-GCM",
                    color = SyncColors.Subtext,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
