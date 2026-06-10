using System;
using System.Collections.ObjectModel;
using System.Linq;
using System.Windows;
using System.Windows.Media;

namespace WindowsAgent;

/// <summary>
/// Interaction logic for MainWindow.xaml
/// Wires up the ClipboardListener to the SyncEngine and displays status/history.
/// </summary>
public partial class MainWindow : Window
{
    private readonly ClipboardListener _clipboardListener;
    private readonly ObservableCollection<ClipboardDisplayItem> _clipboardItems = new();

    public MainWindow()
    {
        InitializeComponent();

        _clipboardListener = new ClipboardListener();
        ClipboardList.ItemsSource = _clipboardItems;

        // Wire up status updates
        App.SyncEngine.StatusChanged += OnStatusChanged;
        App.Monitor.StatusChanged += OnStatusChanged;
        App.SyncEngine.ClipboardReceived += OnClipboardReceived;

        Loaded += OnWindowLoaded;
        Closed += OnWindowClosed;
    }

    private void OnWindowLoaded(object sender, RoutedEventArgs e)
    {
        // Start the native clipboard listener (needs HWND)
        _clipboardListener.Start(this);
        _clipboardListener.ClipboardTextChanged += OnLocalClipboardChanged;

        // Load existing history from DB
        RefreshHistory();
    }

    private void OnWindowClosed(object? sender, EventArgs e)
    {
        _clipboardListener.ClipboardTextChanged -= OnLocalClipboardChanged;
        _clipboardListener.Dispose();
    }

    private async void OnLocalClipboardChanged(string text)
    {
        // Push to Android via sync engine
        await App.SyncEngine.OnWindowsClipboardChangedAsync(text);
        Dispatcher.Invoke(RefreshHistory);
    }

    private void OnClipboardReceived(string content)
    {
        Dispatcher.Invoke(RefreshHistory);
    }

    private void OnStatusChanged(string status)
    {
        Dispatcher.Invoke(() =>
        {
            StatusText.Text = status;

            // Update the status dot color based on connection state
            if (App.SyncEngine.IsConnected)
            {
                StatusDot.Fill = new SolidColorBrush(Color.FromRgb(0xA6, 0xE3, 0xA1)); // Green
                DetailText.Text = "Connected to Android agent via WebSocket";

                // Show discovered IP in footer
                var ipMatch = System.Text.RegularExpressions.Regex.Match(status, @"\d+\.\d+\.\d+\.\d+");
                if (ipMatch.Success)
                    FooterText.Text = $"Connected to {ipMatch.Value}:7777";
            }
            else if (status.Contains("Scanning") || status.Contains("Connecting") || status.Contains("found"))
            {
                StatusDot.Fill = new SolidColorBrush(Color.FromRgb(0xF9, 0xE2, 0xAF)); // Yellow
                DetailText.Text = "Attempting to reach Android agent...";
                FooterText.Text = "Scanning subnet for Android agent on port 7777...";
            }
            else
            {
                StatusDot.Fill = new SolidColorBrush(Color.FromRgb(0xF3, 0x8B, 0xA8)); // Red
                DetailText.Text = "Connect to your phone's hotspot to begin";
                FooterText.Text = "Waiting for hotspot connection...";
            }
        });
    }

    private void RefreshHistory()
    {
        var items = App.Database.GetAllItems();
        _clipboardItems.Clear();
        foreach (var item in items.Take(10))
        {
            _clipboardItems.Add(new ClipboardDisplayItem
            {
                Content = item.Content,
                Source = item.Source == "android" ? "📱 Android" : "💻 Windows",
                TimeDisplay = DateTimeOffset.FromUnixTimeMilliseconds(item.Timestamp)
                    .LocalDateTime.ToString("HH:mm:ss")
            });
        }
    }
}

/// <summary>
/// Display model for the clipboard history list.
/// </summary>
public class ClipboardDisplayItem
{
    public string Content { get; set; } = "";
    public string Source { get; set; } = "";
    public string TimeDisplay { get; set; } = "";
}