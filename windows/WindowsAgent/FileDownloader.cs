using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Threading.Tasks;
using Newtonsoft.Json;

namespace WindowsAgent
{
    /// <summary>
    /// Polls the Android agent for available file transfers and downloads them
    /// to the user's Downloads folder.
    /// </summary>
    public class FileDownloader
    {
        // Endpoint is updated dynamically by NetworkMonitor after discovery
        private string _hotspotIp = "192.168.43.1";
        private int _signalingPort = 7777;
        private static readonly HttpClient _client = new HttpClient { Timeout = TimeSpan.FromMinutes(30) };

        /// <summary>
        /// Called by NetworkMonitor once the Android agent IP is discovered.
        /// </summary>
        public void UpdateEndpoint(string ip, int port)
        {
            _hotspotIp = ip;
            _signalingPort = port;
        }

        private readonly string _downloadPath;
        private readonly HashSet<string> _downloadedTokens = new();

        public event Action<string>? StatusChanged;

        public FileDownloader()
        {
            _downloadPath = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                "Downloads", "SyncDevice");
            Directory.CreateDirectory(_downloadPath);
        }

        /// <summary>
        /// Check for available transfers on the Android agent and download any new files.
        /// </summary>
        public async Task CheckAndDownloadAsync()
        {
            try
            {
                var response = await _client.GetStringAsync(
                    $"http://{_hotspotIp}:{_signalingPort}/transfers");

                var transfers = JsonConvert.DeserializeObject<List<TransferInfo>>(response);
                if (transfers == null || transfers.Count == 0) return;

                foreach (var transfer in transfers)
                {
                    if (_downloadedTokens.Contains(transfer.Token)) continue;

                    StatusChanged?.Invoke($"Downloading: {transfer.FileName}...");
                    await DownloadFileAsync(transfer);
                    _downloadedTokens.Add(transfer.Token);
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"FileDownloader: Check failed: {ex.Message}");
            }
        }

        private async Task DownloadFileAsync(TransferInfo info)
        {
            try
            {
                var filePath = Path.Combine(_downloadPath, info.FileName);

                // Handle name collisions
                var counter = 1;
                var baseName = Path.GetFileNameWithoutExtension(info.FileName);
                var extension = Path.GetExtension(info.FileName);
                while (File.Exists(filePath))
                {
                    filePath = Path.Combine(_downloadPath, $"{baseName} ({counter}){extension}");
                    counter++;
                }

                using var response = await _client.GetAsync(
                    $"http://{_hotspotIp}:{_signalingPort}/transfer/{info.Token}",
                    HttpCompletionOption.ResponseHeadersRead);

                response.EnsureSuccessStatusCode();

                using var stream = await response.Content.ReadAsStreamAsync();
                using var fileStream = new FileStream(filePath, FileMode.Create, FileAccess.Write, FileShare.None, 8192, true);

                var buffer = new byte[8192];
                long totalRead = 0;
                int bytesRead;

                while ((bytesRead = await stream.ReadAsync(buffer, 0, buffer.Length)) > 0)
                {
                    await fileStream.WriteAsync(buffer, 0, bytesRead);
                    totalRead += bytesRead;

                    if (info.Size > 0)
                    {
                        var percent = (int)((totalRead * 100) / info.Size);
                        StatusChanged?.Invoke($"Downloading {info.FileName}: {percent}%");
                    }
                }

                StatusChanged?.Invoke($"Downloaded: {info.FileName} → {filePath}");
                Debug.WriteLine($"FileDownloader: Saved {info.FileName} to {filePath}");
            }
            catch (Exception ex)
            {
                StatusChanged?.Invoke($"Download failed: {info.FileName}");
                Debug.WriteLine($"FileDownloader: Download error: {ex}");
            }
        }
    }

    public class TransferInfo
    {
        [JsonProperty("token")]
        public string Token { get; set; } = "";

        [JsonProperty("fileName")]
        public string FileName { get; set; } = "";

        [JsonProperty("mimeType")]
        public string MimeType { get; set; } = "";

        [JsonProperty("size")]
        public long Size { get; set; }
    }
}
