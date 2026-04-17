# Phone RDP

Android RDP 客户端 MVP，使用 Kotlin + Jetpack Compose + JNI/NDK 接入 FreeRDP（arm64），可连接远程 Windows 电脑并进行基础操作。

## 目录

- [功能亮点](#milestone-2--3-delivered)
- [快速安装](#quick-install-pre-built-apk)
- [真机验证脚本](#real-device-verification-script-click-order)
- [构建要求](#build-requirements)
- [目录结构](#directory-structure)
- [文档索引](#document-index)
- [Known Limitations](#known-limitations)
- [License](#license)


## Milestone 2 + 3 delivered

- Real FreeRDP connection path:
  - `freerdp_connect` handshake
  - session event loop + graceful disconnect
  - TLS/NLA/auth/network error mapping to app error codes
- Remote frame rendering:
  - GDI updates captured in native layer
  - frame metadata polling + frame copy to Android `Bitmap`
  - Compose image display + local zoom
- Input mapping:
  - tap => left click
  - long press => right click
  - two-finger drag => wheel scroll
  - soft keyboard text => Unicode input channel
- Secure recent connections:
  - last 5 connections persisted
  - credentials stored with `EncryptedSharedPreferences`
  - key material managed by Android Keystore (`MasterKey`)
- Reconnect and hint improvements:
  - reconnect attempt counter
  - richer status note and snackbar feedback
  - recent item tap restores full config for quick reconnect
- Certificate trust and disconnect events:
  - native certificate callback now waits for UI trust decision
  - Compose dialog supports `Trust Once` / `Reject`
  - native session disconnect is pushed to UI event channel
  - UI proactively updates status on unexpected disconnect

---

## Quick install (pre-built APK)

A debug APK is already built and located at:

```
app/build/outputs/apk/debug/app-debug.apk
```

### Phone prerequisites

- Android device with **arm64-v8a** ABI (virtually all phones made after 2016)
- Android 8.0 (API 26) or higher
- **USB debugging enabled**: Settings → About Phone → tap "Build Number" 7 times → Developer Options → USB Debugging ON
- USB cable connecting phone to this PC
- On first connection, approve the ADB authorization prompt on the phone

### Install via ADB (one command)

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Verify the device is recognized first:

```powershell
adb devices
# expected: one entry with "device" status, not "unauthorized"
```

If `adb` is not on PATH, use the full SDK path:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

After install, the app appears as **Phone RDP** in the launcher.

---

## Real-device verification script (click order)

Use this checklist end-to-end to verify the two Milestone-3 capabilities:
**① Certificate trust dialog** and **② Session disconnect push event**.

Open a logcat monitor in a separate PowerShell window before starting:

```powershell
adb logcat -s RdpBridge RecentRepo AndroidRuntime --format=time
```

### Step 0 — PC side setup (do once)

| # | Action | Expected |
|---|--------|----------|
| 0-1 | Open **Settings → System → Remote Desktop** on target Windows PC | "Enable Remote Desktop" toggle is ON |
| 0-2 | Confirm Windows Firewall allows **TCP 3389** inbound | Rule exists and is enabled |
| 0-3 | Confirm phone and PC are on the same Wi-Fi / reachable subnet | `ping <pc-ip>` from phone hotspot or router works |

### Step 1 — First connection & certificate trust dialog ✨ (new capability)

| # | Action | Expected logcat / UI |
|---|--------|----------------------|
| 1-1 | Launch **Phone RDP** app | Connection screen appears |
| 1-2 | Fill in: **Host** = `<PC IP>`, **Port** = `3389`, **Username** = `<Windows username>`, **Password** = `<Windows password>` | Fields accept input |
| 1-3 | Tap **Connect** | Status changes to `CONNECTING`; logcat shows `nativeConnect called` |
| 1-4 | Wait 2–5 s for TLS handshake | **Certificate Verification** dialog appears on screen |
| 1-5 | Read dialog: host, CN, fingerprint shown | logcat shows `CERTIFICATE_PROMPT event received` |
| 1-6 | Tap **Trust Once** | Dialog dismisses; logcat shows `Certificate accepted, requestId=…` |
| 1-7 | Status card shows `CONNECTED`; "Remote Surface" area renders desktop | logcat shows `freerdp_connect succeeded` |
| 1-8 | Tap the remote surface once | Cursor moves on remote PC (left click sent) |
| 1-9 | Long-press remote surface | Right-click context menu appears on remote PC |
| 1-10 | Type text in **Soft Keyboard** field, tap **Send** | Text appears in focused remote window |

> **Failure path to test:** repeat 1-1 → 1-4, then tap **Reject** instead.
> Expected: dialog dismisses, status shows `DISCONNECTED` with error note, reconnect button becomes active.

### Step 2 — Unexpected disconnect push event ✨ (new capability)

| # | Action | Expected logcat / UI |
|---|--------|----------------------|
| 2-1 | While connected (from Step 1), go to PC → **Win + L** (lock screen) OR disable the NIC / pull Ethernet | — |
| 2-2 | Within ~3 s on phone | Status card changes from `CONNECTED` → `DISCONNECTED`; a status note appears (e.g. "Session disconnected.") |
| 2-3 | Check logcat | Shows `SESSION_DISCONNECTED event: code=…` pushed from native event loop |
| 2-4 | Tap **Reconnect** | App re-attempts connection; reconnect attempt counter increments in status card |

### Step 3 — Recent connections persistence

| # | Action | Expected |
|---|--------|----------|
| 3-1 | Tap **Back** on session screen | Returns to connection screen |
| 3-2 | Verify the host you just connected to appears in **Recent** list | Up to 5 entries, newest first |
| 3-3 | Tap the recent item | Host, port, username fields are restored automatically |
| 3-4 | Do NOT enter password (it is restored from encrypted storage) | Password field shows placeholder `••••••` |
| 3-5 | Tap **Connect** again | Connection re-established using stored credentials |

---

## Build requirements

- Windows 10/11
- JDK 17
- Android SDK with:
  - platform `android-35`
  - build-tools `35.0.0`
  - CMake `3.22.1`
  - NDK `26.1.10909125` (recommended)
- ABI target: `arm64-v8a` (currently arm64 only)

## Native dependency note

FreeRDP native artifacts are consumed from:

- `app/src/main/jniLibs/arm64-v8a/libfreerdp3.so`
- `app/src/main/jniLibs/arm64-v8a/libfreerdp-client3.so`
- `app/src/main/jniLibs/arm64-v8a/libwinpr3.so`

If you need to rebuild these artifacts:

```powershell
.\scripts\build_freerdp_arm64.ps1
```

## Build and verify (only needed if you modify source)

```powershell
.\gradlew --version
.\gradlew assembleDebug
.\gradlew test
```

---

## Logcat filter reference

```powershell
# Core RDP bridge + encrypted storage (recommended baseline)
adb logcat -s RdpBridge RecentRepo --format=time

# Certificate and disconnect events only
adb logcat -s RdpBridge --format=time | findstr /I "CERTIFICATE SESSION_DISCONNECTED"

# Full verbose for deep debugging
adb logcat --format=time | findstr /I "RdpBridge RecentRepo freerdp"

# Crash / ANR watchdog
adb logcat -s AndroidRuntime ActivityManager --format=time
```

Common log patterns:

| Log snippet | Meaning |
|-------------|---------|
| `freerdp_connect succeeded` | Handshake completed, session active |
| `freerdp_connect failed: code=N` | Auth / TLS / network failure; N maps to `RdpNativeCodes` |
| `CERTIFICATE_PROMPT event received` | UI trust dialog is being shown |
| `Certificate accepted, requestId=…` | User tapped Trust Once |
| `SESSION_DISCONNECTED event: code=…` | Remote side closed or network lost |
| `Failed to initialize encrypted recent repository` | Keystore / security-crypto issue on this device |

---

## Directory Structure

```
phone_rdp/
├── app/
│   └── src/main/
│       ├── cpp/
│       │   ├── CMakeLists.txt
│       │   └── native_bridge.cpp        # JNI 桥接层，调用 FreeRDP
│       ├── java/com/example/phonerdp/
│       │   ├── MainActivity.kt
│       │   ├── data/repository/         # 加密最近连接存储
│       │   ├── domain/
│       │   │   ├── model/               # ConnectionConfig, RdpError, RdpConnectionStatus
│       │   │   ├── usecase/             # 连接/断开/最近连接用例
│       │   │   └── validation/          # 参数校验
│       │   ├── native_bridge/           # Kotlin JNI 封装 + 事件类型
│       │   └── ui/
│       │       ├── connection/          # 连接配置页
│       │       ├── session/             # 会话页（帧渲染 + 输入）
│       │       └── theme/
│       └── jniLibs/arm64-v8a/          # FreeRDP 预编译 .so 库
├── docs/
│   ├── ARCHITECTURE.md                 # 架构说明
│   ├── CHANGELOG.md                    # 变更记录
│   ├── FAQ.md                          # 常见问题
│   └── TROUBLESHOOTING.md              # 排障指南
├── scripts/
│   └── build_freerdp_arm64.ps1         # FreeRDP 原生库重建脚本
├── FINAL_SPEC.md                       # 最终规格归档
├── Prompt.md                           # 原始需求提示词归档
└── README.md
```

---

## Document Index

- 最终规格：[FINAL_SPEC.md](./FINAL_SPEC.md)
- 架构说明：[docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md)
- 常见问题：[docs/FAQ.md](./docs/FAQ.md)
- 排障指南：[docs/TROUBLESHOOTING.md](./docs/TROUBLESHOOTING.md)
- 变更记录：[docs/CHANGELOG.md](./docs/CHANGELOG.md)

---

## Known limitations

- Certificate trust is session-only; per-host persistent trust is not yet implemented.
- x86_64 native artifacts are not included (emulator not supported).
- Remote display resolution adjustment is not supported in current MVP.
- No file transfer or clipboard sync.

---

## License

MIT

This project uses [FreeRDP](https://github.com/FreeRDP/FreeRDP), which is licensed under the Apache License 2.0.
See [FreeRDP LICENSE](https://github.com/FreeRDP/FreeRDP/blob/master/LICENSE) for details.
