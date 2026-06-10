package com.example.devicesync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.devicesync.data.ClipboardRepository
import com.example.devicesync.data.SyncDatabase
import com.google.gson.Gson
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SyncForegroundService : Service() {

    companion object {
        private const val TAG = "SyncService"
        private const val CHANNEL_ID = "sync_channel"
        private const val NOTIFICATION_ID = 1
        private const val PORT = 7777
    }

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var clipboardRepo: ClipboardRepository
    private lateinit var clipboardManager: ClipboardManager
    private val gson = Gson()

    // Track WebSocket sessions for pushing clipboard updates to Windows
    private val connectedClients = ConcurrentHashMap<String, WebSocketSession>()

    // The last clipboard content we set ourselves — used for loop prevention
    @Volatile
    private var lastSetContent: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Starting..."))

        // Initialize database and repository
        val db = SyncDatabase.getInstance(applicationContext)
        clipboardRepo = ClipboardRepository(db.clipboardDao())
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        startSignalingServer()
        startClipboardListener()
        startClipboardBroadcaster()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        server?.stop(1000, 2000)
    }

    // ─── Ktor Signaling Server ──────────────────────────────────────────

    private fun startSignalingServer() {
        serviceScope.launch {
            try {
                server = embeddedServer(Netty, port = PORT, host = "0.0.0.0") {
                    install(WebSockets)

                    routing {
                        // Discovery endpoint
                        get("/hello") {
                            call.respondText(
                                gson.toJson(
                                    mapOf(
                                        "status" to "ok",
                                        "device" to (Build.MODEL ?: "android"),
                                        "protocol" to "1.0",
                                        "features" to listOf("clipboard", "file_transfer")
                                    )
                                ),
                                ContentType.Application.Json
                            )
                        }

                        // REST endpoint: Windows pushes a clipboard item here
                        post("/clipboard") {
                            try {
                                val body = call.receiveText()
                                val payload = gson.fromJson(body, ClipboardPayload::class.java)

                                if (payload.clipboardId != null && payload.content != null && payload.source != null) {
                                    val inserted = clipboardRepo.addItem(
                                        content = payload.content,
                                        source = payload.source,
                                        clipboardId = payload.clipboardId
                                    )

                                    if (inserted) {
                                // Remember what we set so clipboard listener won't echo it back
                                lastSetContent = payload.content
                                withContext(Dispatchers.Main) {
                                            val clip = ClipData.newPlainText("sync", payload.content)
                                            clipboardManager.setPrimaryClip(clip)
                                        }
                                        updateNotification("Received: ${payload.content.take(30)}...")
                                        call.respondText("""{"status":"accepted"}""", ContentType.Application.Json)
                                    } else {
                                        call.respondText("""{"status":"duplicate"}""", ContentType.Application.Json)
                                    }
                                } else {
                                    call.respondText("""{"status":"invalid"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error handling clipboard POST", e)
                                call.respondText("""{"status":"error"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                            }
                        }

                        // REST endpoint: fetch the current journal
                        get("/clipboard/history") {
                            val latest = clipboardRepo.getLatest()
                            // For simplicity, return the latest item
                            if (latest != null) {
                                call.respondText(gson.toJson(latest), ContentType.Application.Json)
                            } else {
                                call.respondText("""{"status":"empty"}""", ContentType.Application.Json)
                            }
                        }

                        // ─── File Transfer Endpoints ────────────────────────────

                        // List all active file transfers
                        get("/transfers") {
                            val transfers = FileTransferManager.listTransfers().map {
                                mapOf(
                                    "token" to it.token,
                                    "fileName" to it.fileName,
                                    "mimeType" to it.mimeType,
                                    "size" to it.size
                                )
                            }
                            call.respondText(gson.toJson(transfers), ContentType.Application.Json)
                        }

                        // Download a file by transfer token
                        get("/transfer/{token}") {
                            val token = call.parameters["token"]
                            if (token == null) {
                                call.respondText("""{"error":"missing token"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                                return@get
                            }

                            val info = FileTransferManager.getTransfer(token)
                            if (info == null) {
                                call.respondText("""{"error":"not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                                return@get
                            }

                            try {
                                val inputStream = applicationContext.contentResolver.openInputStream(info.uri)
                                if (inputStream == null) {
                                    call.respondText("""{"error":"cannot read file"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                                    return@get
                                }

                                call.response.header("Content-Disposition", "attachment; filename=\"${info.fileName}\"")
                                call.respondBytesWriter(
                                    contentType = ContentType.parse(info.mimeType),
                                    status = HttpStatusCode.OK
                                ) {
                                    inputStream.use { input ->
                                        val buffer = ByteArray(8192)
                                        var bytesRead: Int
                                        while (input.read(buffer).also { bytesRead = it } != -1) {
                                            writeFully(buffer, 0, bytesRead)
                                        }
                                    }
                                }
                                Log.i(TAG, "File transfer completed: ${info.fileName}")
                            } catch (e: Exception) {
                                Log.e(TAG, "File transfer error", e)
                                call.respondText("""{"error":"transfer failed"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                            }
                        }
                        // WebSocket: real-time bidirectional clipboard sync
                        webSocket("/sync") {
                            val sessionId = UUID.randomUUID().toString()
                            connectedClients[sessionId] = this
                            Log.i(TAG, "Windows client connected: $sessionId")
                            updateNotification("Windows connected ✓")

                            try {
                                for (frame in incoming) {
                                    if (frame is Frame.Text) {
                                        val text = frame.readText()
                                        try {
                                            val payload = gson.fromJson(text, ClipboardPayload::class.java)
                                            if (payload.clipboardId != null && payload.content != null && payload.source != null) {
                                                val inserted = clipboardRepo.addItem(
                                                    content = payload.content,
                                                    source = payload.source,
                                                    clipboardId = payload.clipboardId
                                                )
                                                if (inserted) {
                                    // Remember what we set so clipboard listener won't echo it back
                                    lastSetContent = payload.content
                                    withContext(Dispatchers.Main) {
                                                        val clip = ClipData.newPlainText("sync", payload.content)
                                                        clipboardManager.setPrimaryClip(clip)
                                                    }
                                                    updateNotification("Received: ${payload.content.take(30)}...")
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error parsing WebSocket message", e)
                                        }
                                    }
                                }
                            } finally {
                                connectedClients.remove(sessionId)
                                Log.i(TAG, "Windows client disconnected: $sessionId")
                                updateNotification("Windows disconnected")
                            }
                        }
                    }
                }.start(wait = false)
                Log.i(TAG, "Signaling server started on port $PORT")
                updateNotification("Server running on :$PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server", e)
            }
        }
    }

    // ─── Android Clipboard Listener ─────────────────────────────────────

    private fun startClipboardListener() {
        // Note: On Android 15+, clipboard change events may only fire when the app
        // has focus. The foreground service notification keeps us partially alive.
        clipboardManager.addPrimaryClipChangedListener {
            serviceScope.launch {
                try {
                    val clip = withContext(Dispatchers.Main) {
                        clipboardManager.primaryClip
                    }
                    val text = clip?.getItemAt(0)?.text?.toString() ?: return@launch

                    // Loop prevention: skip if this text is what we just set from a remote update
                    if (text == lastSetContent) {
                        lastSetContent = null // reset so next real change is captured
                        return@launch
                    }

                    val newId = UUID.randomUUID().toString()

                    val inserted = clipboardRepo.addItem(
                        content = text,
                        source = "android",
                        clipboardId = newId
                    )

                    if (inserted) {
                        // Push to all connected Windows clients via WebSocket
                        val payload = ClipboardPayload(
                            clipboardId = newId,
                            source = "android",
                            content = text
                        )
                        val json = gson.toJson(payload)
                        connectedClients.values.forEach { session ->
                            try {
                                session.send(Frame.Text(json))
                            } catch (e: Exception) {
                                Log.e(TAG, "Error sending to WebSocket client", e)
                            }
                        }
                        updateNotification("Sent: ${text.take(30)}...")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in clipboard listener", e)
                }
            }
        }
    }

    // ─── Clipboard Broadcaster (watches DB and pushes changes) ──────────

    private fun startClipboardBroadcaster() {
        serviceScope.launch {
            clipboardRepo.getAllItems().collectLatest { items ->
                // This Flow fires whenever the database changes.
                // The actual pushing happens in the clipboard listener and WebSocket handler,
                // but this allows the UI to always reflect the latest state.
                Log.d(TAG, "Clipboard journal updated: ${items.size} items")
            }
        }
    }

    // ─── Notification Helpers ───────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sync Agent Service",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Local Device Sync Agent background service"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Local Sync Agent")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val notification = createNotification(statusText)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}

/** Simple data class for JSON serialization of clipboard payloads */
data class ClipboardPayload(
    val clipboardId: String? = null,
    val source: String? = null,
    val content: String? = null
)
