using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace WindowsAgent
{
    /// <summary>
    /// Monitors network changes and dynamically discovers the Android agent
    /// on the hotspot subnet (any 192.168.x.x or 10.x.x.x network) by
    /// scanning port 7777 for the /hello endpoint.
    ///
    /// Fixes vs previous version:
    ///  - No hardcoded IP: scans the subnet gateway + common hotspot addresses
    ///  - Detects stale/dead WebSocket connections and reconnects
    ///  - Restarts file-check timer after reconnection
    ///  - Properly stops file-check timer when disconnected
    /// </summary>
    public class NetworkMonitor
    {
        private const int SignalingPort = 7777;

        // Common gateway addresses Android hotspot uses:
        //   - 192.168.43.1  (older Android / most devices)
        //   - 192.168.137.1 (Windows-hosted hotspot gateway seen on some Samsung)
        //   - 10.0.0.1 / 10.42.0.1 (some custom ROMs)
        private static readonly string[] HotspotGatewayCandidates =
        {
            "192.168.43.1",
            "192.168.1.1",
            "192.168.137.1",
            "10.42.0.1",
            "10.0.0.1",
        };

        private static readonly HttpClient _httpClient = new HttpClient
        {
            Timeout = TimeSpan.FromSeconds(3)
        };

        private readonly ClipboardSyncEngine _syncEngine;
        private readonly FileDownloader _fileDownloader;
        private readonly FileUploader _fileUploader;
        private Timer? _retryTimer;
        private Timer? _fileCheckTimer;
        private Timer? _clipboardPollTimer;
        private bool _probing = false;
        private string? _lastConnectedIp = null;

        public event Action<string>? StatusChanged;

        public NetworkMonitor(ClipboardSyncEngine syncEngine, FileDownloader fileDownloader, FileUploader fileUploader)
        {
            _syncEngine = syncEngine;
            _fileDownloader = fileDownloader;
            _fileUploader = fileUploader;
        }

        public void Start()
        {
            NetworkChange.NetworkAddressChanged += OnNetworkChanged;
            Debug.WriteLine("NetworkMonitor: Started — will scan for Android agent on port 7777.");
            StatusChanged?.Invoke("Scanning for Android agent...");

            // Initial check on startup
            _ = ProbeAndConnectAsync();

            // Retry every 8 seconds so we reconnect quickly after IP changes
            _retryTimer = new Timer(
                _ => _ = ProbeAndConnectAsync(),
                null,
                TimeSpan.FromSeconds(8),
                TimeSpan.FromSeconds(8));
        }

        public void Stop()
        {
            NetworkChange.NetworkAddressChanged -= OnNetworkChanged;
            _retryTimer?.Dispose();
            _fileCheckTimer?.Dispose();
            _clipboardPollTimer?.Dispose();
        }

        private void OnNetworkChanged(object? sender, EventArgs e)
        {
            Debug.WriteLine("NetworkMonitor: Network address changed — re-scanning.");
            // Reset last IP so we re-probe even if we think we know the address
            _lastConnectedIp = null;
            _ = ProbeAndConnectAsync();
        }

        private async Task ProbeAndConnectAsync()
        {
            if (_probing) return;

            // If already connected and WebSocket is healthy, nothing to do
            if (_syncEngine.IsConnected)
            {
                StatusChanged?.Invoke($"Connected ✓  ({_lastConnectedIp ?? "?"})");
                return;
            }

            _probing = true;

            // If WebSocket dropped, clean up timers
            _fileCheckTimer?.Dispose();
            _fileCheckTimer = null;
            _clipboardPollTimer?.Dispose();
            _clipboardPollTimer = null;

            try
            {
                StatusChanged?.Invoke("Scanning for Android agent...");

                // Build candidate list: previously working IP first, then known gateways,
                // then any gateway IP detected from active network adapters.
                var candidates = BuildCandidateList();

                foreach (var ip in candidates)
                {
                    var found = await TryConnectToAgent(ip);
                    if (found) return; // success — exit probe loop
                }

                StatusChanged?.Invoke("Waiting for hotspot...");
            }
            finally
            {
                _probing = false;
            }
        }

        /// <summary>
        /// Builds an ordered list of IP addresses to probe.
        /// </summary>
        private List<string> BuildCandidateList()
        {
            var candidates = new List<string>();

            // Try the last known good IP first
            if (_lastConnectedIp != null)
                candidates.Add(_lastConnectedIp);

            // Try well-known hotspot gateways
            candidates.AddRange(HotspotGatewayCandidates);

            // Also try gateway IPs detected from active adapters
            foreach (var iface in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (iface.OperationalStatus != OperationalStatus.Up) continue;
                if (iface.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

                var props = iface.GetIPProperties();
                foreach (var gw in props.GatewayAddresses)
                {
                    var gwIp = gw.Address.ToString();
                    if (!candidates.Contains(gwIp))
                        candidates.Add(gwIp);
                }
            }

            return candidates.Distinct().ToList();
        }

        /// <summary>
        /// Attempts to reach the Android agent at <paramref name="ip"/>:7777/hello.
        /// If successful, connects the sync engine and starts the file-check timer.
        /// Returns true if connection was established.
        /// </summary>
        private async Task<bool> TryConnectToAgent(string ip)
        {
            try
            {
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(3));
                var response = await _httpClient.GetAsync(
                    $"http://{ip}:{SignalingPort}/hello", cts.Token);

                if (!response.IsSuccessStatusCode) return false;

                var body = await response.Content.ReadAsStringAsync();
                Debug.WriteLine($"NetworkMonitor: Found Android agent at {ip} → {body}");
                StatusChanged?.Invoke($"Android agent found at {ip} — connecting...");

                // Update the sync engine and file transfer endpoints with the discovered IP
                _syncEngine.UpdateEndpoint(ip, SignalingPort);
                _fileDownloader.UpdateEndpoint(ip, SignalingPort);
                _fileUploader.UpdateEndpoint(ip, SignalingPort);
                _lastConnectedIp = ip;

                // Establish WebSocket
                await _syncEngine.ConnectAsync();

                if (_syncEngine.IsConnected)
                {
                    // Start polling for file transfers every 5 seconds
                    _fileCheckTimer?.Dispose();
                    _fileCheckTimer = new Timer(
                        async _ => await _fileDownloader.CheckAndDownloadAsync(),
                        null,
                        TimeSpan.FromSeconds(2),
                        TimeSpan.FromSeconds(5));

                    // Start polling for clipboard changes every 3 seconds
                    // This is the primary Android→Windows sync path because Android 10+
                    // restricts clipboard reading from background services.
                    _clipboardPollTimer?.Dispose();
                    _clipboardPollTimer = new Timer(
                        async _ => await _syncEngine.PollClipboardAsync(),
                        null,
                        TimeSpan.FromSeconds(2),
                        TimeSpan.FromSeconds(3));

                    StatusChanged?.Invoke($"Connected ✓  ({ip})");
                    return true;
                }
            }
            catch (OperationCanceledException)
            {
                // Timeout — this IP didn't respond, try the next one
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"NetworkMonitor: Error probing {ip}: {ex.Message}");
            }

            return false;
        }
    }
}
