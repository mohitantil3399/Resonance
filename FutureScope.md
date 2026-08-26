# Resonance ⚡ - Future Scope & Roadmap

This document outlines the planned future enhancements and "killer features" for Resonance, aimed at making it the ultimate local network synchronization tool.

## 🚀 Priority Features

### 1. File Transfers (Images & Documents)
- **Concept:** Extend the current text-only clipboard sync to handle binary files.
- **Implementation:** 
  - Upgrade the Ktor WebSocket/REST protocol to support multipart data or chunked binary streaming.
  - Users can select a photo/file on Android and beam it directly to a designated folder (e.g., `Downloads/Resonance`) on Windows, and vice-versa.

### 2. Android "Quick Settings" Tile
- **Concept:** Allow users to toggle the Resonance background service without opening the app.
- **Implementation:** Implement a `TileService` on Android so the user can place a Resonance toggle right next to their Wi-Fi and Bluetooth toggles in the notification shade.

### 3. Native Windows Toast Notifications
- **Concept:** Provide better visual feedback on the PC when a clipboard item is received.
- **Implementation:** Replace the silent system tray icon updates with native Windows 11 Toast Notifications (e.g., "Resonance: Clipboard received from Android") using the `Microsoft.Toolkit.Uwp.Notifications` package.

### 4. Auto-Start on Windows Boot
- **Concept:** Make the Windows Agent "set it and forget it" by running silently on startup.
- **Implementation:** Add an option in the WPF app to create a registry key or startup folder shortcut so `WindowsAgent.exe` launches in the background immediately when the PC turns on.

### 5. UI/UX Modernization for Windows
- **Concept:** Bring the sleek aesthetics of the Android app to the Windows desktop.
- **Implementation:** Update the standard WPF interface to feature a modern, dark-mode, glass-morphic design (perhaps using a library like ModernWpf or Wpf.Ui) that matches the Catppuccin theme on Android.

## 🔮 Moonshot Ideas
- **Multi-Device Support:** Allow connecting multiple Android phones or multiple PCs to the same hotspot session and broadcasting clipboard/files to all of them simultaneously.
- **End-to-End Encryption (E2EE):** While traffic is local, adding an AES encryption layer over the WebSocket would ensure maximum security on shared hotspots.
