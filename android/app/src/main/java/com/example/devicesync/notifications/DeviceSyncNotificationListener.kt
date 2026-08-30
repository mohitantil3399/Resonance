package com.example.devicesync.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
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
 * Calls are detected by checking notification category == CATEGORY_CALL,
 * allowing the Windows side to show Answer/Decline action buttons.
 */
class DeviceSyncNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifListener"
        private val gson = Gson()

        // Our own package — never mirror our own notifications
        private const val SELF_PACKAGE = "com.example.devicesync"

        // Default blocklist of packages we never mirror
        private val DEFAULT_BLOCKLIST = setOf(
            SELF_PACKAGE,
            "android",
            "com.android.systemui",
            "com.android.providers.downloads"
        )

        // Shared blocklist (can be updated from UI in future)
        @Volatile
        var userBlocklist: Set<String> = emptySet()

        /**
         * Check whether notification listener is enabled for this app.
         */
        fun isEnabled(context: android.content.Context): Boolean {
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
        fun requestAccess(context: android.content.Context) {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }

        /** Mirroring toggle (controlled from UI) */
        @Volatile
        var mirroringEnabled: Boolean = true

        private var instance: DeviceSyncNotificationListener? = null

        fun executeAction(key: String, actionIndex: Int): Boolean {
            val listener = instance ?: return false
            try {
                val activeNotifs = listener.activeNotifications ?: return false
                val sbn = activeNotifs.firstOrNull { it.key == key } ?: return false
                val actions = sbn.notification.actions ?: return false
                if (actionIndex in actions.indices) {
                    actions[actionIndex].actionIntent.send()
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute notification action", e)
            }
            return false
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !mirroringEnabled) return

        val pkg = sbn.packageName ?: return

        // Filter blocked packages
        if (pkg in DEFAULT_BLOCKLIST || pkg in userBlocklist) return

        // Skip low-priority ongoing notifications (media controls, etc.)
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0 &&
            notification.category != Notification.CATEGORY_CALL
        ) {
            return
        }

        try {
            val extras = notification.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

            // Skip empty notifications
            if (title.isBlank() && text.isBlank()) return

            // Detect calls
            val isCall = notification.category == Notification.CATEGORY_CALL

            // Extract action labels (for Answer/Decline, Reply, etc.)
            val actions = notification.actions?.mapIndexed { index, action ->
                mapOf(
                    "index" to index,
                    "label" to (action.title?.toString() ?: "Action $index")
                )
            } ?: emptyList()

            // Extract small app icon as Base64 PNG (small, ~1-2 KB)
            val iconBase64 = extractIconBase64(notification.smallIcon, pkg)

            // Build the payload
            val payload = mapOf(
                "type" to "notification",
                "key" to sbn.key,
                "packageName" to pkg,
                "appName" to getAppName(pkg),
                "title" to title,
                "text" to (bigText ?: text),
                "subText" to (subText ?: ""),
                "isCall" to isCall,
                "category" to (notification.category ?: ""),
                "actions" to actions,
                "icon" to (iconBase64 ?: ""),
                "timestamp" to sbn.postTime
            )

            val json = gson.toJson(payload)

            serviceScope.launch {
                SyncForegroundService.sendEncryptedToAllClients(json)
                Log.d(TAG, "Mirrored notification: $pkg — $title")
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

    /**
     * Called when the notification listener is connected.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification listener connected")
    }

    /**
     * Called when the notification listener is disconnected.
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.w(TAG, "Notification listener disconnected — requesting rebind")
        requestRebind(ComponentName(this, DeviceSyncNotificationListener::class.java))
    }

    // ── Helpers ──

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    /**
     * Extract a notification's small icon as a tiny Base64 PNG.
     * Returns null if extraction fails.
     */
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
