using System;
using System.Diagnostics;
using System.IO;
using Microsoft.Win32;

namespace WindowsAgent
{
    /// <summary>
    /// Manages application storage settings including customizable download paths.
    /// </summary>
    public class StorageSettings
    {
        private static readonly string SettingsFilePath = Path.Combine(
            AppDomain.CurrentDomain.BaseDirectory, "settings.txt");

        private string _downloadFolder;

        public event Action<string>? DownloadPathChanged;

        public string DownloadFolder
        {
            get => _downloadFolder;
            set
            {
                if (_downloadFolder != value && !string.IsNullOrWhiteSpace(value))
                {
                    _downloadFolder = value;
                    Directory.CreateDirectory(_downloadFolder);
                    SaveSettings();
                    DownloadPathChanged?.Invoke(_downloadFolder);
                }
            }
        }

        public StorageSettings()
        {
            var defaultFolder = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                "Downloads", "SyncDevice");

            _downloadFolder = defaultFolder;
            LoadSettings(defaultFolder);
            Directory.CreateDirectory(_downloadFolder);
        }

        private void LoadSettings(string defaultFolder)
        {
            try
            {
                if (File.Exists(SettingsFilePath))
                {
                    var saved = File.ReadAllText(SettingsFilePath).Trim();
                    if (!string.IsNullOrWhiteSpace(saved) && Directory.Exists(Path.GetPathRoot(saved)))
                    {
                        _downloadFolder = saved;
                    }
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"StorageSettings: Error reading settings: {ex.Message}");
                _downloadFolder = defaultFolder;
            }
        }

        private void SaveSettings()
        {
            try
            {
                File.WriteAllText(SettingsFilePath, _downloadFolder);
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"StorageSettings: Error saving settings: {ex.Message}");
            }
        }

        /// <summary>
        /// Opens a modern folder picker dialog (.NET 9 WPF native OpenFolderDialog).
        /// </summary>
        public bool BrowseAndSelectFolder(System.Windows.Window owner)
        {
            try
            {
                var dialog = new OpenFolderDialog
                {
                    Title = "Select Folder for Received Files",
                    InitialDirectory = Directory.Exists(_downloadFolder) ? _downloadFolder : Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                    Multiselect = false
                };

                if (dialog.ShowDialog(owner) == true && !string.IsNullOrWhiteSpace(dialog.FolderName))
                {
                    DownloadFolder = dialog.FolderName;
                    return true;
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"StorageSettings: OpenFolderDialog error: {ex.Message}");
            }
            return false;
        }

        /// <summary>
        /// Opens the download folder in Windows File Explorer.
        /// </summary>
        public void OpenInExplorer()
        {
            try
            {
                Directory.CreateDirectory(_downloadFolder);
                Process.Start(new ProcessStartInfo
                {
                    FileName = "explorer.exe",
                    Arguments = $"\"{_downloadFolder}\"",
                    UseShellExecute = true
                });
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"StorageSettings: Could not open explorer: {ex.Message}");
            }
        }
    }
}
