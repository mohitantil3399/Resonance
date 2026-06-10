package com.example.devicesync.ui.viewmodel

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.devicesync.ClipboardPayload
import com.example.devicesync.SyncForegroundService
import com.example.devicesync.data.ClipboardItem
import com.example.devicesync.data.ClipboardRepository
import com.example.devicesync.data.SyncDatabase
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ViewModel for the main sync screen.
 * Survives configuration changes and owns all clipboard sync state/logic.
 */
class SyncViewModel(application: Application) : AndroidViewModel(application) {

    // ── Dependencies ────────────────────────────────────────────────────
    private val clipboardRepo: ClipboardRepository
    private val gson = Gson()

    init {
        val db = SyncDatabase.getInstance(application)
        clipboardRepo = ClipboardRepository(db.clipboardDao())
    }

    // ── Exposed state ───────────────────────────────────────────────────

    /** Live clipboard history from Room (newest first) */
    val clipboardItems: StateFlow<List<ClipboardItem>> = clipboardRepo.getAllItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Text currently in the paste text field */
    var pasteText by mutableStateOf("")
        private set

    /** Transient status message shown after send/paste actions */
    var sendStatus by mutableStateOf("")
        private set

    /** Number of connected Windows clients — polled from the service companion */
    var connectedClientsCount by mutableIntStateOf(0)
        private set

    init {
        // Poll the connected clients count every second so the UI stays current.
        // This is lightweight — just reads a ConcurrentHashMap.size.
        viewModelScope.launch {
            while (true) {
                connectedClientsCount = SyncForegroundService.connectedClientsCount
                delay(1_000)
            }
        }
    }

    // ── User actions ────────────────────────────────────────────────────

    /** Update the paste text field */
    fun onPasteTextChanged(text: String) {
        pasteText = text
    }

    /**
     * Read the system clipboard and send its contents to Windows.
     * Must be called from an Activity context (which has focus → can read clipboard).
     */
    fun readAndSendClipboard(clipboardManager: ClipboardManager) {
        val clip = clipboardManager.primaryClip
        val text = clip?.getItemAt(0)?.text?.toString()

        if (text.isNullOrBlank()) {
            showStatus("Clipboard is empty")
            return
        }

        viewModelScope.launch {
            sendToWindows(text)
            showStatus("Sent: ${text.take(30)}...")
        }
    }

    /**
     * Paste from the system clipboard into the text field.
     */
    fun pasteFromClipboard(clipboardManager: ClipboardManager) {
        val clip = clipboardManager.primaryClip
        val text = clip?.getItemAt(0)?.text?.toString() ?: ""
        pasteText = text
    }

    /**
     * Send whatever is in the paste text field to Windows.
     */
    fun sendPasteText() {
        if (pasteText.isBlank()) return

        val text = pasteText
        viewModelScope.launch {
            sendToWindows(text)
            showStatus("Sent ✓")
            pasteText = ""
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────

    /**
     * Save clipboard text to the local Room database and push it
     * to all connected Windows clients via WebSocket.
     */
    private suspend fun sendToWindows(text: String) {
        val newId = UUID.randomUUID().toString()
        val inserted = clipboardRepo.addItem(
            content = text,
            source = "android",
            clipboardId = newId
        )

        if (inserted) {
            val payload = ClipboardPayload(
                clipboardId = newId,
                source = "android",
                content = text
            )
            SyncForegroundService.sendToAllClients(gson.toJson(payload))
        }
    }

    private fun showStatus(message: String) {
        sendStatus = message
        viewModelScope.launch {
            delay(2_000)
            if (sendStatus == message) sendStatus = ""
        }
    }
}
