# Troubleshooting（排障指南）

这份文档按“先定位、后修复”的顺序组织。  
建议先看 **0. 快速定位**，再按问题类型跳转。

## 0. 快速定位（30 秒）

1. 确认设备和 PC 网络可达（同网段或路由可达）
2. 先看应用状态文案与错误码
3. 拉日志定位：

```powershell
adb logcat -s RdpBridge RecentRepo AndroidRuntime --format=time
```

---

## 1. APK 安装失败

### 症状

- `INSTALL_FAILED_NO_MATCHING_ABIS`
- `INSTALL_PARSE_FAILED_NO_CERTIFICATES`

### 常见原因与处理

| 原因 | 处理方法 |
|---|---|
| 设备 ABI 不是 `arm64-v8a` | 当前版本仅支持 arm64，换真机测试 |
| `adb devices` 显示 `unauthorized` | 在手机上确认 USB 调试授权弹窗 |
| 安装签名冲突 | 使用 `adb install -r` 或先卸载旧包 |

命令：

```powershell
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## 2. 连接失败（状态变为 DISCONNECTED）

### 先看错误码

| 错误标题 | 错误码 | 常见原因 | 处理方法 |
|---|---:|---|---|
| Network Failure | -1201 | IP 不可达、防火墙拦截、3389 未开放 | `ping <host>`；检查 Windows 防火墙入站规则 |
| Authentication Failed | -1301 | 用户名/密码错误 | 核对账户、域、RDP 权限 |
| TLS/NLA Failure | -1401 | NLA 协商失败或证书策略冲突 | 检查 Windows 远程桌面安全策略 |
| Certificate Rejected | -1402 | 手动点了 Reject | 重连并选择 Trust Once |
| Invalid Parameters | -1101 | host/port 参数不合法 | 检查 host 空格、port 范围（1-65535） |
| RDP Backend Unavailable | -1501 | Native 库未加载 | 确认 arm64 设备并重装 APK |

日志命令：

```powershell
adb logcat -s RdpBridge --format=time
```

---

## 3. 连接成功但画面不显示（黑屏/一直等待首帧）

按顺序检查：

1. 等待 3-5 秒（首帧初始化可能延迟）
2. 在远端 Windows 上移动鼠标或切换窗口，触发刷新
3. 查看是否持续出现无帧日志：
   - `nativeGetFrameInfo: no frame`
   - `copyFrameToBitmap: buffer empty`
4. 断开后重连再观察

---

## 4. 输入无效（软键盘发送后没反应）

### 现象

- Input code 为负数
- 远端没有收到文本

### 处理步骤

1. 确认连接状态为 `CONNECTED`
2. 确认远端窗口有焦点
3. 再次点击 `Send`
4. 查看日志中是否有输入异常

参考错误码：

| 负码 | 含义 | 处理方法 |
|---:|---|---|
| -1701 | INPUT_FAILURE | 连接尚未稳定，稍后重试 |
| -1999 | INTERNAL_ERROR | 检查 `AndroidRuntime` 日志 |

---

## 5. 会话意外断开

### 常见触发

- 远端主机锁屏或会话策略中断
- 网络抖动
- 远端重启

### 处理建议

1. 查看日志中的 `SESSION_DISCONNECTED event: code=...`
2. 点击 `Reconnect` 尝试恢复
3. 若频繁发生，优先排查 Wi-Fi 质量与远端策略

---

## 6. 重连后认证失败（密码似乎不对）

可能原因：

- Windows 密码已变更
- 本地加密存储异常（低概率）

处理方法：

1. 回到连接页手动更新密码
2. 若仍失败，清除应用数据后重新录入

---

## 7. 构建失败（改代码后无法编译）

### 常见问题

#### `Could not find cmake version 3.22.1`

确认在 Android SDK Manager 安装了 CMake 3.22.1。

#### `NDK not found for ABI arm64-v8a`

确认安装 NDK `26.1.10909125`。

#### `Execution failed for task ':app:compileDebugKotlin'`

确认 `java -version` 是 17.x。

#### `ninja: error: ... MSYS / conda ...`

请避免混用 MSYS2/Conda 工具链，优先使用 Android SDK 自带 CMake/Ninja。

建议完整重建：

```powershell
.\gradlew clean assembleDebug
```

---

## 8. GitHub Release 创建相关问题

### 8.1 页面看不到 `Draft a new release`

- 你可能没有仓库写权限
- 或仓库是受保护组织策略

处理：确认你在该仓库的角色至少是 `Write`。

### 8.2 选不到你刚推送的 Tag

先检查本地是否真的推送：

```powershell
git tag --list
git ls-remote --tags origin
```

如果本地有、远端没有：

```powershell
git push origin <tag>
```

### 8.3 Release 挂错了提交

- 重新检查 Tag 指向：

```powershell
git show <tag>
```

- 如果确认打错，参考 FAQ 的“Tag 修正”流程处理

### 8.4 上传 APK 后用户反馈无法安装

优先检查：

1. 是否是 arm64 设备
2. 是否是调试包签名冲突
3. 是否缺少必要权限或系统版本过低

---

## 9. 仍无法解决时请带上这些信息

提 Issue 或内部协作时，建议附上：

1. 设备型号、Android 版本
2. Windows 版本与网络环境
3. 复现步骤（尽量可重复）
4. 关键日志（至少含 `RdpBridge`）
5. 当前分支与最近提交哈希
