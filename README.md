# DeviceSync ⚡

DeviceSync (Resonance) is a high-performance, offline local network synchronization tool that seamlessly syncs your clipboard and beams **photos, videos, and documents** between your Android device and Windows PC using a local Wi-Fi Hotspot.

---

## 🚀 Features

- **⚡ Blazing Fast Local Sync**: Operates entirely offline over your Android's Wi-Fi hotspot. No internet connection, cloud servers, or logins required.
- **📋 Bidirectional Clipboard Sync**:
  - **Windows → Android**: Powered by real-time WebSockets with COM-retry protection.
  - **Android → Windows**: Hybrid sync using WebSockets and REST polling (bypasses Android 10+ background clipboard restrictions).
- **📁 High-Speed Media & Document Transfer**:
  - **Android → Windows**:
    - Select photos, videos, or docs in **any app** (Google Photos, Gallery, Files) ➔ tap **Share** ➔ **Send to PC (DeviceSync)**.
    - In-app **Photos & Videos** and **Documents** pickers in the Android app.
  - **Windows → Android**:
    - **Drag & Drop**: Drop any file(s) onto the Windows Agent window to beam them to your phone.
    - **In-App File Picker**: Multi-file select dialog for photos, videos, and docs.
    - **Right-Click Shell Menu**: Right-click any file in Windows File Explorer ➔ **Send to** ➔ **Send to Android (DeviceSync)**.
- **📂 Zero-Permission Media Categorization & Custom Paths**:
  - **Android**: Automatically categorizes incoming files into `Pictures/SyncDevice` (instantly visible in Gallery/Photos), `Movies/SyncDevice`, and `Download/SyncDevice` without intrusive storage permissions. Custom folders selectable via Storage Access Framework.
  - **Windows**: Customizable download folder (defaults to `~/Downloads/SyncDevice`) with built-in folder browser and explorer launcher.
- **🎨 Catppuccin Mocha Aesthetics**: Modern dark-mode UI with dual tabs, live linear progress bars, and persistent activity journals (last 10 clips & 20 file transfers).

---

## 🛠️ Tech Stack

### Android App (Server)
- **Language**: Kotlin 2.2.10
- **UI**: Jetpack Compose (Material 3 with Catppuccin Mocha theme)
- **Architecture**: Clean Architecture (MVVM)
- **Network**: Ktor Server 3.1 (Netty) for REST streaming, WebSockets, & binary chunked transfers
- **Storage**: Room Database 2.8 (SQLite) & MediaStore Scoped Storage

### Windows Agent (Client)
- **Framework**: .NET 9.0 (WPF)
- **Language**: C# 13
- **Networking**: `System.Net.WebSockets` & `HttpClient` streaming
- **Storage**: `Microsoft.Data.Sqlite`
- **Shell**: Win32 `AddClipboardFormatListener`, NamedPipe IPC, Shell `SendTo` shortcut

---

## 📦 How It Works

1. Turn on your Android device's **Wi-Fi Hotspot** and connect your Windows PC to it.
2. Open the **DeviceSync** Android app (it hosts a local Ktor server on port `7777`).
3. Run the **WindowsAgent** executable.
4. The Windows Agent will automatically scan the hotspot gateway to find and connect to your phone.
5. **Start Syncing & Beaming Files**:
   - **Send Clipboard**: Copy on Windows to paste on phone, or tap "Send Clipboard" / copy on phone.
   - **Send from Phone**: Tap **Share** on any photo, video, or doc ➔ choose **Send to PC (DeviceSync)**.
   - **Send from PC**: Drag & drop any file onto the Windows Agent window, or right-click ➔ **Send to** ➔ **Send to Android**.

---

## ▶️ Running the Windows Agent

The `.NET 9 SDK` is installed **portably** in `d:\SyncDevice\dotnet_sdk\` (nothing on C drive).

### Run from Source (CLI):
```powershell
$env:DOTNET_ROOT = "d:\SyncDevice\dotnet_sdk"
$env:PATH = "d:\SyncDevice\dotnet_sdk;$env:PATH"
d:\SyncDevice\dotnet_sdk\dotnet.exe run --project d:\SyncDevice\windows\WindowsAgent\WindowsAgent.csproj
```

### Build & Run Standalone Executable:
```powershell
$env:DOTNET_ROOT = "d:\SyncDevice\dotnet_sdk"
$env:PATH = "d:\SyncDevice\dotnet_sdk;$env:PATH"
d:\SyncDevice\dotnet_sdk\dotnet.exe publish d:\SyncDevice\windows\WindowsAgent\WindowsAgent.csproj -c Release -o d:\SyncDevice\windows\output
```
Then run:
```powershell
d:\SyncDevice\windows\output\WindowsAgent.exe
```

---

## 📜 Version History

- **v2.5 (Current)**: Added seamless bidirectional media & document transfer (Android Share Intent `ACTION_SEND` & `ACTION_SEND_MULTIPLE`, in-app Photo & Document Pickers, Windows Drag & Drop, Right-Click `SendTo` Shell Menu integration, streaming uploads via `POST /upload`, live progress reporting, and customizable storage paths).
- **v2.0**: Major rewrite! Introduced Jetpack Compose UI with Clean Architecture, fixed bidirectional clipboard sync, and dynamic hotspot IP discovery.
- **v1.0**: Initial prototype.
