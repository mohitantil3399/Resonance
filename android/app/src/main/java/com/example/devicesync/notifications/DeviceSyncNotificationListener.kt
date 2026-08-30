package com.example.devicesync.notifications

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import com.example.devicesync.SyncForegroundService
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Mirrors Android notifications to connected Windows clients via WebSocket.
 *
 * This service requires the user to grant "Notification Access" in Settings.
 * Once granted, it receives all notifications posted on the device and
 * forwards relevant ones to the Windows agent.
 *
 * Enhanced with:
 * - Full Notification.MessagingStyle extraction for WhatsApp, Telegram, Google Messages.
 * - Accurate active incoming call vs missed call classification.
 * - RemoteInput support for inline quick replies from Windows toasts.
 */
class DeviceSyncNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifListener"
        private val gson = Gson()

        // Our own package — never mirror our own notifications
        private const val SELF_PACKAGE = "com.example.devicesync"

        // Default blocklist of packages that produce noise
        private val DEFAULT_BLOCKLIST = setOf(
            SELF_PACKAGE,
            "com.android.providers.downloads"
        )

        // Shared blocklist (can be updated from UI)
        @Volatile
        var userBlocklist: Set<String> = emptySet()

        /**
         * Check whether notification listener is enabled for this app.
         */
        fun isEnabled(context: Context): Boolean {
            val cn = ComponentName(context, DeviceSyncNotificationListener::class.java)
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return flat.contains(cn.flattenToString())
        }

        /**
         * Launch system settings to grant notification access.
         */
        fun requestAccess(context: Context) {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }

        /** Mirroring toggle (controlled from UI) */
        @Volatile
        var mirroringEnabled: Boolean = true

        private var instance: DeviceSyncNotificationListener? = null

        /**
         * Executes a notification action by index, optionally passing inline reply text via RemoteInput.
         */
        fun executeAction(key: String, actionIndex: Int, replyText: String? = null): Boolean {
            val listener = instance ?: return false
            try {
                val activeNotifs = listener.activeNotifications ?: return false
                val sbn = activeNotifs.firstOrNull { it.key == key } ?: return false
                val actions = sbn.notification.actions ?: return false
                if (actionIndex in actions.indices) {
                    val action = actions[actionIndex]
                    val remoteInputs = action.remoteInputs

                    if (!remoteInputs.isNullOrEmpty() && !replyText.isNullOrBlank()) {
                        val intent = Intent()
                        val bundle = Bundle()
                        for (ri in remoteInputs) {
                            bundle.putCharSequence(ri.resultKey, replyText)
                        }
                        RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
                        action.actionIntent.send(listener, 0, intent)
                        Log.i(TAG, "Sent inline reply text via RemoteInput to $key: $replyText")
                    } else {
                        action.actionIntent.send()
                        Log.i(TAG, "Triggered action intent index $actionIndex for $key")
                    }
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute notification action for $key", e)
            }
            return false
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !mirroringEnabled) return

        val pkg = sbn.packageName ?: return

        // Filter self and user-configured blocked packages
        if (pkg in DEFAULT_BLOCKLIST || pkg in userBlocklist) return

        val notification = sbn.notification ?: return

        try {
            // Extract actions
            val actionObjects = extractActions(notification)

            // Extract rich content (handles MessagingStyle for WhatsApp, Telegram, etc.)
            val (title, text, subText) = extractContent(notification, pkg)

            // Detect call state
            val category = notification.category ?: ""
            val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
            val isInsistent = (notification.flags and Notification.FLAG_INSISTENT) != 0

            val isCallCategory = category == Notification.CATEGORY_CALL ||
                    category.equals("call", ignoreCase = true) ||
                    pkg.contains("dialer", ignoreCase = true) ||
                    pkg.contains("incall", ignoreCase = true) ||
                    pkg.contains("telecom", ignoreCase = true) ||
                    pkg.contains("phone", ignoreCase = true)

            val hasAnswerAction = actionObjects.any { it.isAnswer }
            val hasDeclineAction = actionObjects.any { it.isDecline }
            val hasCallBackAction = actionObjects.any {
                it.label.contains("call back", ignoreCase = true) ||
                it.label.contains("callback", ignoreCase = true)
            }

            // Missed call: ended/missed without answer option
            val isMissedCall = category == Notification.CATEGORY_MISSED_CALL ||
                    category.equals("missed_call", ignoreCase = true) ||
                    title.contains("missed call", ignoreCase = true) ||
                    text.contains("missed call", ignoreCase = true) ||
                    (hasCallBackAction && !hasAnswerAction)

            // Incoming call: active ringing call with Answer/Decline or CallStyle incoming
            val isIncomingCall = !isMissedCall && (
                (isCallCategory && (isOngoing || isInsistent || hasAnswerAction)) ||
                (hasAnswerAction && (hasDeclineAction || isOngoing))
            )

            // Filter out low-priority background ongoing events (e.g. music players, persistent services)
            // But KEEP active incoming calls
            if (isOngoing && !isIncomingCall) {
                return
            }

            // Skip completely empty notifications
            if (title.isBlank() && text.isBlank()) {
                return
            }

            // Small app icon as Base64 PNG
            val iconBase64 = extractIconBase64(notification.smallIcon, pkg)

            // Format actions for JSON serialization
            val actionsJson = actionObjects.map {
                mapOf(
                    "index" to it.index,
                    "label" to it.label,
                    "isReply" to it.isReply,
                    "isAnswer" to it.isAnswer,
                    "isDecline" to it.isDecline
                )
            }

            // Build payload
            val payload = mapOf(
                "type" to "notification",
                "key" to sbn.key,
                "packageName" to pkg,
                "appName" to getAppName(pkg),
                "title" to title,
                "text" to text,
                "subText" to subText,
                "isCall" to isIncomingCall,
                "isIncomingCall" to isIncomingCall,
                "isMissedCall" to isMissedCall,
                "category" to category,
                "actions" to actionsJson,
                "icon" to (iconBase64 ?: ""),
                "timestamp" to sbn.postTime
            )

            val json = gson.toJson(payload)

            serviceScope.launch {
                SyncForegroundService.sendEncryptedToAllClients(json)
                Log.d(TAG, "Mirrored notification: $pkg — $title: $text (isIncomingCall=$isIncomingCall, isMissedCall=$isMissedCall)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification from $pkg", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || !mirroringEnabled) return

        val pkg = sbn.packageName ?: return
        if (pkg in DEFAULT_BLOCKLIST || pkg in userBlocklist) return

        try {
            val payload = mapOf(
                "type" to "notification_dismissed",
                "key" to sbn.key,
                "packageName" to pkg
            )
            val json = gson.toJson(payload)

            serviceScope.launch {
                SyncForegroundService.sendToAllClients(json)
                Log.d(TAG, "Notification dismissed: ${sbn.key}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending notification dismissal", e)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.w(TAG, "Notification listener disconnected — requesting rebind")
        requestRebind(ComponentName(this, DeviceSyncNotificationListener::class.java))
    }

    // ── Content & Action Extraction ─────────────────────────────────────

    private fun extractContent(notification: Notification, pkg: String): Triple<String, String, String> {
        val extras = notification.extras
        var title: String? = null
        var text: String? = null
        var subText: String? = null

        if (extras != null) {
            // 1. Title extraction
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            if (title.isNullOrBlank()) {
                title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            }
            if (title.isNullOrBlank()) {
                title = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            }
            if (title.isNullOrBlank()) {
                val person = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    extras.getParcelable<android.app.Person>(Notification.EXTRA_CALL_PERSON)
                        ?: extras.getParcelable<android.app.Person>(Notification.EXTRA_MESSAGING_PERSON)
                } else null
                title = person?.name?.toString()
            }

            // 2. Direct text / BigText
            text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            if (text.isNullOrBlank()) {
                text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            }

            // 3. MessagingStyle extraction (WhatsApp, Telegram, Signal, Google Messages)
            if (text.isNullOrBlank()) {
                val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                if (!messages.isNullOrEmpty()) {
                    val lastMsg = messages.lastOrNull()
                    if (lastMsg is Bundle) {
                        val msgText = lastMsg.getCharSequence("text")?.toString() ?: ""
                        val sender = lastMsg.getCharSequence("sender")?.toString()
                        if (title.isNullOrBlank() && !sender.isNullOrBlank()) {
                            title = sender
                        }
                        text = if (!sender.isNullOrBlank() && title != sender && !sender.equals(title, ignoreCase = true)) {
                            "$sender: $msgText"
                        } else {
                            msgText
                        }
                    }
                }
            }

            // 4. InboxStyle extraction (lines of messages / summary)
            if (text.isNullOrBlank()) {
                val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                if (!textLines.isNullOrEmpty()) {
                    text = textLines.filterNotNull().joinToString("\n") { it.toString() }
                }
            }

            // 5. Summary / SubText / InfoText
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            if (text.isNullOrBlank()) {
                text = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
            }
            if (text.isNullOrBlank()) {
                text = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
            }
        }

        // 6. TickerText fallback
        if (text.isNullOrBlank() && notification.tickerText != null) {
            val ticker = notification.tickerText.toString()
            if (title.isNullOrBlank()) {
                title = getAppName(pkg)
                text = ticker
            } else {
                text = ticker
            }
        }

        if (title.isNullOrBlank()) {
            title = getAppName(pkg)
        }

        return Triple(title ?: "", text ?: "", subText ?: "")
    }

    private data class ActionInfo(
        val index: Int,
        val label: String,
        val isReply: Boolean,
        val isAnswer: Boolean,
        val isDecline: Boolean
    )

    private fun extractActions(notification: Notification): List<ActionInfo> {
        val actions = notification.actions ?: return emptyList()
        return actions.mapIndexed { index, action ->
            val label = action.title?.toString() ?: "Action $index"
            val lower = label.lowercase()

            val isReply = (action.remoteInputs != null && action.remoteInputs.isNotEmpty()) ||
                    lower.contains("reply") || lower.contains("respond")
            val isAnswer = lower.contains("answer") || lower.contains("accept") || lower.contains("receive")
            val isDecline = lower.contains("decline") || lower.contains("reject") || lower.contains("dismiss") ||
                    lower.contains("hang up") || lower.contains("end")

            ActionInfo(
                index = index,
                label = label,
                isReply = isReply,
                isAnswer = isAnswer,
                isDecline = isDecline
            )
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    private fun extractIconBase64(icon: Icon?, packageName: String): String? {
        return try {
            val drawable: Drawable = icon?.loadDrawable(this) ?: return null
            val size = 48 // 48x48 px — small enough to serialize
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 80, stream)
            bitmap.recycle()

            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }
}

