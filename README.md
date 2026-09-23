# DeviceSync ⚡ (Resonance)

DeviceSync (Resonance) is a high-performance, zero-cloud local network synchronization and media beaming tool. It seamlessly mirrors clipboard items and streams **photos, videos, and documents** between your Android device and Windows PC over local Wi-Fi (Home / Office LAN), mobile hotspot, or USB tethering — with full end-to-end encryption and zero cloud dependency.

---

## 🚀 Key Features

- **⚡ 100% Offline & Private**: Operates entirely over your local network. No internet connection, external servers, cloud accounts, or logins required.
- **📡 Universal Zero-Configuration Discovery (v3.1)**:
  - **Home / Office Wi-Fi LAN**: Instant peer discovery (< 15ms) via lightweight UDP broadcast beacon on port 7777 and Android `NsdManager` (mDNS / DNS-SD).
  - **Mobile Hotspot & USB Tethering**: Dynamic DHCP Default Gateway resolution from active OS network adapters — works automatically across any Android OEM (Pixel, Samsung, Xiaomi, OnePlus, Motorola) and iOS with zero hardcoded IPs.
  - **Parallel Fast Probing**: Probes candidate IPs concurrently using `Task.WhenAny` (< 100ms connection time).
  - **Direct IP Fallback**: Modern modal prompt in the Windows UI to connect directly via IP on enterprise or campus networks with client isolation.
  - **Persistent Reconnection**: Remembers the last verified IP for instant zero-delay reconnects across sessions.
- **💓 WebSocket Heartbeat Keepalive (v3.1)**:
  - Automated 30-second ping/pong heartbeat keepalive prevents idle timeouts when the Windows app is minimized or the Android screen locks.
- **🔐 End-to-End Encryption (E2EE) & Trusted Devices**:
  - Dynamic **ECDHE P-256** session key exchange with **AES-256-GCM** authenticated payload encryption.
  - **Persistent Trusted Devices (v3.1)**: Android stores approved Windows pairings in a Room database (v3), skipping repeated approval prompts across app restarts while providing a dedicated UI to revoke paired devices at any time.
- **📋 Real-Time Bidirectional Clipboard Sync**:
  - **Windows ➔ Android**: Instant push via WebSockets with COM-retry protection.
  - **Android ➔ Windows**: Hybrid sync with deduplication and loop prevention.
  - **Clipboard History**: Persistent journal of recent clipboard items on both devices.
- **📁 High-Speed Media & Document Transfer**:
  - **Active Transfer Queue (v3.1)**: Observable transfer pipeline with per-file progress bars, automatic retry with backoff, and individual item cancellation.
  - **Android ➔ Windows**:
    - Native **Android Share Sheet** integration (`ACTION_SEND` & `ACTION_SEND_MULTIPLE`) — beam files directly from Google Photos, Gallery, Files, or browsers.
    - In-app **Photos & Videos** and **Documents** visual pickers.
  - **Windows ➔ Android**:
    - **Drag & Drop**: Drop any file(s) onto the Windows Agent window to transfer immediately.
    - **File Explorer Context Menu**: Right-click any file ➔ **Send to** ➔ **Send to Android (DeviceSync)**.
    - **Multi-File Picker**: Built-in file selector for photos, 4K videos, and large archives.
  - **Zero-Permission Media Categorization & Custom Paths**:
    - **Android**: Automatically places photos in `Pictures/SyncDevice` (instantly visible in Gallery), videos in `Movies/SyncDevice`, and docs in `Download/SyncDevice`. Custom storage trees selectable via Storage Access Framework.
    - **Windows**: Customizable download folder (defaults to `~/Downloads/SyncDevice`) with quick explorer launcher.
- **🎨 Catppuccin Mocha Aesthetics**: Elegant dark-mode UI with dual tabs, live linear progress bars, active transfer cards, and activity journals.

---

## 🛠️ Architecture & Tech Stack

| Component | Android Agent (Server) | Windows Agent (Client) |
|---|---|---|
| **Language / Runtime** | Kotlin 2.2 • JVM 17+ • Android 8.0+ (API 26+) | C# 13 • .NET 9.0 (WPF) |
| **UI Framework** | Jetpack Compose (Material 3) | Modern WPF with Catppuccin Mocha theme |
| **Signaling & Server** | Ktor Server 3.1 (Netty Engine) + UDP Beacon + NSD | `System.Net.WebSockets`, `UdpClient` & `HttpClient` |
| **Discovery** | UDP Broadcast Responder (`:7777`) + mDNS (`_devicesync._tcp`) | Dynamic DHCP Gateway + UDP Broadcast + Parallel Probing |
| **Cryptography** | `java.security` (ECDHE P-256 + AES-256-GCM) | `System.Security.Cryptography` (ECDHE + AesGcm) |
| **Local Storage** | Room Database v3 (SQLite) + MediaStore Scoped Storage | `Microsoft.Data.Sqlite` |
| **OS Integration** | Android Share Sheet (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`) | Named Pipe IPC, Win32 Clipboard Listener, Shell `SendTo` |

---

## 📦 Getting Started

### Prerequisites

1. **Windows PC**: Windows 10/11 with [.NET 9.0 SDK](https://dotnet.microsoft.com/download/dotnet/9.0) installed.
2. **Android Phone**: Android 8.0+ (API 26+) connected to the same Wi-Fi or phone hotspot.
3. **Android Studio** (for building/installing Android app from source) or pre-built APK.

---

### Step 1: Build & Install the Android App

1. Open the `android/` folder in **Android Studio**.
2. Connect your Android phone via USB with USB Debugging enabled.
3. Click **Run ▶** (or run `./gradlew assembleDebug` and install `app-debug.apk` via `adb install -r`).
4. On your phone:
   - Allow background activity / disable battery optimization for uninterrupted background sync.

---

### Step 2: Run the Windows Agent

#### Option A: Run directly from source (CLI)
```powershell
# From the repository root
dotnet run --project windows/WindowsAgent/WindowsAgent.csproj
```

#### Option B: Build a standalone executable
```powershell
dotnet publish windows/WindowsAgent/WindowsAgent.csproj -c Release -o ./publish
```
Then launch `./publish/WindowsAgent.exe`.

> **Optional (Portable .NET SDK Mode):**  
> If you are using a portable, non-installed .NET SDK located in a custom directory (e.g. `./dotnet_sdk`), set your environment variables before running:
> ```powershell
> $env:DOTNET_ROOT = "path\to\dotnet_sdk"
> $env:PATH = "path\to\dotnet_sdk;$env:PATH"
> dotnet run --project windows/WindowsAgent/WindowsAgent.csproj
> ```

---

## ⚡ How to Use

1. **Connect Devices**: Ensure both devices are connected to the same **Wi-Fi network**, or connect Windows to your phone's **Mobile Hotspot** or **USB Tethering**.
2. **Launch Agents**:
   - Open **DeviceSync** on Android (foreground service starts on port 7777).
   - Launch **WindowsAgent** on PC.
3. **Automatic Discovery & E2EE Handshake**:
   - Windows automatically discovers the phone via UDP beacon or dynamic DHCP gateway.
   - On first connection, Android prompts you to approve the new Windows PC.
   - Once approved, an encrypted session is established (**Green Status Dot ✓**). Android saves the PC to Trusted Devices.
4. **Syncing & Sharing**:
   - **Clipboard**: Copy text anywhere on either device — it syncs automatically.
   - **Send Files from Phone**: Select items in any app ➔ Tap **Share** ➔ **Send to PC (DeviceSync)**.
   - **Send Files from Windows**: Drag & drop files onto the app window or right-click any file in File Explorer ➔ **Send to** ➔ **Send to Android**. Monitor live file progress in the **Active Transfers** panel.

---

## 📜 Version History

- **v3.1**:
  - **Universal Zero-Config Discovery**: Network-agnostic discovery using UDP broadcast beacon, mDNS (`NsdManager`), and dynamic DHCP gateway resolution with zero hardcoded IPs.
  - **Parallel Fast Probing**: Sub-100ms discovery using concurrent `Task.WhenAny` probe loops.
  - **WebSocket Heartbeat**: 30-second ping/pong keepalive preventing disconnects on window minimize.
  - **Active Transfer Queue**: Multi-file pipeline with per-item progress bars, auto-retry, and individual item cancellation.
  - **Persistent Trusted Devices**: Android Room DB v3 persistence for paired PCs with in-app revocation UI.
  - **Direct IP Fallback**: Modern modal for manual IP entry on enterprise networks.
- **v3.0**: ECDHE P-256 + AES-256-GCM End-to-End Encryption with device pairing approval on Android.
- **v2.5**: Bidirectional media & document transfers (Android Share Sheet, in-app pickers, Windows drag & drop, and Explorer context menu integration).
- **v2.0**: Jetpack Compose UI rewrite with Clean Architecture.
- **v1.0**: Initial prototype.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
