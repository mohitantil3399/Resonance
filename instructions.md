# Local Device Sync Agent — Instructions

> Everything you need to build, deploy, run, and test the clipboard sync + file transfer system between your **Vivo V2246 (Android)** and **Windows 11 laptop**.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Project Structure](#2-project-structure)
3. [Building the Android Agent](#3-building-the-android-agent)
4. [Building the Windows Agent](#4-building-the-windows-agent)
5. [First-Time Setup on Your Vivo Phone](#5-first-time-setup-on-your-vivo-phone)
6. [Running the System](#6-running-the-system)
7. [Testing Clipboard Sync](#7-testing-clipboard-sync)
8. [Testing File Transfer](#8-testing-file-transfer)
9. [How It Works Under the Hood](#9-how-it-works-under-the-hood)
10. [Troubleshooting](#10-troubleshooting)
11. [Stopping Everything](#11-stopping-everything)
12. [Uninstalling](#12-uninstalling)

---

## 1. Prerequisites

### On Your Laptop

| Requirement | Where It Is | Notes |
|---|---|---|
| Android Studio | Already installed | Used to build and install the Android app |
| .NET 9 SDK | `d:\SyncDevice\dotnet_sdk\` | Already installed portably on D drive. **Nothing on C drive.** |
| ADB | Comes with Android Studio | Used to install the APK via USB |

### On Your Phone

| Requirement | Notes |
|---|---|
| Vivo V2246 | Running Android 14 or 15 |
| USB Debugging enabled | Settings → System → Developer Options → USB Debugging → ON |
| Hotspot capability | The phone acts as the network host |

---

## 2. Project Structure

```
d:\SyncDevice\
├── android\                    ← Android Studio project (open this folder)
├── windows\
│   └── WindowsAgent\          ← .NET 9 WPF project
├── dotnet_sdk\                ← Portable .NET 9 SDK (D drive only)
├── uninstall_dotnet.bat       ← One-click .NET removal script
├── dotnet-install.ps1         ← SDK installer script (already used)
└── instructions.md            ← This file
```

---

## 3. Building the Android Agent

### Step 1: Open in Android Studio

1. Open Android Studio
2. Click **File → Open**
3. Navigate to `d:\SyncDevice\android` and select it
4. Wait for Gradle sync to complete (this may take a few minutes the first time as it downloads Ktor, Room, and WebRTC dependencies)

### Step 2: Connect Your Phone via USB

1. Plug your Vivo V2246 into the laptop with a USB cable
2. On the phone, tap **Allow USB Debugging** when prompted
3. In Android Studio, your device should appear in the device dropdown (top toolbar)

### Step 3: Build and Install

1. Select your Vivo device from the device dropdown
2. Click the green **Run ▶** button (or press `Shift + F10`)
3. Wait for the build to complete and the app to install
4. The app will open automatically on your phone

### Step 4: Verify Installation

After the app opens, you should see:
- A screen saying **"Hello Sync Agent is running in background!"**
- A persistent notification in your notification drawer: **"Local Sync Agent — Listening for Windows Agent connection..."**

> **Important:** You can now close the app. The service will keep running in the background via the notification.

---

## 4. Building the Windows Agent

### Option A: Run Directly (Recommended for Testing)

Open **PowerShell** and run:

```powershell
$env:DOTNET_ROOT = "d:\SyncDevice\dotnet_sdk"
$env:PATH = "d:\SyncDevice\dotnet_sdk;$env:PATH"
d:\SyncDevice\dotnet_sdk\dotnet.exe run --project d:\SyncDevice\windows\WindowsAgent\WindowsAgent.csproj
```

This will:
1. Restore NuGet packages (first time only)
2. Compile the project
3. Launch the Windows Agent window

### Option B: Build a Standalone Executable

```powershell
$env:DOTNET_ROOT = "d:\SyncDevice\dotnet_sdk"
$env:PATH = "d:\SyncDevice\dotnet_sdk;$env:PATH"
d:\SyncDevice\dotnet_sdk\dotnet.exe publish d:\SyncDevice\windows\WindowsAgent\WindowsAgent.csproj -c Release -o d:\SyncDevice\windows\output
```

Then run the agent anytime by double-clicking:
```
d:\SyncDevice\windows\output\WindowsAgent.exe
```

---

## 5. First-Time Setup on Your Vivo Phone

Vivo phones aggressively kill background services. You **must** change these settings once, or the sync agent will be killed after a few minutes.

### Step 1: Disable Battery Optimization for DeviceSync

1. Go to **Settings → Battery → Background Power Consumption Management**
2. Find **DeviceSync** in the app list
3. Set it to **No Restrictions** (or "Allow background activity")

### Step 2: Enable Auto Start

1. Go to **Settings → Apps → Special App Access → Auto Start** (or search "Auto Start" in Settings)
2. Find **DeviceSync** and toggle it **ON**

### Step 3: Lock the App in Recent Apps

1. Open the DeviceSync app
2. Open the Recent Apps view (swipe up and hold)
3. Tap the **lock icon** on the DeviceSync card (this prevents Vivo from killing it)

### Step 4: Grant Notification Permission

- When you first open the app, it will ask for notification permission. **Tap Allow.**
- The persistent notification is what keeps the background service alive.

---

## 6. Running the System

Here is the exact sequence to get everything working:

### Step 1: Start the Android Agent

1. Open the **DeviceSync** app on your Vivo phone (just once — then you can close it)
2. Verify the notification appears: **"Local Sync Agent — Server running on :7777"**

### Step 2: Enable Hotspot on the Phone

1. Go to **Settings → Hotspot & Tethering → Wi-Fi Hotspot**
2. Turn it **ON**
3. Note your hotspot name and password

### Step 3: Connect Laptop to Hotspot

1. On your Windows laptop, go to **Wi-Fi settings**
2. Connect to your phone's hotspot network
3. Wait for the connection to establish

### Step 4: Start the Windows Agent

1. Open **PowerShell** and run:

```powershell
$env:DOTNET_ROOT = "d:\SyncDevice\dotnet_sdk"
$env:PATH = "d:\SyncDevice\dotnet_sdk;$env:PATH"
d:\SyncDevice\dotnet_sdk\dotnet.exe run --project d:\SyncDevice\windows\WindowsAgent\WindowsAgent.csproj
```

2. The **Local Sync Agent** window will open
3. Watch the status indicator:
   - 🔴 Red dot → "Waiting for hotspot..." (not connected yet)
   - 🟡 Yellow dot → "Probing hotspot..." (trying to connect)
   - 🟢 Green dot → "Connected ✓" (you're good to go!)

### What Happens Automatically

```
Phone hotspot ON
       ↓
Laptop connects to hotspot WiFi
       ↓
Windows Agent probes 192.168.43.1:7777
       ↓
Android Agent responds to /hello
       ↓
WebSocket connection established
       ↓
Clipboard sync is LIVE
File transfer polling starts
```

The whole process takes **2-5 seconds** after the laptop joins the hotspot.

---

## 7. Testing Clipboard Sync

### Test 1: Copy on Windows → Paste on Android

1. On your laptop, copy any text (e.g., `Ctrl+C` on "Hello from Windows")
2. Watch the Windows Agent window — it should show: **"Sent: Hello from Windows..."**
3. On your phone, the notification should update: **"Received: Hello from Windows..."**
4. On your phone, open any app and **long press → Paste** — the text should appear!

### Test 2: Copy on Android → Paste on Windows

1. On your phone, copy any text (e.g., long press on a URL in Chrome and tap "Copy")
2. Watch the phone notification — it should show: **"Sent: [your text]..."**
3. On your laptop, the Windows Agent status should update: **"Received: [your text]..."**
4. On your laptop, press `Ctrl+V` anywhere — the text from your phone appears!

### Test 3: Verify Clipboard History

The Windows Agent window shows a **"📋 Clipboard Journal (last 10)"** section. Each item shows:
- The copied text
- Whether it came from 📱 Android or 💻 Windows
- The timestamp

Only the **last 10 copies** are retained. Older entries are automatically deleted.

### Test 4: Loop Prevention

1. Copy "test123" on Windows
2. It syncs to Android — ✅
3. Android's clipboard now has "test123", but it does **NOT** send it back to Windows
4. No infinite loop — the system uses UUID tracking to prevent this

---

## 8. Testing File Transfer

### Sending a File from Phone to Laptop

1. On your phone, open any app (Gallery, Files, Chrome, etc.)
2. Tap the **Share** button on any file (image, video, PDF, anything)
3. In the Share Sheet, look for **"Send to Laptop"** and tap it
4. You'll see a confirmation screen: **"Shared successfully!"**
5. On your laptop, the file will automatically download to:

```
C:\Users\MOHIT\Downloads\SyncDevice\
```

6. The Windows Agent status will show download progress (e.g., "Downloading video.mp4: 45%...")

### File Transfer Details

- Files are transferred via direct HTTP streaming over the hotspot network
- Large files are chunked in 8KB blocks for reliability
- Duplicate filenames are handled automatically (adds "(1)", "(2)", etc.)
- Transfer speed depends on your hotspot bandwidth (typically 10-50 MB/s on WiFi)

---

## 9. How It Works Under the Hood

### Network Layer

```
Phone (Hotspot Host)          Laptop (Client)
192.168.43.1                  192.168.43.x
     │                              │
     │←── HTTP GET /hello ──────────│  (Discovery)
     │                              │
     │←── WebSocket /sync ─────────→│  (Clipboard sync)
     │                              │
     │←── HTTP GET /transfer/xxx ───│  (File download)
```

### Clipboard Sync Protocol

Every clipboard item is a JSON message:
```json
{
  "clipboardId": "a1b2c3d4-...",
  "source": "windows",
  "content": "npm install express"
}
```

- `clipboardId` — Unique UUID, used for loop prevention
- `source` — Which device created this copy
- `content` — The actual clipboard text

### Data Storage

Both devices maintain an identical SQLite database with a `clipboard_items` table:

| Column | Type | Description |
|--------|------|-------------|
| clipboardId | TEXT (PK) | UUID for deduplication |
| source | TEXT | "android" or "windows" |
| content | TEXT | Clipboard text |
| timestamp | INTEGER | Epoch milliseconds |

Maximum **10 rows** are retained at any time.

---

## 10. Troubleshooting

### Windows Agent says "Waiting for hotspot..." but I'm connected

**Cause:** The laptop might be connected to the hotspot, but the Android agent isn't running.

**Fix:**
1. Check the phone notification — is "Local Sync Agent" visible?
2. If not, open the DeviceSync app once to restart the service
3. If the notification is there, try restarting the Windows Agent

### Clipboard sync works one way but not the other

**Android → Windows works, but Windows → Android doesn't:**
- Make sure the WebSocket is connected (green dot in Windows Agent)
- Check if Android clipboard access is restricted — try copying while the DeviceSync app is briefly in the foreground

**Windows → Android works, but Android → Windows doesn't:**
- Android 15 may restrict background clipboard reading. Copy text while the DeviceSync notification is visible in the notification shade

### The Android service keeps getting killed

**This is a Vivo-specific issue.** Make sure you've done ALL of these:
1. ✅ Battery optimization → No Restrictions for DeviceSync
2. ✅ Auto Start → Enabled for DeviceSync
3. ✅ Lock in Recent Apps (swipe up → lock icon)
4. ✅ Notification permission granted

### File transfer doesn't start

1. Make sure you shared the file to **"Send to Laptop"** (not some other app)
2. Make sure the Windows Agent is running and connected (green dot)
3. Check that the phone has the file access permission

### Connection drops frequently

The Windows Agent retries every 10 seconds automatically. If the connection keeps dropping:
1. Make sure the hotspot is stable
2. Reduce the distance between phone and laptop
3. Check if any VPN or firewall is blocking port 7777

### Port 7777 is blocked

If another app uses port 7777, you can change it:
- **Android:** In [SyncForegroundService.kt](file:///d:/SyncDevice/android/app/src/main/java/com/example/devicesync/SyncForegroundService.kt), change `private const val PORT = 7777`
- **Windows:** Update the `SignalingPort` constant in [NetworkMonitor.cs](file:///d:/SyncDevice/windows/WindowsAgent/NetworkMonitor.cs), [ClipboardSyncEngine.cs](file:///d:/SyncDevice/windows/WindowsAgent/ClipboardSyncEngine.cs), and [FileDownloader.cs](file:///d:/SyncDevice/windows/WindowsAgent/FileDownloader.cs)

---

## 11. Stopping Everything

### Stop the Windows Agent
- Simply close the **Local Sync Agent** window, or press `Ctrl+C` in the PowerShell terminal

### Stop the Android Agent
- Swipe down the notification drawer
- Tap the **"Local Sync Agent"** notification
- The app will open — force close it from Recent Apps (swipe up → swipe the card away, after unlocking it first)

Or:
- Go to **Settings → Apps → DeviceSync → Force Stop**

---

## 12. Uninstalling

### Remove the .NET SDK (one click)

Double-click this file:
```
d:\SyncDevice\uninstall_dotnet.bat
```

This deletes the entire `d:\SyncDevice\dotnet_sdk\` folder. Your C drive was never touched.

### Remove the Windows Agent

Delete the folder:
```
d:\SyncDevice\windows\
```

### Remove the Android App

On your phone:
1. Go to **Settings → Apps → DeviceSync → Uninstall**

Or long press the app icon → Uninstall

### Remove Everything

Delete the entire folder:
```
d:\SyncDevice\
```

That's it. No registry entries, no system services, no leftover files anywhere. Everything was self-contained.

---

## Quick Reference Card

| Action | How |
|--------|-----|
| Start Android Agent | Open DeviceSync app once on phone |
| Start Windows Agent | Run `d:\SyncDevice\dotnet_sdk\dotnet.exe run --project d:\SyncDevice\windows\WindowsAgent\WindowsAgent.csproj` |
| Copy sync (Win → Phone) | Just `Ctrl+C` on laptop — auto syncs |
| Copy sync (Phone → Win) | Just copy on phone — auto syncs |
| Send file to laptop | Share → "Send to Laptop" on phone |
| Downloaded files location | `C:\Users\MOHIT\Downloads\SyncDevice\` |
| Change sync port | Edit `PORT`/`SignalingPort` constants (see Troubleshooting) |
| Uninstall .NET SDK | Double-click `d:\SyncDevice\uninstall_dotnet.bat` |
