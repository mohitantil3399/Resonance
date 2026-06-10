package com.example.devicesync.ui.screen

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.devicesync.ui.components.*
import com.example.devicesync.ui.viewmodel.SyncViewModel

/**
 * Main sync screen — assembles UI components and wires them to the ViewModel.
 * Stateless with respect to business logic; all state lives in [SyncViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(viewModel: SyncViewModel) {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipboardItems by viewModel.clipboardItems.collectAsState()

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
                                fontSize = 20.sp
                            )
                            Text(
                                text = "Android ↔ Windows",
                                color = SyncColors.Overlay,
                                fontSize = 12.sp
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
            // ── Connection status ──
            ConnectionStatusCard(connectedClients = viewModel.connectedClientsCount)

            Spacer(modifier = Modifier.height(12.dp))

            // ── Quick send button (reads system clipboard on tap) ──
            SendClipboardButton(
                onClick = { viewModel.readAndSendClipboard(clipboardManager) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Paste / type & send ──
            PasteAndSendCard(
                pasteText = viewModel.pasteText,
                onTextChange = viewModel::onPasteTextChanged,
                onPasteFromClipboard = { viewModel.pasteFromClipboard(clipboardManager) },
                onSend = viewModel::sendPasteText
            )

            // ── Status message ──
            StatusMessage(
                message = viewModel.sendStatus,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Clipboard journal header ──
            Text(
                text = "📋 Clipboard Journal",
                color = SyncColors.Subtext,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── Clipboard history list ──
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
        }
    }
}
