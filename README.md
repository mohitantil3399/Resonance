# DeviceSync ⚡

DeviceSync is a high-performance local network synchronization tool that seamlessly syncs your clipboard between your Android device and Windows PC using a local Wi-Fi Hotspot. 

## 🚀 Features

- **Blazing Fast Local Sync**: Operates entirely offline over your Android's Wi-Fi hotspot. No internet connection required.
- **Bidirectional Clipboard Sync**:
  - **Windows → Android**: Powered by real-time WebSockets.
  - **Android → Windows**: Reliable hybrid sync using WebSockets and REST polling (bypasses Android 10+ background clipboard restrictions).
- **Clean Architecture Android App**: Built with Modern Android Development (MAD) practices including Jetpack Compose, ViewModels, and Coroutines.
- **Native Windows Agent**: Lightweight, system-tray friendly C# WPF application built on .NET 9.
- **Local Persistence**: Clipboard history is securely stored locally on your Android device using Room Database and on Windows using SQLite.

## 🛠️ Tech Stack

### Android App (The Server)
- **Language**: Kotlin
- **UI**: Jetpack Compose (Material 3 with Catppuccin Mocha colors)
- **Architecture**: Clean Architecture (MVVM)
- **Network**: Ktor Server (Netty) for REST & WebSockets
- **Storage**: Room (SQLite)

### Windows Agent (The Client)
- **Framework**: .NET 9.0 (WPF)
- **Language**: C#
- **Networking**: System.Net.WebSockets & HttpClient
- **Storage**: Microsoft.Data.Sqlite

## 📦 How It Works

1. Turn on your Android device's **Wi-Fi Hotspot** and connect your Windows PC to it.
2. Open the **DeviceSync** Android app (it will run a foreground service and host a local Ktor server on port `7777`).
3. Run the **WindowsAgent** executable.
4. The Windows Agent will automatically scan the hotspot gateway IP to find and connect to the Android app.
5. Once connected, your clipboards are synchronized! 
   - **On Android**: Use the "Send Clipboard to PC" button or paste text in the app.
   - **On Windows**: Just copy text normally, it instantly syncs to Android.

## 📱 Screenshots & UI

The Android app features a beautiful, clean Compose UI with a centralized connection status, quick-send actions, and a persistent clipboard journal.

## 📜 Version History

- **v2.0**: Major rewrite! Introduced Jetpack Compose UI with Clean Architecture, fixed bidirectional clipboard sync issues (specifically Android 10+ background reading restrictions via REST polling + foreground focus), and dynamic hotspot IP discovery.
- **v1.0**: Initial prototype and proof of concept.
