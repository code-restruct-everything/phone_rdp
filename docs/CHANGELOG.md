# Changelog

## v0.1.0 — 2026-04-17

### Milestone 3 — 数据安全与事件完善

#### Added
- 最近连接持久化：最近 5 条连接记录保存至 `EncryptedSharedPreferences`
- 密码加密存储：凭据通过 Android Keystore (`MasterKey`) 保护
- 证书信任对话框：TLS 握手时弹出 `Trust Once` / `Reject` 对话框
- 非预期断连推送：原生会话事件循环检测到断连后，通过 `pollEvent()` 主动推送 `SESSION_DISCONNECTED` 事件至 UI
- 重连计数器：会话状态卡片显示已尝试重连次数
- 状态备注与 Snackbar：连接失败时提供更细粒度的错误说明

#### Improved
- 最近连接列表项支持一键恢复完整连接配置（含 host/port/username/password）
- `RdpError.fromCode()` 覆盖更多错误码，提供人类可读的标题与详情
- 日志脱敏：不打印明文密码与完整凭据

---

### Milestone 2 — 渲染与输入

#### Added
- 远程画面渲染：GDI 帧更新在原生层捕获，帧元数据轮询 + 像素拷贝至 Android `Bitmap`，Compose 展示
- 手势输入映射：单击→左键、长按→右键、双指拖动→滚轮、双指缩放→本地视图缩放
- 软键盘文字通道：文本通过 Unicode input channel 发送至远端

---

### Milestone 1 — 连接链路

#### Added
- FreeRDP JNI 最小链路：`freerdp_connect` 握手、会话事件循环、`freerdp_disconnect`
- TLS/NLA/认证/网络错误码映射至 `RdpNativeCodes` 与 `RdpError`
- 会话页连接状态展示

---

### Milestone 0 — 脚手架

#### Added
- 工程结构与模块分层（ui / domain / data / native_bridge）
- Gradle Kotlin DSL + CMake 3.22.1 + NDK 26.1.10909125 构建基线
- 连接页、会话页基础 Compose 骨架
- FreeRDP arm64-v8a 预编译共享库接入

### Commits（里程碑代表性提交）

- `chore: init project scaffold with gradle kotlin dsl`
- `feat: freerdp jni minimum connection path`
- `feat: gdi frame rendering + gesture input`
- `feat: encrypted recent connections + keystore`
- `feat: certificate trust dialog + session disconnect push`
- `docs: full documentation set for github`
