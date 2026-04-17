# Phone RDP

一个面向 Android 的轻量 RDP 客户端 MVP，基于 **Kotlin + Jetpack Compose + JNI/NDK + FreeRDP**。  
目标是：在手机上快速连接 Windows 远程桌面，完成基础查看与输入操作。

## 目录

- [项目亮点](#项目亮点)
- [当前状态](#当前状态)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [Windows 环境准备（建议）](#windows-环境准备建议)
- [连接与使用流程](#连接与使用流程)
- [真机验证清单](#真机验证清单)
- [常用日志命令](#常用日志命令)
- [目录结构](#目录结构)
- [文档索引](#文档索引)
- [已知限制](#已知限制)
- [License](#license)

## 项目亮点

- 基于 FreeRDP 的真实连接链路（非 mock）
- 支持连接/断开/重连，状态与错误可读
- 支持远端桌面帧渲染到 Compose 界面
- 支持基础输入映射：
  - 单击：左键
  - 长按：右键
  - 双指拖动：滚轮
  - 软键盘文本发送
- 最近连接最多保存 5 条
- 密码通过 Android Keystore + EncryptedSharedPreferences 加密保存
- 支持证书确认弹窗（Trust Once / Reject）
- 支持会话异常断开事件上推到 UI

## 当前状态

| 项目 | 状态 | 说明 |
|---|---|---|
| Android 真机连接 | 已实现 | arm64-v8a |
| 远端画面显示 | 已实现 | GDI 帧复制到 Bitmap |
| 基础输入映射 | 已实现 | 点击/长按/滚动/文本 |
| 最近连接与加密存储 | 已实现 | 最多 5 条 |
| x86_64 模拟器支持 | 未提供 | 当前仅 arm64-v8a |

## 技术栈

- UI：Kotlin + Jetpack Compose
- 业务层：Kotlin（分层：`ui/domain/data/native_bridge`）
- Native：C++17 + JNI + FreeRDP
- 构建：Gradle Kotlin DSL + CMake + NDK

## 快速开始

### 1. 环境要求

- Windows 10/11
- JDK 17
- Android SDK：
  - `android-35`
  - `build-tools 35.0.0`
  - `CMake 3.22.1`
  - `NDK 26.1.10909125`
- Android 设备：`arm64-v8a`，Android 8.0+，开启 USB 调试

### 2. 编译

在项目根目录执行：

```powershell
.\gradlew --version
.\gradlew assembleDebug
.\gradlew test
```

### 3. 安装 APK（真机）

```powershell
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

如果 `adb` 不在 PATH：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

## Windows 环境准备（建议）

为避免 NDK/CMake 工具链冲突，建议只在 PowerShell 下执行构建，并优先使用 Android SDK 自带工具：

- `CMake`: `C:\Users\<you>\AppData\Local\Android\Sdk\cmake\3.22.1\bin\cmake.exe`
- `Ninja`: `C:\Users\<you>\AppData\Local\Android\Sdk\cmake\3.22.1\bin\ninja.exe`

若你遇到 `ninja: error` 或 CMake 混用问题，请看排障文档中的对应章节。

## 连接与使用流程

1. 打开应用，填写 `Host/Port/Username/Password`（可选 Domain）
2. 点击 `Connect`
3. 首次连接可能出现证书确认弹窗：
   - `Trust Once`：继续连接
   - `Reject`：终止连接
4. 连接成功后进入会话页，进行点击/长按/滚动/文本输入
5. 异常断开后可点击 `Reconnect` 快速重连
6. 返回连接页可从 `Recent` 一键恢复最近配置

## 真机验证清单

建议至少验证以下 8 项：

1. 能成功连接远程 Windows
2. 会话页可显示远端桌面
3. 单击/长按/滚动可生效
4. 软键盘发送文本可生效
5. 主动断开可回到可连接状态
6. 异常断开时 UI 能收到断连事件
7. 最近连接可恢复（含密码）
8. 重新安装后仍可正常连接

## 常用日志命令

```powershell
# 推荐：核心标签
adb logcat -s RdpBridge RecentRepo --format=time

# 证书与断连事件
adb logcat -s RdpBridge --format=time | findstr /I "CERTIFICATE SESSION_DISCONNECTED"

# 崩溃排查
adb logcat -s AndroidRuntime ActivityManager --format=time
```

常见日志含义：

- `freerdp_connect succeeded`：连接成功
- `freerdp_connect failed: code=N`：连接失败（认证/TLS/网络）
- `CERTIFICATE_PROMPT event received`：证书确认弹窗触发
- `SESSION_DISCONNECTED event: code=...`：会话被远端或网络中断

## 目录结构

```text
phone_rdp/
├─ app/
│  └─ src/main/
│     ├─ cpp/                          # JNI 与 FreeRDP 桥接
│     ├─ java/com/example/phonerdp/    # Kotlin 主体代码
│     │  ├─ data/
│     │  ├─ domain/
│     │  ├─ native_bridge/
│     │  └─ ui/
│     └─ jniLibs/arm64-v8a/            # 预编译 FreeRDP 相关库
├─ docs/
│  ├─ ARCHITECTURE.md
│  ├─ CHANGELOG.md
│  ├─ FAQ.md
│  └─ TROUBLESHOOTING.md
├─ scripts/
│  └─ build_freerdp_arm64.ps1
├─ FINAL_SPEC.md
├─ ORIGINAL_PROMPT.md
├─ LICENSE
└─ README.md
```

## 文档索引

- 最终规格：[FINAL_SPEC.md](./FINAL_SPEC.md)
- 架构说明：[docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md)
- 常见问题：[docs/FAQ.md](./docs/FAQ.md)
- 发布流程：见 [docs/FAQ.md](./docs/FAQ.md) 的“上传到 GitHub 后，如何创建 Release？”
- 排障指南：[docs/TROUBLESHOOTING.md](./docs/TROUBLESHOOTING.md)
- 变更记录：[docs/CHANGELOG.md](./docs/CHANGELOG.md)
- 原始需求归档：[ORIGINAL_PROMPT.md](./ORIGINAL_PROMPT.md)

## 已知限制

- 当前证书信任为会话级（未做按主机永久信任）
- 当前仅提供 `arm64-v8a`，不支持 x86_64 模拟器
- 暂不支持剪贴板同步与文件传输
- 暂不支持远端分辨率动态调整

## License

MIT（见仓库根目录 [LICENSE](./LICENSE)）。  
本项目依赖 [FreeRDP](https://github.com/FreeRDP/FreeRDP)（Apache License 2.0）。
