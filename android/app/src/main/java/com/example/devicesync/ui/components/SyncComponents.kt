package com.example.devicesync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.devicesync.data.ClipboardItem
import java.text.SimpleDateFormat
import java.util.*

// ─── Catppuccin Mocha palette ───────────────────────────────────────────

object SyncColors {
    val Base = Color(0xFF1E1E2E)
    val Surface0 = Color(0xFF313244)
    val Surface1 = Color(0xFF45475A)
    val Text = Color(0xFFCDD6F4)
    val Subtext = Color(0xFFA6ADC8)
    val Overlay = Color(0xFF6C7086)
    val Green = Color(0xFFA6E3A1)
    val Red = Color(0xFFF38BA8)
    val Blue = Color(0xFF89B4FA)
    val Mauve = Color(0xFFCBA6F7)
}

// ─── Connection Status Card ─────────────────────────────────────────────

@Composable
fun ConnectionStatusCard(
    connectedClients: Int,
    modifier: Modifier = Modifier
) {
    val isConnected = connectedClients > 0

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SyncColors.Surface0)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (isConnected) SyncColors.Green else SyncColors.Red)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = if (isConnected) "Windows Connected ✓" else "Waiting for Windows...",
                    color = SyncColors.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Text(
                    text = if (isConnected) "$connectedClients client(s) connected via WebSocket"
                    else "Start the Windows agent and connect to this hotspot",
                    color = SyncColors.Overlay,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ─── Send Clipboard Button ──────────────────────────────────────────────

@Composable
fun SendClipboardButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SyncColors.Mauve)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = "Send",
            tint = SyncColors.Base
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "Send Clipboard to PC",
            color = SyncColors.Base,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}

// ─── Paste & Send Text Field Card ───────────────────────────────────────

@Composable
fun PasteAndSendCard(
    pasteText: String,
    onTextChange: (String) -> Unit,
    onPasteFromClipboard: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SyncColors.Surface0)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Or paste / type text to send:",
                color = SyncColors.Subtext,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            OutlinedTextField(
                value = pasteText,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste text here...", color = SyncColors.Overlay) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = SyncColors.Text,
                    unfocusedTextColor = SyncColors.Text,
                    cursorColor = SyncColors.Mauve,
                    focusedBorderColor = SyncColors.Mauve,
                    unfocusedBorderColor = SyncColors.Surface1
                ),
                maxLines = 3,
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onPasteFromClipboard,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SyncColors.Blue)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = "Paste",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Paste", fontSize = 13.sp)
                }

                Button(
                    onClick = onSend,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    enabled = pasteText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SyncColors.Green,
                        disabledContainerColor = SyncColors.Surface1
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = SyncColors.Base,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Send", color = SyncColors.Base, fontSize = 13.sp)
                }
            }
        }
    }
}

// ─── Clipboard History Card ─────────────────────────────────────────────

@Composable
fun ClipboardHistoryCard(
    item: ClipboardItem,
    modifier: Modifier = Modifier
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val isAndroid = item.source == "android"

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = SyncColors.Surface1)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = item.content,
                color = SyncColors.Text,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                Text(
                    text = if (isAndroid) "📱 Android" else "💻 Windows",
                    color = SyncColors.Blue,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = timeFormat.format(Date(item.timestamp)),
                    color = SyncColors.Overlay,
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ─── Empty State ────────────────────────────────────────────────────────

@Composable
fun ClipboardEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No clipboard items yet.\nCopy something and tap Send!",
            color = SyncColors.Overlay,
            fontSize = 14.sp
        )
    }
}

// ─── Status Toast ───────────────────────────────────────────────────────

@Composable
fun StatusMessage(
    message: String,
    modifier: Modifier = Modifier
) {
    if (message.isNotBlank()) {
        Text(
            text = message,
            color = SyncColors.Green,
            fontSize = 13.sp,
            modifier = modifier.padding(start = 4.dp)
        )
    }
}
