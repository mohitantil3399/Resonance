using System.Windows;

namespace WindowsAgent;

/// <summary>
/// Interaction logic for App.xaml
/// </summary>
public partial class App : Application
{
    // Shared services — initialized once, used across the app
    public static ClipboardDatabase Database { get; } = new ClipboardDatabase();
    public static ClipboardSyncEngine SyncEngine { get; } = new ClipboardSyncEngine(Database);
    public static FileDownloader FileDownloader { get; } = new FileDownloader();
    public static NetworkMonitor Monitor { get; } = new NetworkMonitor(SyncEngine, FileDownloader);

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        Monitor.Start();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        Monitor.Stop();
        _ = SyncEngine.DisconnectAsync();
        base.OnExit(e);
    }
}
