# Architecture Overview

## Layer Structure

```
┌──────────────────────────────────────────────┐
│                  UI Layer                    │
│  ConnectionScreen  ·  SessionScreen          │
│  AppRoot (NavController)  ·  Theme           │
└────────────────────┬─────────────────────────┘
                     │ ViewModel / state hoisting
┌────────────────────▼─────────────────────────┐
│                Domain Layer                  │
│  ConnectionConfig  ·  RdpConnectionStatus    │
│  RdpError          ·  Use Cases              │
│    ConnectRdpUseCase  DisconnectRdpUseCase   │
│    UpsertRecentConnectionUseCase             │
│    RecentConnectionLimitUseCase              │
│  ConnectionConfigValidator                   │
└──────────┬─────────────────┬─────────────────┘
           │                 │
┌──────────▼──────┐  ┌───────▼─────────────────┐
│  Native Bridge  │  │       Data Layer         │
│  RdpNativeBridge│  │  RecentConnectionRepo    │
│  NativeBridgeEv.│  │  EncryptedRecentConn.    │
│  RdpNativeCodes │  │  InMemoryRecentConn.     │
└──────────┬──────┘  └─────────────────────────┘
           │ JNI
┌──────────▼──────────────────────────────────┐
│           Native Layer (C++)                │
│  native_bridge.cpp                          │
│    freerdp_connect / freerdp_disconnect     │
│    GDI frame capture → shared buffer        │
│    pointer / keyboard event injection       │
│    certificate callback → event queue       │
│    session disconnect → event queue         │
├─────────────────────────────────────────────┤
│  FreeRDP shared libraries (arm64-v8a)       │
│    libfreerdp3.so                           │
│    libfreerdp-client3.so                    │
│    libwinpr3.so                             │
└─────────────────────────────────────────────┘
```

## Key Components

### `RdpNativeBridge` (Kotlin singleton)
- 加载 `librdpbridge.so`，对外暴露类型安全的 Kotlin API
- 所有 JNI 调用包裹在 `runCatching` 中，避免未处理的 native 异常
- `pollEvent()` 轮询原生事件队列，返回 JSON → 解析为 `NativeBridgeEvent` sealed class

### `native_bridge.cpp`
- 维护单一 `RdpContext`（全局 mutex 保护）
- 连接事件循环运行在独立线程中（`std::thread`）
- 证书回调通过条件变量阻塞，等待 Kotlin 层的 `submitCertificateDecision` 调用
- 帧数据写入 `std::vector<uint8_t>` 共享缓冲区，序列号自增（无锁读，有锁写）

### `SessionScreen` (Compose)
- 以 `LaunchedEffect` 协程循环轮询帧元数据（约 30 fps，delay 33 ms）
- 帧变化时通过 `nativeCopyFrameToBitmap` 将像素拷贝到 Android `Bitmap`
- 手势层：`detectTapGestures`（单击/长按）+ `detectTransformGestures`（双指缩放/滚轮）
- 本地坐标 → 远端坐标的映射通过归一化比例实现

## Data Flow

```
用户输入连接配置
      ↓
ConnectionConfigValidator 校验
      ↓
ConnectRdpUseCase
      ↓
RdpNativeBridge.connect()  ──JNI──▶  freerdp_connect()
                                            ↓
                                    TLS 握手 / NLA 认证
                                            ↓ certificate callback
                                     native 事件队列入队
                                            ↓
RdpNativeBridge.pollEvent()  ◀──────  CERTIFICATE_PROMPT
      ↓                                     
UI 弹出证书对话框
      ↓ Trust Once / Reject
RdpNativeBridge.submitCertificateDecision()
      ↓
                                    freerdp_connect 继续
                                            ↓
                                    会话事件循环（独立线程）
                                       GDI 帧更新 → buffer
                                            ↓
SessionScreen 轮询帧 ◀──────  nativeCopyFrameToBitmap
      ↓
Android Bitmap → Compose Image
```

## Storage

| 存储 | Key | 内容 |
|------|-----|------|
| `EncryptedSharedPreferences` | `recent_connections` | 最近 5 条 `ConnectionConfig`（JSON 序列化），密码加密 |
| Android Keystore | `_androidx_security_master_key_` | AES-GCM 主密钥，由 `MasterKey` 管理 |

## Thread Model

| 线程 | 职责 |
|------|------|
| Main（UI）| Compose 渲染、状态更新 |
| IO Dispatcher | JNI connect / disconnect / frame copy 调用 |
| Native event loop thread | FreeRDP 会话事件循环，写帧缓冲，写事件队列 |
