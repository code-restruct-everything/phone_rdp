# Final Spec (MVP)

## Metadata

- Project: Phone RDP
- Scope: Android RDP Client
- Platform: Android arm64-v8a
- Status: Milestone 3 Completed (MVP)
- Version: 0.1.0
- Last Updated: 2026-04-17

## Product Positioning

一个面向 Android 手机的 RDP 客户端 MVP：  
通过 FreeRDP 接入真实 RDP 协议，让手机像 Windows 远程桌面客户端一样连接并操控远端 PC，支持画面渲染、手势输入、软键盘、安全存储和断连恢复。

## Goals

1. 可输入并保存连接配置（host / port / username / password / domain）。
2. 可建立 / 断开 RDP 会话（TLS/NLA 兼容）。
3. 连接后可显示远程桌面画面（实时帧渲染，约 30 fps）。
4. 支持基础输入映射：单击、长按、双指滚轮、软键盘文本。
5. 证书验证交互：弹窗让用户决策 Trust Once / Reject。
6. 连接失败 / 断连有可读错误提示，支持一键重连。
7. 最近 5 条连接持久化，密码加密存储（Android Keystore）。

## Non-Goals (Current MVP)

- 不做远端分辨率动态调整
- 不做多显示器支持
- 不做剪贴板双向同步
- 不做文件传输（RDP 驱动重定向）
- 不做 x86 / x86_64 支持
- 不支持 iOS 平台
- 不支持 VNC 协议

## Implemented Functional Scope

### 1) Connection Management

- 参数校验：host 非空、port 1–65535、username 非空
- 连接状态机：`IDLE → CONNECTING → CONNECTED → DISCONNECTING → DISCONNECTED`
- 连接超时与错误码分类（见 `RdpNativeCodes`）

### 2) Remote Frame Rendering

- 原生层 GDI 帧捕获，写入共享缓冲区，序列号自增
- Kotlin 层以 33ms 轮询帧元数据，检测到新帧后通过 `nativeCopyFrameToBitmap` 拷贝像素
- Compose `Image` 展示当前帧；本地双指缩放（1x–3x）

### 3) Input Mapping

- 单击 → 左键点击（相对坐标归一化）
- 长按 → 右键点击
- 双指拖动（pan.y）→ 滚轮事件，累积阈值 24px 发送一次
- 软键盘文本 → Unicode input channel（通过 FreeRDP freerdp_input_send_unicode_char）

### 4) Certificate Trust Dialog

- TLS 握手中的证书回调通过条件变量阻塞原生线程
- 证书信息（host / CN / issuer / fingerprint / changed 标记）序列化为 JSON 推送至 Kotlin
- Compose 弹窗展示证书详情，用户选择 Trust Once 或 Reject
- 决策结果通过 `submitCertificateDecision` 回传至原生层解除阻塞

### 5) Session Disconnect Push

- 会话事件循环线程检测到 `freerdp_shall_disconnect` 后入队 `SESSION_DISCONNECTED` 事件
- UI 轮询 `pollEvent()` 后主动更新状态为 `DISCONNECTED` 并显示断连原因

### 6) Secure Recent Connections

- 使用 `EncryptedSharedPreferences`（AES256-GCM 加密，Keystore 主密钥）
- 最多保存 5 条记录，超出时移除最旧项
- 最近连接列表支持一键恢复完整配置（含加密密码）

## Data Models

### `ConnectionConfig`

| 字段 | 类型 | 说明 |
|------|------|------|
| `host` | String | 目标主机 IP 或域名 |
| `port` | Int | RDP 端口，默认 3389 |
| `username` | String | Windows 账户名 |
| `password` | String | 密码（不落明文磁盘） |
| `domain` | String? | 域名，可选 |

### `RdpNativeCodes`（错误码）

| 码值 | 含义 |
|------|------|
| 0 | OK |
| 1 | ALREADY_CONNECTED |
| 2 | NOT_CONNECTED |
| -1101 | INVALID_ARGUMENT |
| -1201 | NETWORK_FAILURE |
| -1301 | AUTH_FAILURE |
| -1401 | TLS_FAILURE |
| -1402 | CERTIFICATE_REJECTED |
| -1501 | BACKEND_UNAVAILABLE |
| -1601 | FRAME_UNAVAILABLE |
| -1701 | INPUT_FAILURE |
| -1999 | INTERNAL_ERROR |

## Acceptance Checklist

- [x] 可连接（freerdp_connect 成功）
- [x] 可显示（远程帧渲染至 Compose）
- [x] 可输入（手势 + 软键盘）
- [x] 可断开（graceful disconnect）
- [x] 可重连（一键 Reconnect + 计数器）
- [x] 最近连接可恢复（EncryptedSharedPreferences 持久化）
- [x] 密码加密存储（Android Keystore）
- [x] Debug 包可构建成功（`.\gradlew assembleDebug`）

## Known Risks

1. FreeRDP arm64 so 库为预编译二进制，如需重建需执行 `scripts/build_freerdp_arm64.ps1`。
2. 证书信任当前为"本次会话"级别，不持久化到磁盘。
3. x86_64（Android 模拟器）暂无原生库，无法在模拟器上运行。
4. 帧渲染帧率受 GDI 刷新频率限制，对动态内容（视频）体验有限。

## Next Milestones (Suggested)

1. 持久化证书信任（per-host 指纹存储）
2. 远端分辨率适配（发送 display update 请求）
3. 剪贴板双向同步
4. x86_64 原生库支持（模拟器调试）
5. 连接配置编辑与删除 UI
