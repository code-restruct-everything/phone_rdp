# Troubleshooting

## A. `adb install` 失败

**症状**：`INSTALL_FAILED_NO_MATCHING_ABIS` 或 `INSTALL_PARSE_FAILED_NO_CERTIFICATES`

| 原因 | 解决方法 |
|------|---------|
| 设备 ABI 不是 arm64-v8a | 当前仅支持 arm64，x86 / x86_64 模拟器不可用 |
| 设备未授权 | `adb devices` 确认状态为 `device` 而非 `unauthorized`；在手机上同意授权弹窗 |
| APK 签名问题 | 使用 `adb install -r`（允许覆盖安装）或先卸载再安装 |

---

## B. 连接失败（Status: DISCONNECTED，有错误提示）

**先查错误标题**，对照下表排查：

| 错误标题 | 错误码 | 常见原因 | 处理方法 |
|---------|--------|---------|---------|
| Network Failure | -1201 | IP 不可达 / 防火墙拦截 / 端口未开放 | `ping <host>`；检查 Windows 防火墙 TCP 3389 规则 |
| Authentication Failed | -1301 | 用户名或密码错误 | 确认 Windows 账户名（含域）；检查账户是否有 RDP 权限 |
| TLS/NLA Failure | -1401 | NLA 协商失败 / 证书策略问题 | 尝试在 Windows 端关闭"仅允许使用 NLA"设置；或检查证书 |
| Certificate Rejected | -1402 | 用户在弹窗中点击了 Reject | 重新连接并选择 Trust Once |
| Invalid Parameters | -1101 | host/port 格式错误 | 检查 host 是否含多余空格；port 是否为 1–65535 |
| RDP Backend Unavailable | -1501 | FreeRDP so 库未加载 | 确认设备为 arm64-v8a；重新安装 APK |

**logcat 快速定位**：

```powershell
adb logcat -s RdpBridge --format=time
```

---

## C. 连接成功但画面不显示（黑屏 / "Waiting for first remote frame..."）

1. 等待 3–5 秒：首帧需要 GDI 初始化，可能有延迟。
2. 检查 logcat 中是否持续出现 `nativeGetFrameInfo: no frame` 或 `copyFrameToBitmap: buffer empty`。
3. 在远端 PC 移动鼠标或操作窗口，触发 GDI 刷新。
4. 若仍无帧，尝试断开重连；检查远端 GDI 是否被禁用（部分 RDP 策略会关闭 GDI）。

---

## D. 软键盘输入无效（Input code 为负数）

| 负码 | 含义 | 处理方法 |
|------|------|---------|
| -1701 | INPUT_FAILURE | 会话仍在连接中，等待 `CONNECTED` 后再发送 |
| -1999 | INTERNAL_ERROR | JNI 层崩溃，查看 logcat 中 `AndroidRuntime` 标签 |

- 确认输入框中有文本后再点 **Send**。
- 确认远端 PC 有活动窗口获得了焦点（如记事本）。

---

## E. 会话意外断开（未操作就变为 DISCONNECTED）

1. 检查 logcat：`SESSION_DISCONNECTED event: code=…`，记录错误码。
2. 常见原因：
   - 远端 PC 锁屏（需重新认证） → 点 **Reconnect**
   - Wi-Fi 信号不稳定 → 确认网络后重连
   - 远端 PC 重启 → 等待 PC 启动后重连
3. 在 Session 页状态卡片中观察 `statusNote` 字段获取详情。

---

## F. 重连后密码不正确（需重新输入）

当前 MVP 重连流程从最近连接中读取已加密的密码并自动使用。若弹出认证失败，可能是：

- Windows 账户密码已更改 → 返回连接页手动编辑并重新连接
- Keystore 数据损坏（极少见）→ 清除应用数据后重新输入

---

## G. 构建失败（源码修改后）

**常见错误与解决**：

```
CMake Error: Could not find cmake version 3.22.1
```
→ 确认 Android SDK CMake 3.22.1 已安装：SDK Manager → SDK Tools → CMake

```
NDK not found for ABI arm64-v8a
```
→ 确认 NDK 26.1.10909125 已安装：SDK Manager → SDK Tools → NDK (Side by side)

```
Execution failed for task ':app:compileDebugKotlin'
```
→ 确认 JDK 版本：`java -version` 应输出 17.x

```
ninja: error: ... MSYS / conda cmake
```
→ 参见 `Prompt.md` 零节：必须使用 SDK CMake 绝对路径，禁止 MSYS2 / Conda 工具链

**完整重建命令**：

```powershell
.\gradlew clean assembleDebug
```
