using System;
using System.Diagnostics;
using System.Net.Http;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using Newtonsoft.Json;

namespace WindowsAgent
{
    /// <summary>
    /// Manages the full clipboard sync lifecycle:
    /// 1. Monitors Windows clipboard changes
    /// 2. Pushes changes to Android via WebSocket
    /// 3. Receives Android changes via WebSocket and sets Windows clipboard
    /// 4. Handles loop prevention via clipboardId tracking
    /// </summary>
    public class ClipboardSyncEngine
    {
        // Endpoint is updated dynamically by NetworkMonitor after discovery
        private string _hotspotIp = "192.168.43.1";
        private int _signalingPort = 7777;

        private readonly ClipboardDatabase _db;
        private ClientWebSocket? _webSocket;
        private CancellationTokenSource? _wsCancellation;

        /// <summary>
        /// Called by NetworkMonitor once the Android agent IP is discovered.
        /// </summary>
        public void UpdateEndpoint(string ip, int port)
        {
            _hotspotIp = ip;
            _signalingPort = port;
        }

        // Loop prevention: the last clipboardId we set on the Windows clipboard
        private string? _lastSetClipboardId;

        // Track last clipboard content to avoid sending duplicates
        private string? _lastClipboardContent;

        public event Action<string>? StatusChanged;
        public event Action<string>? ClipboardReceived;

        public ClipboardSyncEngine(ClipboardDatabase db)
        {
            _db = db;
        }

        /// <summary>
        /// Attempt to connect to the Android agent via WebSocket.
        /// </summary>
        public async Task ConnectAsync()
        {
            // If already open, nothing to do
            if (_webSocket?.State == WebSocketState.Open) return;

            try
            {
                // Dispose any stale socket before creating a new one
                _wsCancellation?.Cancel();
                _webSocket?.Dispose();

                _wsCancellation = new CancellationTokenSource();
                _webSocket = new ClientWebSocket();

                var uri = new Uri($"ws://{_hotspotIp}:{_signalingPort}/sync");
                StatusChanged?.Invoke("Connecting to Android agent...");

                await _webSocket.ConnectAsync(uri, _wsCancellation.Token);
                StatusChanged?.Invoke("Connected ✓");

                // Start listening for incoming messages
                _ = Task.Run(() => ReceiveLoopAsync(_wsCancellation.Token));
            }
            catch (Exception ex)
            {
                StatusChanged?.Invoke($"Connection failed: {ex.Message}");
                Debug.WriteLine($"WebSocket connection failed: {ex}");
            }
        }

        /// <summary>
        /// Disconnect from the Android agent.
        /// </summary>
        public async Task DisconnectAsync()
        {
            _wsCancellation?.Cancel();

            if (_webSocket?.State == WebSocketState.Open)
            {
                try
                {
                    await _webSocket.CloseAsync(
                        WebSocketCloseStatus.NormalClosure,
                        "Client disconnecting",
                        CancellationToken.None);
                }
                catch { }
            }
            _webSocket?.Dispose();
            _webSocket = null;
            StatusChanged?.Invoke("Disconnected");
        }

        /// <summary>
        /// Called when Windows clipboard changes.
        /// Sends the new content to the Android agent.
        /// </summary>
        public async Task OnWindowsClipboardChangedAsync(string text)
        {
            // Skip if this is the same content we just set from a remote update
            if (text == _lastClipboardContent) return;

            _lastClipboardContent = text;
            var clipboardId = Guid.NewGuid().ToString();

            // Store locally
            var timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            _db.InsertItem(clipboardId, "windows", text, timestamp);

            // Send to Android via WebSocket
            if (_webSocket?.State == WebSocketState.Open)
            {
                var payload = new
                {
                    clipboardId,
                    source = "windows",
                    content = text
                };
                var json = JsonConvert.SerializeObject(payload);
                var bytes = Encoding.UTF8.GetBytes(json);

                try
                {
                    await _webSocket.SendAsync(
                        new ArraySegment<byte>(bytes),
                        WebSocketMessageType.Text,
                        true,
                        _wsCancellation?.Token ?? CancellationToken.None);

                    StatusChanged?.Invoke($"Sent: {text[..Math.Min(text.Length, 30)]}...");
                }
                catch (Exception ex)
                {
                    Debug.WriteLine($"Error sending clipboard: {ex}");
                    StatusChanged?.Invoke("Send failed, reconnecting...");
                }
            }
            else
            {
                // Fallback: push via REST if WebSocket is not connected
                await PushViaRestAsync(clipboardId, text);
            }
        }

        /// <summary>
        /// Fallback REST push when WebSocket is unavailable.
        /// </summary>
        private async Task PushViaRestAsync(string clipboardId, string text)
        {
            try
            {
                using var client = new HttpClient();
                client.Timeout = TimeSpan.FromSeconds(5);

                var payload = new
                {
                    clipboardId,
                    source = "windows",
                    content = text
                };
                var json = JsonConvert.SerializeObject(payload);
                var httpContent = new StringContent(json, Encoding.UTF8, "application/json");

                var response = await client.PostAsync(
                    $"http://{_hotspotIp}:{_signalingPort}/clipboard",
                    httpContent);

                if (response.IsSuccessStatusCode)
                {
                    StatusChanged?.Invoke($"Sent (REST): {text[..Math.Min(text.Length, 30)]}...");
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"REST fallback failed: {ex}");
            }
        }

        /// <summary>
        /// Continuously receive messages from the Android agent.
        /// </summary>
        private async Task ReceiveLoopAsync(CancellationToken ct)
        {
            var buffer = new byte[8192];

            try
            {
                while (!ct.IsCancellationRequested && _webSocket?.State == WebSocketState.Open)
                {
                    var result = await _webSocket.ReceiveAsync(
                        new ArraySegment<byte>(buffer), ct);

                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        StatusChanged?.Invoke("Android disconnected");
                        break;
                    }

                    if (result.MessageType == WebSocketMessageType.Text)
                    {
                        var json = Encoding.UTF8.GetString(buffer, 0, result.Count);
                        HandleIncomingClipboard(json);
                    }
                }
            }
            catch (OperationCanceledException)
            {
                // Expected on disconnect
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"WebSocket receive error: {ex}");
                StatusChanged?.Invoke("Connection lost, will retry...");
            }
        }

        /// <summary>
        /// Process an incoming clipboard payload from Android.
        /// </summary>
        private void HandleIncomingClipboard(string json)
        {
            try
            {
                var payload = JsonConvert.DeserializeObject<ClipboardPayload>(json);
                if (payload == null || payload.ClipboardId == null || payload.Content == null)
                    return;

                // Loop prevention: don't process our own items
                if (payload.Source == "windows") return;

                // Check if we already have this item
                if (_db.ExistsById(payload.ClipboardId)) return;

                // Store in local DB
                var timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                _db.InsertItem(payload.ClipboardId, payload.Source ?? "android", payload.Content, timestamp);

                // Update Windows clipboard (must happen on UI thread)
                _lastSetClipboardId = payload.ClipboardId;
                _lastClipboardContent = payload.Content;

                Application.Current?.Dispatcher.Invoke(() =>
                {
                    try
                    {
                        Clipboard.SetText(payload.Content);
                        ClipboardReceived?.Invoke(payload.Content);
                        StatusChanged?.Invoke($"Received: {payload.Content[..Math.Min(payload.Content.Length, 30)]}...");
                    }
                    catch (Exception ex)
                    {
                        Debug.WriteLine($"Error setting clipboard: {ex}");
                    }
                });
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Error parsing incoming clipboard: {ex}");
            }
        }

        /// <summary>
        /// True only when the WebSocket is actually open (not just flagged as connected).
        /// This prevents the NetworkMonitor from skipping probes on a stale/dead socket.
        /// </summary>
        public bool IsConnected => _webSocket?.State == WebSocketState.Open;
    }

    /// <summary>
    /// JSON payload for clipboard sync messages.
    /// </summary>
    public class ClipboardPayload
    {
        [JsonProperty("clipboardId")]
        public string? ClipboardId { get; set; }

        [JsonProperty("source")]
        public string? Source { get; set; }

        [JsonProperty("content")]
        public string? Content { get; set; }
    }
}
