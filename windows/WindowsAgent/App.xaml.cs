using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Windows;

namespace WindowsAgent;

/// <summary>
/// Application entry point and service container.
/// Supports single-instance mutex and IPC for SendTo right-click context menu actions.
/// </summary>
public partial class App : Application
{
    private static Mutex? _instanceMutex;
    private static CancellationTokenSource _ipcCancel = new();

    // Shared services — initialized once, used across the app
    public static StorageSettings StorageSettings { get; } = new StorageSettings();
    public static ClipboardDatabase Database { get; } = new ClipboardDatabase();
    public static ClipboardSyncEngine SyncEngine { get; } = new ClipboardSyncEngine(Database);
    public static FileDownloader FileDownloader { get; } = new FileDownloader(StorageSettings, Database);
    public static FileUploader FileUploader { get; } = new FileUploader(Database);
    public static NotificationEngine NotificationEngine { get; } = new NotificationEngine(SyncEngine);
    public static NetworkMonitor Monitor { get; } = new NetworkMonitor(SyncEngine);

    protected override void OnStartup(StartupEventArgs e)
    {
        // Check single-instance mutex
        _instanceMutex = new Mutex(true, ShellIntegration.MutexName, out bool isFirstInstance);

        if (!isFirstInstance)
        {
            // Another instance is already running
            if (e.Args.Length > 0)
            {
                // Send file paths to the running instance via Named Pipe
                ShellIntegration.SendToPrimaryInstance(e.Args);
            }
            // Exit secondary instance
            Shutdown();
            return;
        }

        base.OnStartup(e);

        // Wire immediate download on WebSocket file notification
        SyncEngine.FilesAvailableReceived += () =>
        {
            _ = FileDownloader.CheckAndDownloadAsync();
        };

        // Wire notification mirroring events
        SyncEngine.NotificationReceived += json => NotificationEngine.HandleNotification(json);
        SyncEngine.NotificationDismissed += json => NotificationEngine.HandleDismissal(json);

        // Start IPC server to receive files from right-click context menu
        ShellIntegration.StartIpcServer(files =>
        {
            Dispatcher.Invoke(() =>
            {
                _ = FileUploader.UploadFilesAsync(files);
            });
        }, _ipcCancel.Token);

        // Start network monitor & discovery
        Monitor.Start();

        // Process any files passed at startup
        if (e.Args.Length > 0)
        {
            var validFiles = e.Args.Where(File.Exists).ToArray();
            if (validFiles.Length > 0)
            {
                _ = FileUploader.UploadFilesAsync(validFiles);
            }
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _ipcCancel.Cancel();
        Monitor.Stop();
        _ = SyncEngine.DisconnectAsync();
        NotificationEngine.Cleanup();
        _instanceMutex?.ReleaseMutex();
        base.OnExit(e);
    }
}
