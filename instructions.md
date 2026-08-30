# Local Device Sync Agent — Instructions

> Complete guide to build, deploy, run, and test the clipboard sync, media transfer, and notification mirroring system between your **Android device** and **Windows PC**.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Project Structure](#2-project-structure)
3. [Building the Android Agent](#3-building-the-android-agent)
4. [Building the Windows Agent](#4-building-the-windows-agent)
5. [First-Time Setup on Android](#5-first-time-setup-on-android)
6. [Running the System](#6-running-the-system)
7. [Testing Clipboard Sync](#7-testing-clipboard-sync)
8. [Testing File Transfer](#8-testing-file-transfer)
9. [Testing Notification Mirroring & Call Intercept](#9-testing-notification-mirroring--call-intercept)
10. [How It Works Under the Hood](#10-how-it-works-under-the-hood)
11. [Troubleshooting](#11-troubleshooting)
12. [Stopping Everything](#12-stopping-everything)

---

## 1. Prerequisites

### On Your PC

| Requirement | Notes |
|---|---|
| .NET 9.0 SDK | Installed globally via official installer or used portably via `dotnet` CLI |
| Android Studio / Android SDK | Used to build and deploy the Android app |
| ADB (Android Debug Bridge) | Included with Android SDK / Android Studio for installing APK over USB |

### On Your Phone

| Requirement | Notes |
|---|---|
| Android Device | Running Android 8.0+ (API 26+) |
| USB Debugging enabled | Settings → System → Developer Options → USB Debugging → ON |
| Hotspot capability | The phone acts as the local network host |

---

## 2. Project Structure

```
.
├── android/                   ← Android Studio project (Jetpack Compose + Ktor)
│   ├── app/
│   │   └── src/main/java/com/example/devicesync/
│   │       ├── crypto/        ← ECDHE P-256 + AES-256-GCM session cryptography
│   │       ├── notifications/ ← NotificationListenerService & RemoteInput reply
│   │       ├── ui/            ← Jetpack Compose UI (Catppuccin Mocha theme)
│   │       └── SyncForegroundService.kt
├── windows/
│   └── WindowsAgent/          ← .NET 9.0 WPF Client
│       ├── NotificationEngine.cs
│       ├── SessionCrypto.cs
│       ├── ClipboardSyncEngine.cs
│       └── MainWindow.xaml
├── README.md
└── instructions.md
```

---

## 3. Building the Android Agent

### Step 1: Open in Android Studio

1. Open Android Studio.
2. Click **File → Open**.
3. Select the `android/` directory in this repository.
4. Wait for Gradle sync to complete.

### Step 2: Connect Your Phone via USB

1. Plug your phone into your PC with a USB cable.
2. On the phone, tap **Allow USB Debugging** when prompted.
3. In Android Studio, your device should appear in the target device dropdown.

### Step 3: Build and Install

1. Select your target device from the device dropdown.
2. Click the green **Run ▶** button (or press `Shift + F10`).
3. Alternatively, build and install via CLI:
   ```bash
   cd android
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. The app will launch automatically on your phone.

### Step 4: Verify Installation

After the app opens, you should see:
- The Catppuccin-themed **Local Sync Agent** screen.
- A persistent notification in your drawer: **"Local Sync Agent — Server running on :7777"**.

---

## 4. Building the Windows Agent

### Option A: Run Directly (Recommended for Testing)

Open **PowerShell** in the repository root:

```powershell
dotnet run --project windows/WindowsAgent/WindowsAgent.csproj
```

This will automatically restore dependencies, compile the project, and launch the Windows Agent window.

### Option B: Build a Standalone Executable

```powershell
dotnet publish windows/WindowsAgent/WindowsAgent.csproj -c Release -o ./publish
```

Then run the agent anytime by launching:
```powershell
./publish/WindowsAgent.exe
```

---

## 5. First-Time Setup on Android

Manufacturers (e.g., Vivo, Xiaomi, Samsung) aggressively optimize background services. Ensure these one-time settings are configured:

### Step 1: Disable Battery Optimization
1. Go to **Settings → Battery → Background Power Consumption Management** (or App Battery Usage).
2. Find **DeviceSync** and set it to **No Restrictions** (or "Allow background activity").

### Step 2: Enable Auto Start
1. Go to **Settings → Apps → Special App Access → Auto Start**.
2. Find **DeviceSync** and toggle it **ON**.

### Step 3: Lock App in Recent Apps
1. Open the DeviceSync app.
2. Swipe up to Recent Apps view.
3. Tap the **lock icon** on the DeviceSync card to prevent system eviction.

### Step 4: Grant Notification Access
1. Switch to the **🔔 Notifications** tab in the app.
2. Tap **Grant Notification Access** (opens system settings).
3. Toggle **DeviceSync** to **ON** so it can mirror incoming notifications and calls.

---

## 6. Running the System

### Step 1: Start the Android Agent
1. Open the **DeviceSync** app on your phone.
2. Verify the notification indicates the server is running on port 7777.

### Step 2: Enable Hotspot on the Phone
1. Go to **Settings → Hotspot & Tethering → Wi-Fi Hotspot**.
2. Turn it **ON**.

### Step 3: Connect PC to Hotspot
1. On your Windows PC, connect to your phone's Wi-Fi hotspot.

### Step 4: Start the Windows Agent
1. Run the Windows Agent via `dotnet run --project windows/WindowsAgent/WindowsAgent.csproj` or launch `WindowsAgent.exe`.
2. Watch the status indicator:
   - 🔴 Red dot ➔ "Waiting for hotspot..."
   - 🟡 Yellow dot ➔ "Probing hotspot..."
   - 🟢 Green dot ➔ "Connected ✓" (and "🔒 Encrypted session established")

---

## 7. Testing Clipboard Sync

### Test 1: Copy on Windows ➔ Paste on Android
1. On your PC, copy any text (`Ctrl+C`).
2. The Windows Agent status will show: **"Sent: [text]..."**
3. On your phone, the notification will update: **"Received: [text]..."**
4. Paste anywhere on your phone — the copied text appears immediately.

### Test 2: Copy on Android ➔ Paste on Windows
1. On your phone, copy any text.
2. On your PC, press `Ctrl+V` — the copied text from your phone is pasted.

---

## 8. Testing File Transfer

### A. Sending Files from Phone to PC

#### Method 1: Via Android Share Sheet (Any App)
1. In Google Photos, Gallery, Files, or browser, select any photo, video, or document.
2. Tap **Share** ➔ choose **Send to PC (DeviceSync)**.
3. The file streams over the hotspot and downloads to your PC (default: `Downloads/SyncDevice/`).

#### Method 2: Via In-App Visual Pickers
1. In the app's **"📁 File Transfers"** tab, tap **"Photos & Videos"** or **"Documents"**.
2. Select your items to beam them to your PC immediately.

---

### B. Sending Files from PC to Phone

#### Method 1: Drag & Drop
1. Drag any file(s) from File Explorer and drop them directly onto the Windows Agent window.
2. The files are streamed to your phone with live percentage progress.

#### Method 2: File Explorer Right-Click Menu
1. In the Windows Agent, click **"⚡ Add to Right-Click Menu"**.
2. Right-click any file in Windows Explorer ➔ **Send to** ➔ **Send to Android (DeviceSync)**.

---

## 9. Testing Notification Mirroring & Call Intercept

### Test 1: WhatsApp / Messaging Notifications
1. Receive a WhatsApp, Telegram, or SMS message on your phone.
2. A native Windows toast notification appears with the sender name and message body.
3. Type a message in the toast's **reply text box** and click **Send** ➔ the reply is sent directly through your phone!

### Test 2: Incoming Phone Calls
1. Place a call to your phone.
2. Windows displays a ringing call toast with **"✓ Answer"** and **"✕ Decline"** buttons.
3. If the call is unanswered, a non-ringing **Missed Call** alert is displayed.

---

## 10. How It Works Under the Hood

### Network & Cryptography Architecture

```
Phone (Hotspot Server: 192.168.43.1)       Windows PC (Client: 192.168.43.x)
                 │                                        │
                 │←─────── HTTP GET /hello ───────────────│  (Discovery)
                 │                                        │
                 │←─────── WebSocket /sync ──────────────→│  (Signaling)
                 │                                        │
                 │←── ECDHE P-256 Key Exchange ──────────→│  (Session Setup)
                 │                                        │
                 │←── AES-256-GCM Encrypted Payloads ────→│  (Clipboards & Notifications)
                 │                                        │
                 │←─────── HTTP GET/POST /transfers ──────│  (Binary File Streaming)
```

---

## 11. Troubleshooting

| Issue | Cause | Solution |
|---|---|---|
| "Waiting for hotspot..." | PC not on phone's hotspot Wi-Fi | Connect PC to phone hotspot; verify phone server is running. |
| Notifications not appearing | Notification access missing | Grant Notification Access in Android Settings → Apps → Special Access. |
| Background service stopped | Battery saver killed service | Set Battery Optimization to "No Restrictions" & enable Auto Start. |
| Port 7777 blocked | Firewall / VPN restriction | Ensure local hotspot port 7777 is not blocked by third-party firewall. |

---

## 12. Stopping Everything

* **Windows Agent**: Close the window or press `Ctrl+C` in the terminal.
* **Android Agent**: Swipe down the notification drawer, tap the DeviceSync notification, and close the app, or tap **Force Stop** in Android Settings.
