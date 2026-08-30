# DeviceSync ⚡ (Resonance)

DeviceSync (Resonance) is a high-performance, zero-cloud local network synchronization and media beaming tool. It seamlessly mirrors clipboard items, streams **photos, videos, and documents**, forwards **phone notifications with inline reply**, and provides **call intercept** between your Android device and Windows PC over a local Wi-Fi hotspot.

---

## 🚀 Key Features

- **⚡ 100% Offline & Private**: Operates entirely over your local Wi-Fi hotspot. No internet connection, external servers, cloud accounts, or logins required.
- **🔐 End-to-End Encryption (E2EE)**:
  - Dynamic **ECDHE P-256** session key exchange with **AES-256-GCM** payload encryption.
  - Device authorization & pairing approval prompt on Android for new Windows PCs.
- **📋 Real-Time Bidirectional Clipboard Sync**:
  - **Windows ➔ Android**: Instant push via WebSockets with COM-retry protection.
  - **Android ➔ Windows**: Hybrid sync with deduplication and loop prevention.
  - **Clipboard History**: Persistent journal of recent clipboard items on both devices.
- **📁 High-Speed Media & Document Transfer**:
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
- **🔔 Notification Mirroring & Call Intercept**:
  - **Live Notifications**: Phone notifications (WhatsApp, Telegram, SMS, System) mirrored as native **Windows 11 toast notifications**.
  - **Rich Messaging Support**: Full `MessagingStyle` extraction for conversation titles, senders, and message bodies.
  - **Inline Quick Replies**: Reply directly to WhatsApp/SMS messages from the Windows notification toast.
  - **📞 Call Intercept**: Incoming calls ring on Windows with **Answer** and **Decline** actions. Missed calls display as standard non-ringing alert cards.
- **🎨 Catppuccin Mocha Aesthetics**: Elegant dark-mode UI with dual tabs, live linear progress bars, and activity journals.

---

## 🛠️ Architecture & Tech Stack

| Component | Android Agent (Server) | Windows Agent (Client) |
|---|---|---|
| **Language / Runtime** | Kotlin 2.2 • JVM 17+ • Android 8.0+ (API 26+) | C# 13 • .NET 9.0 (WPF) |
| **UI Framework** | Jetpack Compose (Material 3) | Modern WPF with Catppuccin Mocha theme |
| **Signaling & Server** | Ktor Server 3.1 (Netty Engine) | `System.Net.WebSockets` & `HttpClient` |
| **Cryptography** | `java.security` (ECDHE P-256 + AES-256-GCM) | `System.Security.Cryptography` (ECDHE + AesGcm) |
| **Notifications** | `NotificationListenerService` + `RemoteInput` | `Microsoft.Toolkit.Uwp.Notifications` (Win11 Toast) |
| **Local Storage** | Room Database (SQLite) + MediaStore Scoped Storage | `Microsoft.Data.Sqlite` |
| **OS Integration** | Android Share Sheet (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`) | Named Pipe IPC, Win32 Clipboard Listener, Shell `SendTo` |

---

## 📦 Getting Started

### Prerequisites

1. **Windows PC**: Windows 10/11 with [.NET 9.0 SDK](https://dotnet.microsoft.com/download/dotnet/9.0) installed.
2. **Android Phone**: Android 8.0+ (API 26+) with Hotspot capability.
3. **Android Studio** (for building/installing Android app from source) or pre-built APK.

---

### Step 1: Build & Install the Android App

1. Open the `android/` folder in **Android Studio**.
2. Connect your Android phone via USB with USB Debugging enabled.
3. Click **Run ▶** (or run `./gradlew assembleDebug` and install `app-debug.apk` via `adb install -r`).
4. On your phone:
   - Grant **Notification Access** when prompted (required for notification mirroring).
   - Turn off battery optimization / allow background activity for uninterrupted background sync.

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

1. **Enable Hotspot**: Turn on your Android phone's **Wi-Fi Hotspot** and connect your Windows PC to it.
2. **Launch Agents**:
   - Open the **DeviceSync** app on your phone.
   - Launch **WindowsAgent** on your PC.
3. **Automatic Discovery & E2EE Handshake**:
   - The Windows Agent automatically probes the hotspot gateway (`192.168.43.1:7777`).
   - On the first connection, Android prompts you to approve the new Windows PC.
   - Once approved, an encrypted session is established (**Green Status Dot ✓**).
4. **Syncing & Sharing**:
   - **Clipboard**: Copy text anywhere on either device — it syncs automatically.
   - **Send Files from Phone**: Select items in any app ➔ Tap **Share** ➔ **Send to PC (DeviceSync)**.
   - **Send Files from Windows**: Drag & drop files onto the app window or right-click any file in File Explorer ➔ **Send to** ➔ **Send to Android**.
   - **Notifications & Calls**: Manage WhatsApp replies and incoming calls directly from your PC toast notifications.

---

## 📜 Version History

- **v3.0**: Notification Mirroring with rich `MessagingStyle` (WhatsApp/Telegram), Call Intercept (Answer/Decline), Windows toast inline quick replies, and ECDHE P-256 + AES-256-GCM End-to-End Encryption.
- **v2.5**: Bidirectional media & document transfers (Android Share Sheet, in-app pickers, Windows drag & drop, and Explorer context menu integration).
- **v2.0**: Jetpack Compose UI rewrite with Clean Architecture and dynamic hotspot discovery.
- **v1.0**: Initial prototype.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
