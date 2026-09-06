using System;
using System.Diagnostics;
using System.Net.Http;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

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
        private readonly SessionCrypto _crypto = new();
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
        public event Action? FilesAvailableReceived;

        /// <summary>
        /// Fired when a notification message is received from Android.
        /// The string payload is the raw JSON of the notification.
        /// </summary>
        public event Action<string>? NotificationReceived;

        /// <summary>
        /// Fired when a notification dismissal is received from Android.
        /// </summary>
        public event Action<string>? NotificationDismissed;

        /// <summary>
        /// Fired when a WebRTC signaling message is received (offer/answer/ice).
        /// </summary>
        public event Action<string, string>? WebRtcSignalingReceived; // (type, json)

        /// <summary>
        /// Fired when a screen share lifecycle message is received.
        /// </summary>
        public event Action<string>? ScreenShareLifecycleReceived;

        /// <summary>
        /// Whether the ECDHE key exchange has completed for this session.
        /// </summary>
        public bool IsEncrypted => _crypto.IsEstablished;

        /// <summary>
        /// Bearer token for authenticating HTTP REST endpoints, negotiated during key exchange.
        /// </summary>
        public string? SessionToken { get; private set; }

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

                // Initiate ECDHE key exchange
                await InitiateKeyExchangeAsync();

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
            _crypto.Reset();
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
                    type = "clipboard",
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
        }


        /// <summary>
        /// Continuously receive messages from the Android agent.
        /// Handles multi-frame WebSocket messages by accumulating bytes until EndOfMessage.
        /// </summary>
        private async Task ReceiveLoopAsync(CancellationToken ct)
        {
            var buffer = new byte[8192];

            try
            {
                while (!ct.IsCancellationRequested && _webSocket?.State == WebSocketState.Open)
                {
                    // Accumulate frames until we get a complete message
                    var messageBuilder = new System.IO.MemoryStream();
                    WebSocketReceiveResult result;

                    do
                    {
                        result = await _webSocket.ReceiveAsync(
                            new ArraySegment<byte>(buffer), ct);

                        if (result.MessageType == WebSocketMessageType.Close)
                        {
                            StatusChanged?.Invoke("Android disconnected");
                            return;
                        }

                        messageBuilder.Write(buffer, 0, result.Count);
                    }
                    while (!result.EndOfMessage);

                    if (result.MessageType == WebSocketMessageType.Text)
                    {
                        var json = Encoding.UTF8.GetString(
                            messageBuilder.GetBuffer(), 0, (int)messageBuilder.Length);
                        Debug.WriteLine($"WebSocket received: {json[..Math.Min(json.Length, 100)]}");
                        RouteIncomingMessage(json);
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

        // ─── ECDHE Key Exchange ──────────────────────────────────────────

        /// <summary>
        /// Initiate ECDHE key exchange right after WebSocket connect.
        /// Windows is always the initiator (sends pubKey first).
        /// </summary>
        private async Task InitiateKeyExchangeAsync()
        {
            try
            {
                _crypto.GenerateKeyPair();
                var payload = new { 
                    type = "key_exchange", 
                    pubKey = _crypto.GetPublicKeyBase64(),
                    deviceId = App.StorageSettings.DeviceId,
                    deviceName = Environment.MachineName
                };
                var json = JsonConvert.SerializeObject(payload);
                var bytes = Encoding.UTF8.GetBytes(json);

                await _webSocket!.SendAsync(
                    new ArraySegment<byte>(bytes),
                    WebSocketMessageType.Text,
                    true,
                    _wsCancellation?.Token ?? CancellationToken.None);

                StatusChanged?.Invoke("🔑 Key exchange initiated...");
                Debug.WriteLine("SessionCrypto: Key exchange message sent");
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Key exchange initiation failed: {ex}");
            }
        }

        // ─── Typed Message Router ────────────────────────────────────────

        /// <summary>
        /// Routes an incoming WebSocket message by its "type" field.
        /// Messages without a type are treated as legacy clipboard payloads.
        /// </summary>
        private void RouteIncomingMessage(string json)
        {
            try
            {
                JObject? obj;
                try
                {
                    obj = JObject.Parse(json);
                }
                catch
                {
                    Debug.WriteLine($"Non-JSON WebSocket message: {json[..Math.Min(json.Length, 50)]}");
                    return;
                }

                var type = obj["type"]?.ToString();

                switch (type)
                {
                    // ── ECDHE Key Exchange response ──
                    case "key_exchange":
                        var remotePubKey = obj["pubKey"]?.ToString();
                        var token = obj["sessionToken"]?.ToString();
                        if (remotePubKey != null)
                        {
                            _crypto.DeriveSharedSecret(remotePubKey);
                            SessionToken = token;
                            StatusChanged?.Invoke("🔒 Encrypted session established");
                            Debug.WriteLine($"SessionCrypto: Shared secret derived, token={(token != null ? "received" : "missing")}");
                        }
                        break;

                    // ── Encrypted envelope — decrypt and re-route ──
                    case "encrypted":
                        var encPayload = obj["payload"]?.ToString();
                        if (encPayload != null && _crypto.IsEstablished)
                        {
                            var decrypted = _crypto.Decrypt(encPayload);
                            RouteIncomingMessage(decrypted);
                        }
                        else
                        {
                            Debug.WriteLine("Received encrypted message but no key exchange done");
                        }
                        break;

                    // ── Clipboard (typed or untyped legacy) ──
                    case "clipboard":
                    case null:
                        if (obj["content"] != null || obj["clipboardId"] != null)
                        {
                            HandleClipboardPayload(obj);
                        }
                        else
                        {
                            Debug.WriteLine($"Unknown message type: {type}");
                        }
                        break;

                    // ── File transfer notification ──
                    case "files_available":
                        FilesAvailableReceived?.Invoke();
                        break;

                    // ── Notification mirroring (Phase 3b) ──
                    case "notification":
                        NotificationReceived?.Invoke(json);
                        break;

                    // ── Notification dismissed (Phase 3b) ──
                    case "notification_dismissed":
                        NotificationDismissed?.Invoke(json);
                        break;

                    // ── WebRTC signaling (Phase 3d) ──
                    case "webrtc_offer":
                    case "webrtc_answer":
                    case "webrtc_ice":
                        WebRtcSignalingReceived?.Invoke(type, json);
                        break;

                    // ── Screen share lifecycle (Phase 3d) ──
                    case "screen_share_start":
                    case "screen_share_stop":
                        ScreenShareLifecycleReceived?.Invoke(type);
                        break;

                    default:
                        Debug.WriteLine($"Unknown message type: {type}");
                        break;
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Error routing incoming message: {ex}");
            }
        }

        /// <summary>
        /// Handle a clipboard message (typed or untyped legacy).
        /// </summary>
        private void HandleClipboardPayload(JObject obj)
        {
            var content = obj["content"]?.ToString();
            if (content == null) return;

            var clipboardId = obj["clipboardId"]?.ToString() ?? Guid.NewGuid().ToString();
            var source = obj["source"]?.ToString() ?? "android";

            if (source == "windows") return;
            if (_db.ExistsById(clipboardId)) return;

            var timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            _db.InsertItem(clipboardId, source, content, timestamp);

            _lastSetClipboardId = clipboardId;
            _lastClipboardContent = content;

            Application.Current?.Dispatcher.Invoke(() =>
            {
                SetClipboardWithRetry(content);
                ClipboardReceived?.Invoke(content);
                StatusChanged?.Invoke($"Received: {content[..Math.Min(content.Length, 30)]}...");
            });
        }



        /// <summary>
        /// Sets clipboard text with retry logic. The Windows clipboard can throw
        /// COMException when another app has it locked (e.g., password managers, RDP).
        /// </summary>
        private void SetClipboardWithRetry(string text, int maxRetries = 3)
        {
            for (int i = 0; i < maxRetries; i++)
            {
                try
                {
                    if (string.IsNullOrEmpty(text))
                    {
                        Clipboard.Clear();
                    }
                    else
                    {
                        Clipboard.SetText(text);
                    }
                    return; // success
                }
                catch (Exception ex)
                {
                    Debug.WriteLine($"Clipboard.SetText attempt {i + 1} failed: {ex.Message}");
                    if (i < maxRetries - 1)
                        System.Threading.Thread.Sleep(100); // brief pause before retry
                }
            }
        }

        /// <summary>
        /// True only when the WebSocket is actually open (not just flagged as connected).
        /// This prevents the NetworkMonitor from skipping probes on a stale/dead socket.
        /// </summary>
        public bool IsConnected => _webSocket?.State == WebSocketState.Open;

        /// <summary>
        /// Send a raw JSON payload to the Android agent via WebSocket.
        /// Used by NotificationEngine, ScreenShareEngine, etc.
        /// </summary>
        public async Task SendRawAsync(string json)
        {
            if (_webSocket?.State != WebSocketState.Open) return;

            var bytes = Encoding.UTF8.GetBytes(json);
            try
            {
                await _webSocket.SendAsync(
                    new ArraySegment<byte>(bytes),
                    WebSocketMessageType.Text,
                    true,
                    _wsCancellation?.Token ?? CancellationToken.None);
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"SendRawAsync error: {ex}");
            }
        }

        /// <summary>
        /// Send an encrypted JSON payload. If key exchange is not complete, sends unencrypted.
        /// </summary>
        public async Task SendEncryptedAsync(string json)
        {
            if (_crypto.IsEstablished)
            {
                var encrypted = _crypto.Encrypt(json);
                var envelope = JsonConvert.SerializeObject(new { type = "encrypted", payload = encrypted });
                await SendRawAsync(envelope);
            }
            else
            {
                await SendRawAsync(json);
            }
        }
    }

    /// <summary>
    /// JSON payload for clipboard sync messages (WebSocket).
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

