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
    /// Downloads available file transfers from the Android agent to the customizable download folder.
    /// </summary>
    public class FileDownloader
    {
        private string _hotspotIp = "192.168.43.1";
        private int _signalingPort = 7777;
        private readonly StorageSettings _storageSettings;
        private readonly ClipboardDatabase _db;
        private static readonly HttpClient _client = new HttpClient { Timeout = TimeSpan.FromHours(2) };
        private readonly HashSet<string> _downloadedTokens = new();

        public event Action<string>? StatusChanged;
        public event Action<string, int, long, long>? DownloadProgressChanged;
        public event Action<TransferEntry>? FileDownloaded;

        public FileDownloader(StorageSettings storageSettings, ClipboardDatabase db)
        {
            _storageSettings = storageSettings;
            _db = db;
        }

        public void UpdateEndpoint(string ip, int port)
        {
            _hotspotIp = ip;
            _signalingPort = port;
        }

        /// <summary>
        /// Check for available transfers on the Android agent and download any new files.
        /// </summary>
        public async Task CheckAndDownloadAsync()
        {
            try
            {
                using var request = new HttpRequestMessage(HttpMethod.Get, $"http://{_hotspotIp}:{_signalingPort}/transfers");
                if (App.SyncEngine.SessionToken != null)
                {
                    request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", App.SyncEngine.SessionToken);
                }

                using var response = await _client.SendAsync(request);
                if (!response.IsSuccessStatusCode) return;

                var json = await response.Content.ReadAsStringAsync();
                var transfers = JsonConvert.DeserializeObject<List<TransferInfo>>(json);
                if (transfers == null || transfers.Count == 0) return;

                foreach (var transfer in transfers)
                {
                    if (_downloadedTokens.Contains(transfer.Token)) continue;

                    _downloadedTokens.Add(transfer.Token);
                    StatusChanged?.Invoke($"Downloading: {transfer.FileName}...");
                    await DownloadSingleFileAsync(transfer);
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"FileDownloader: Check failed: {ex.Message}");
            }
        }

        /// <summary>
        /// Download a single transfer. Used by TransferQueue for per-item progress.
        /// </summary>
        public async Task<bool> DownloadSingleFileAsync(
            TransferInfo info,
            Action<long, long>? onProgress = null)
        {
            try
            {
                var downloadFolder = _storageSettings.DownloadFolder;
                Directory.CreateDirectory(downloadFolder);

                var filePath = Path.Combine(downloadFolder, info.FileName);

                // Handle duplicate name collisions
                var counter = 1;
                var baseName = Path.GetFileNameWithoutExtension(info.FileName);
                var extension = Path.GetExtension(info.FileName);
                while (File.Exists(filePath))
                {
                    filePath = Path.Combine(downloadFolder, $"{baseName} ({counter}){extension}");
                    counter++;
                }

                using var request = new HttpRequestMessage(HttpMethod.Get, $"http://{_hotspotIp}:{_signalingPort}/transfer/{info.Token}");
                if (App.SyncEngine.SessionToken != null)
                {
                    request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", App.SyncEngine.SessionToken);
                }

                using var response = await _client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead);
                response.EnsureSuccessStatusCode();

                using var stream = await response.Content.ReadAsStreamAsync();
                using var fileStream = new FileStream(filePath, FileMode.Create, FileAccess.Write, FileShare.None, 64 * 1024, true);

                var buffer = new byte[64 * 1024];
                long totalRead = 0;
                int bytesRead;

                while ((bytesRead = await stream.ReadAsync(buffer, 0, buffer.Length)) > 0)
                {
                    await fileStream.WriteAsync(buffer, 0, bytesRead);
                    totalRead += bytesRead;

                    if (info.Size > 0)
                    {
                        var percent = (int)((totalRead * 100) / info.Size);
                        onProgress?.Invoke(totalRead, info.Size);
                        DownloadProgressChanged?.Invoke(info.FileName, percent, totalRead, info.Size);
                        StatusChanged?.Invoke($"Downloading {info.FileName}: {percent}%");
                    }
                }

                var entry = new TransferEntry
                {
                    TransferId = info.Token,
                    FileName = Path.GetFileName(filePath),
                    FileSize = totalRead > 0 ? totalRead : info.Size,
                    MimeType = info.MimeType,
                    Direction = "received",
                    Status = "completed",
                    LocalPath = filePath,
                    Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
                };

                _db.InsertTransfer(entry);
                FileDownloaded?.Invoke(entry);
                StatusChanged?.Invoke($"Downloaded: {entry.FileName} ✓");
                Debug.WriteLine($"FileDownloader: Saved {entry.FileName} to {filePath}");

                // Notify Android to delete the staged cache file
                try
                {
                    var deleteUrl = $"http://{_hotspotIp}:{_signalingPort}/transfer/{info.Token}";
                    using var deleteReq = new HttpRequestMessage(HttpMethod.Delete, deleteUrl);
                    if (!string.IsNullOrEmpty(App.SyncEngine.SessionToken))
                        deleteReq.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", App.SyncEngine.SessionToken);
                    _ = await _client.SendAsync(deleteReq);
                }
                catch (Exception delEx)
                {
                    Debug.WriteLine($"FileDownloader: Failed to notify Android of transfer completion: {delEx.Message}");
                }

                return true;
            }
            catch (Exception ex)
            {
                StatusChanged?.Invoke($"Download failed: {info.FileName}");
                Debug.WriteLine($"FileDownloader: Download error: {ex}");
                return false;
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
