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
                var response = await _client.GetStringAsync(
                    $"http://{_hotspotIp}:{_signalingPort}/transfers");

                var transfers = JsonConvert.DeserializeObject<List<TransferInfo>>(response);
                if (transfers == null || transfers.Count == 0) return;

                foreach (var transfer in transfers)
                {
                    if (_downloadedTokens.Contains(transfer.Token)) continue;

                    _downloadedTokens.Add(transfer.Token);
                    StatusChanged?.Invoke($"Downloading: {transfer.FileName}...");
                    await DownloadFileAsync(transfer);
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
                var downloadFolder = _storageSettings.DownloadFolder;
                Directory.CreateDirectory(downloadFolder);

                var filePath = Path.Combine(downloadFolder, info.FileName);

                // Handle duplicate name collisions (e.g. photo (1).jpg)
                var counter = 1;
                var baseName = Path.GetFileNameWithoutExtension(info.FileName);
                var extension = Path.GetExtension(info.FileName);
                while (File.Exists(filePath))
                {
                    filePath = Path.Combine(downloadFolder, $"{baseName} ({counter}){extension}");
                    counter++;
                }

                using var response = await _client.GetAsync(
                    $"http://{_hotspotIp}:{_signalingPort}/transfer/{info.Token}",
                    HttpCompletionOption.ResponseHeadersRead);

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
