using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;

namespace WindowsAgent
{
    /// <summary>
    /// Handles streaming file uploads from Windows PC to the Android agent via HTTP POST /upload.
    /// Supports real-time progress reporting and multi-gigabyte file streaming.
    /// </summary>
    public class FileUploader
    {
        private string _hotspotIp = "192.168.43.1";
        private int _signalingPort = 7777;
        private readonly ClipboardDatabase _db;
        private static readonly HttpClient _httpClient = new HttpClient { Timeout = TimeSpan.FromHours(2) };

        public event Action<string>? StatusChanged;
        public event Action<string, int, long, long>? UploadProgressChanged;
        public event Action<TransferEntry>? FileUploaded;

        public FileUploader(ClipboardDatabase db)
        {
            _db = db;
        }

        public void UpdateEndpoint(string ip, int port)
        {
            _hotspotIp = ip;
            _signalingPort = port;
        }

        /// <summary>
        /// Upload a batch of files sequentially with progress reporting.
        /// </summary>
        public async Task UploadFilesAsync(IEnumerable<string> filePaths, CancellationToken ct = default)
        {
            var list = new List<string>(filePaths);
            if (list.Count == 0) return;

            int index = 0;
            int total = list.Count;

            foreach (var path in list)
            {
                if (ct.IsCancellationRequested) break;
                if (!File.Exists(path)) continue;

                index++;
                var fileName = Path.GetFileName(path);
                StatusChanged?.Invoke($"Sending ({index}/{total}): {fileName}...");

                await UploadSingleFileAsync(path, ct);
            }
        }

        /// <summary>
        /// Upload a single file with streaming and progress callback.
        /// </summary>
        public async Task<bool> UploadSingleFileAsync(string filePath, CancellationToken ct = default)
        {
            var fileInfo = new FileInfo(filePath);
            if (!fileInfo.Exists) return false;

            var fileName = fileInfo.Name;
            var fileSize = fileInfo.Length;
            var mimeType = GetMimeType(filePath);

            try
            {
                var url = $"http://{_hotspotIp}:{_signalingPort}/upload";
                using var request = new HttpRequestMessage(HttpMethod.Post, url);

                request.Headers.Add("X-File-Name", Uri.EscapeDataString(fileName));
                request.Headers.Add("X-File-Size", fileSize.ToString());

                if (App.SyncEngine.SessionToken != null)
                {
                    request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", App.SyncEngine.SessionToken);
                }

                using var fileStream = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024, true);
                using var content = new ProgressableStreamContent(fileStream, 64 * 1024, (sent, total) =>
                {
                    var percent = total > 0 ? (int)((sent * 100) / total) : 0;
                    UploadProgressChanged?.Invoke(fileName, percent, sent, total);
                    StatusChanged?.Invoke($"Sending {fileName}: {percent}%");
                });

                content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue(mimeType);
                request.Content = content;

                var response = await _httpClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
                response.EnsureSuccessStatusCode();

                // Record in transfer history
                var transferId = Guid.NewGuid().ToString();
                var timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                var entry = new TransferEntry
                {
                    TransferId = transferId,
                    FileName = fileName,
                    FileSize = fileSize,
                    MimeType = mimeType,
                    Direction = "sent",
                    Status = "completed",
                    LocalPath = filePath,
                    Timestamp = timestamp
                };

                _db.InsertTransfer(entry);
                FileUploaded?.Invoke(entry);
                StatusChanged?.Invoke($"Sent: {fileName} ✓");
                Debug.WriteLine($"FileUploader: Successfully sent {fileName} ({fileSize} bytes)");
                return true;
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"FileUploader: Failed to upload {fileName}: {ex.Message}");
                StatusChanged?.Invoke($"Upload failed: {fileName}");
                return false;
            }
        }

        private static string GetMimeType(string path)
        {
            var ext = Path.GetExtension(path).ToLowerInvariant();
            return ext switch
            {
                ".jpg" or ".jpeg" => "image/jpeg",
                ".png" => "image/png",
                ".gif" => "image/gif",
                ".webp" => "image/webp",
                ".heic" => "image/heic",
                ".svg" => "image/svg+xml",
                ".mp4" => "video/mp4",
                ".mkv" => "video/x-matroska",
                ".mov" => "video/quicktime",
                ".avi" => "video/x-msvideo",
                ".webm" => "video/webm",
                ".mp3" => "audio/mpeg",
                ".wav" => "audio/wav",
                ".flac" => "audio/flac",
                ".aac" => "audio/aac",
                ".pdf" => "application/pdf",
                ".doc" => "application/msword",
                ".docx" => "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ".xls" or ".xlsx" => "application/vnd.ms-excel",
                ".ppt" or ".pptx" => "application/vnd.ms-powerpoint",
                ".txt" => "text/plain",
                ".zip" => "application/zip",
                ".rar" => "application/x-rar-compressed",
                ".7z" => "application/x-7z-compressed",
                _ => "application/octet-stream"
            };
        }
    }

    /// <summary>
    /// HttpContent wrapper that reports byte-level progress as data is written to the network.
    /// </summary>
    public class ProgressableStreamContent : HttpContent
    {
        private readonly Stream _stream;
        private readonly int _bufferSize;
        private readonly Action<long, long> _progressCallback;

        public ProgressableStreamContent(Stream stream, int bufferSize, Action<long, long> progressCallback)
        {
            _stream = stream ?? throw new ArgumentNullException(nameof(stream));
            _bufferSize = bufferSize;
            _progressCallback = progressCallback ?? throw new ArgumentNullException(nameof(progressCallback));
        }

        protected override async Task SerializeToStreamAsync(Stream stream, TransportContext? context)
        {
            var buffer = new byte[_bufferSize];
            long totalRead = 0;
            var totalLength = _stream.Length;

            int bytesRead;
            while ((bytesRead = await _stream.ReadAsync(buffer, 0, buffer.Length)) > 0)
            {
                await stream.WriteAsync(buffer, 0, bytesRead);
                totalRead += bytesRead;
                _progressCallback(totalRead, totalLength);
            }
        }

        protected override bool TryComputeLength(out long length)
        {
            length = _stream.Length;
            return true;
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                _stream.Dispose();
            }
            base.Dispose(disposing);
        }
    }
}
