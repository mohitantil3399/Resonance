using System;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;

namespace WindowsAgent
{
    /// <summary>
    /// Status of a single item in the transfer queue.
    /// </summary>
    public enum TransferStatus
    {
        Pending,
        InProgress,
        Done,
        Failed,
        Retrying
    }

    /// <summary>
    /// Represents one file in the transfer queue, observable by the UI.
    /// </summary>
    public class TransferQueueItem : INotifyPropertyChanged
    {
        public string Id { get; } = Guid.NewGuid().ToString();
        public string FileName { get; init; } = "";
        public string FilePath { get; init; } = "";       // null/empty for downloads
        public string Direction { get; init; } = "Upload"; // "Upload" or "Download"
        public string MimeType { get; init; } = "";

        // For downloads
        public string? DownloadToken { get; init; }
        public long TotalBytes { get; init; }

        private TransferStatus _status = TransferStatus.Pending;
        public TransferStatus Status
        {
            get => _status;
            set { _status = value; OnPropertyChanged(nameof(Status)); OnPropertyChanged(nameof(StatusDisplay)); OnPropertyChanged(nameof(IsActive)); }
        }

        private int _progress;
        public int Progress
        {
            get => _progress;
            set { _progress = value; OnPropertyChanged(nameof(Progress)); OnPropertyChanged(nameof(ProgressText)); }
        }

        private long _transferredBytes;
        public long TransferredBytes
        {
            get => _transferredBytes;
            set { _transferredBytes = value; OnPropertyChanged(nameof(TransferredBytes)); OnPropertyChanged(nameof(ProgressText)); }
        }

        public int RetryCount { get; set; }

        public string DirectionIcon => Direction == "Upload" ? "↑" : "↓";
        public string StatusDisplay => Status switch
        {
            TransferStatus.Pending    => "Pending",
            TransferStatus.InProgress => $"{Progress}%",
            TransferStatus.Done       => "✓ Done",
            TransferStatus.Failed     => "✗ Failed",
            TransferStatus.Retrying   => $"Retry {RetryCount}…",
            _                         => ""
        };
        public string ProgressText => Status == TransferStatus.InProgress
            ? $"{FormatBytes(TransferredBytes)} / {FormatBytes(TotalBytes)}"
            : "";
        public bool IsActive => Status is TransferStatus.InProgress or TransferStatus.Retrying;

        public event PropertyChangedEventHandler? PropertyChanged;
        protected void OnPropertyChanged(string name) =>
            Application.Current?.Dispatcher.Invoke(() =>
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name)));

        private static string FormatBytes(long bytes)
        {
            if (bytes <= 0) return "0 B";
            string[] units = { "B", "KB", "MB", "GB" };
            int i = (int)(Math.Log10(Math.Max(bytes, 1)) / Math.Log10(1024));
            i = Math.Clamp(i, 0, 3);
            return $"{bytes / Math.Pow(1024, i):F1} {units[i]}";
        }
    }

    /// <summary>
    /// Observable transfer queue that serializes uploads and downloads with
    /// per-item retry (up to 3 attempts with 2-second back-off) and cancel support.
    /// </summary>
    public class TransferQueue
    {
        private const int MaxRetries = 3;
        private static readonly TimeSpan RetryDelay = TimeSpan.FromSeconds(2);

        /// <summary>
        /// All items ever enqueued in this session (completed items stay visible).
        /// Bound to the active-transfers ListView in the UI.
        /// </summary>
        public ObservableCollection<TransferQueueItem> Items { get; } = new();

        public event Action<string>? StatusChanged;

        private readonly SemaphoreSlim _semaphore = new(1, 1);  // one transfer at a time

        // ── Public API ──────────────────────────────────────────────────────

        /// <summary>Enqueue one or more file uploads.</summary>
        public void EnqueueUploads(IEnumerable<string> filePaths)
        {
            var items = new List<TransferQueueItem>();
            foreach (var path in filePaths)
            {
                if (!System.IO.File.Exists(path)) continue;
                var info = new System.IO.FileInfo(path);
                var item = new TransferQueueItem
                {
                    FileName   = info.Name,
                    FilePath   = path,
                    Direction  = "Upload",
                    MimeType   = "application/octet-stream",
                    TotalBytes = info.Length
                };
                Application.Current?.Dispatcher.Invoke(() => Items.Add(item));
                items.Add(item);
            }
            _ = ProcessItemsAsync(items);
        }

        /// <summary>Enqueue one or more file downloads (from Android).</summary>
        public void EnqueueDownloads(IEnumerable<TransferInfo> transfers)
        {
            var items = new List<TransferQueueItem>();
            foreach (var t in transfers)
            {
                var item = new TransferQueueItem
                {
                    FileName      = t.FileName,
                    Direction     = "Download",
                    MimeType      = t.MimeType,
                    DownloadToken = t.Token,
                    TotalBytes    = t.Size
                };
                Application.Current?.Dispatcher.Invoke(() => Items.Add(item));
                items.Add(item);
            }
            _ = ProcessItemsAsync(items);
        }

        /// <summary>Cancel a pending or in-progress item by ID.</summary>
        public void Cancel(string itemId)
        {
            var item = Items.FirstOrDefault(i => i.Id == itemId);
            if (item != null && item.Status != TransferStatus.Done)
                item.Status = TransferStatus.Failed;
        }

        // ── Internal processing ─────────────────────────────────────────────

        private async Task ProcessItemsAsync(IEnumerable<TransferQueueItem> items)
        {
            foreach (var item in items)
            {
                if (item.Status == TransferStatus.Failed) continue; // cancelled before start

                await _semaphore.WaitAsync();
                try
                {
                    await RunWithRetryAsync(item);
                }
                finally
                {
                    _semaphore.Release();
                }
            }
        }

        private async Task RunWithRetryAsync(TransferQueueItem item)
        {
            for (int attempt = 1; attempt <= MaxRetries; attempt++)
            {
                if (item.Status == TransferStatus.Failed) return; // externally cancelled

                item.Status = attempt == 1 ? TransferStatus.InProgress : TransferStatus.Retrying;
                item.RetryCount = attempt - 1;

                bool success = item.Direction == "Upload"
                    ? await TryUploadAsync(item)
                    : await TryDownloadAsync(item);

                if (success)
                {
                    item.Progress = 100;
                    item.Status = TransferStatus.Done;
                    StatusChanged?.Invoke($"✓ {item.Direction}: {item.FileName}");
                    return;
                }

                if (attempt < MaxRetries)
                {
                    StatusChanged?.Invoke($"Retry {attempt}/{MaxRetries}: {item.FileName}");
                    await Task.Delay(RetryDelay);
                }
            }

            item.Status = TransferStatus.Failed;
            StatusChanged?.Invoke($"Failed: {item.FileName}");
        }

        private Task<bool> TryUploadAsync(TransferQueueItem item)
        {
            return App.FileUploader.UploadSingleFileAsync(
                item.FilePath,
                onProgress: (sent, total) =>
                {
                    item.TransferredBytes = sent;
                    item.Progress = total > 0 ? (int)((sent * 100) / total) : 0;
                });
        }

        private Task<bool> TryDownloadAsync(TransferQueueItem item)
        {
            if (item.DownloadToken == null) return Task.FromResult(false);
            return App.FileDownloader.DownloadSingleFileAsync(
                new TransferInfo { Token = item.DownloadToken, FileName = item.FileName, MimeType = item.MimeType, Size = item.TotalBytes },
                onProgress: (received, total) =>
                {
                    item.TransferredBytes = received;
                    item.Progress = total > 0 ? (int)((received * 100) / total) : 0;
                });
        }
    }
}
