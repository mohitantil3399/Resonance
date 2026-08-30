using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Media;
using System.Windows;
using Microsoft.Toolkit.Uwp.Notifications;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace WindowsAgent
{
    /// <summary>
    /// Receives notification messages from Android and displays them as
    /// native Windows toast notifications.
    ///
    /// Incoming calls are rendered with a ringing sound and Answer/Decline buttons.
    /// Notification actions (Answer, Decline, Reply) are sent back to Android
    /// via the ClipboardSyncEngine WebSocket.
    /// </summary>
    public class NotificationEngine
    {
        private readonly ClipboardSyncEngine _syncEngine;

        // Track active notifications so we can dismiss them
        private readonly ConcurrentDictionary<string, string> _activeNotifications = new();

        // In-memory log for UI display (newest first, max 50)
        private readonly ConcurrentQueue<NotificationDisplayItem> _displayLog = new();
        private const int MaxDisplayItems = 50;

        public event Action? NotificationLogUpdated;

        public NotificationEngine(ClipboardSyncEngine syncEngine)
        {
            _syncEngine = syncEngine;

            // Register toast activation handler
            ToastNotificationManagerCompat.OnActivated += toastArgs =>
            {
                var args = ToastArguments.Parse(toastArgs.Argument);

                if (args.Contains("action") && args["action"] == "notification_action")
                {
                    var notifKey = args.Contains("notifKey") ? args["notifKey"] : "";
                    var actionIndex = args.Contains("actionIndex") ? args["actionIndex"] : "0";

                    Debug.WriteLine($"NotificationEngine: Action clicked — key={notifKey}, index={actionIndex}");

                    // Send notification_action back to Android
                    var payload = JsonConvert.SerializeObject(new
                    {
                        type = "notification_action",
                        key = notifKey,
                        actionIndex = int.TryParse(actionIndex, out var idx) ? idx : 0
                    });

                    // Fire-and-forget send back to Android
                    _ = _syncEngine.SendEncryptedAsync(payload);
                }
            };
        }

        /// <summary>
        /// Get the current display log for the UI.
        /// </summary>
        public NotificationDisplayItem[] GetDisplayLog()
        {
            return _displayLog.ToArray();
        }

        /// <summary>
        /// Handle an incoming notification JSON from Android.
        /// </summary>
        public void HandleNotification(string json)
        {
            try
            {
                var obj = JObject.Parse(json);

                var key = obj["key"]?.ToString() ?? "";
                var packageName = obj["packageName"]?.ToString() ?? "";
                var appName = obj["appName"]?.ToString() ?? packageName;
                var title = obj["title"]?.ToString() ?? "";
                var text = obj["text"]?.ToString() ?? "";
                var isCall = obj["isCall"]?.ToObject<bool>() ?? false;
                var category = obj["category"]?.ToString() ?? "";
                var actionsArr = obj["actions"] as JArray;

                // Store as active
                _activeNotifications[key] = json;

                // Add to display log
                var displayItem = new NotificationDisplayItem
                {
                    Key = key,
                    AppName = appName,
                    Title = title,
                    Text = text,
                    IsCall = isCall,
                    Time = DateTime.Now.ToString("HH:mm:ss"),
                    PackageName = packageName
                };

                _displayLog.Enqueue(displayItem);
                while (_displayLog.Count > MaxDisplayItems)
                    _displayLog.TryDequeue(out _);

                NotificationLogUpdated?.Invoke();

                // Build toast notification
                if (isCall)
                {
                    ShowCallToast(key, appName, title, text, actionsArr);
                }
                else
                {
                    ShowStandardToast(key, appName, title, text, actionsArr);
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"NotificationEngine: Error handling notification: {ex}");
            }
        }

        /// <summary>
        /// Handle a notification dismissal from Android.
        /// </summary>
        public void HandleDismissal(string json)
        {
            try
            {
                var obj = JObject.Parse(json);
                var key = obj["key"]?.ToString() ?? "";

                _activeNotifications.TryRemove(key, out _);

                // Remove the toast (best-effort)
                try
                {
                    ToastNotificationManagerCompat.History.Remove(key);
                }
                catch { }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"NotificationEngine: Error handling dismissal: {ex}");
            }
        }

        // ─── Toast Builders ─────────────────────────────────────────────

        private void ShowStandardToast(string key, string appName, string title, string text, JArray? actions)
        {
            var builder = new ToastContentBuilder()
                .AddArgument("notifKey", key)
                .AddText($"📱 {appName}")
                .AddText(string.IsNullOrEmpty(title) ? text : title);

            // Add body text if different from title
            if (!string.IsNullOrEmpty(title) && !string.IsNullOrEmpty(text) && text != title)
            {
                builder.AddText(text);
            }

            // Add action buttons (max 3 per toast)
            if (actions != null)
            {
                var count = 0;
                foreach (var action in actions)
                {
                    if (count >= 3) break;
                    var label = action["label"]?.ToString() ?? $"Action {count}";
                    var index = action["index"]?.ToObject<int>() ?? count;

                    builder.AddButton(new ToastButton()
                        .SetContent(label)
                        .AddArgument("action", "notification_action")
                        .AddArgument("notifKey", key)
                        .AddArgument("actionIndex", index.ToString()));
                    count++;
                }
            }

            builder.Show(toast =>
            {
                toast.Tag = key;
                toast.Group = "devicesync";
            });
        }

        private void ShowCallToast(string key, string appName, string callerName, string text, JArray? actions)
        {
            var builder = new ToastContentBuilder()
                .AddArgument("notifKey", key)
                .AddText($"📞 Incoming Call")
                .AddText(string.IsNullOrEmpty(callerName) ? "Unknown Caller" : callerName);

            if (!string.IsNullOrEmpty(text) && text != callerName)
            {
                builder.AddText(text);
            }

            // Add Answer/Decline from the actual notification actions
            if (actions != null)
            {
                var count = 0;
                foreach (var action in actions)
                {
                    if (count >= 3) break;
                    var label = action["label"]?.ToString() ?? $"Action {count}";
                    var index = action["index"]?.ToObject<int>() ?? count;

                    builder.AddButton(new ToastButton()
                        .SetContent(label)
                        .AddArgument("action", "notification_action")
                        .AddArgument("notifKey", key)
                        .AddArgument("actionIndex", index.ToString()));
                    count++;
                }
            }
            else
            {
                // Fallback generic Answer/Decline if no actions provided
                builder.AddButton(new ToastButton()
                    .SetContent("✓ Answer")
                    .AddArgument("action", "notification_action")
                    .AddArgument("notifKey", key)
                    .AddArgument("actionIndex", "0"));

                builder.AddButton(new ToastButton()
                    .SetContent("✕ Decline")
                    .AddArgument("action", "notification_action")
                    .AddArgument("notifKey", key)
                    .AddArgument("actionIndex", "1"));
            }

            // Play ringtone sound for calls
            builder.AddAudio(new ToastAudio()
            {
                Src = new Uri("ms-winsoundevent:Notification.Looping.Call"),
                Loop = true
            });

            builder.Show(toast =>
            {
                toast.Tag = key;
                toast.Group = "devicesync_calls";
                toast.ExpiresOnReboot = true;
            });
        }



        /// <summary>
        /// Clean up toast notification resources.
        /// </summary>
        public void Cleanup()
        {
            try
            {
                ToastNotificationManagerCompat.Uninstall();
            }
            catch { }
        }
    }

    /// <summary>
    /// Display model for the Notifications tab in the UI.
    /// </summary>
    public class NotificationDisplayItem
    {
        public string Key { get; set; } = "";
        public string AppName { get; set; } = "";
        public string Title { get; set; } = "";
        public string Text { get; set; } = "";
        public bool IsCall { get; set; }
        public string Time { get; set; } = "";
        public string PackageName { get; set; } = "";
    }
}
