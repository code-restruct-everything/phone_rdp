# FAQ

## 1. 支持哪些 Android 设备？

当前只提供 `arm64-v8a` ABI，适用于 2016 年以后发布的绝大多数 Android 手机。  
最低系统版本要求：Android 8.0（API 26）。  
x86_64（模拟器）的原生库暂未包含。

## 2. 支持哪些 Windows 版本？

任何启用了"远程桌面"功能的 Windows 版本均可连接，包括：

- Windows 10 专业版 / 企业版
- Windows 11 专业版 / 企业版

> Windows 家庭版默认不支持被 RDP 连接（被控端），可通过第三方补丁开启，但超出本项目支持范围。

## 3. 第一次连接时出现证书验证弹窗，正常吗？

正常。RDP 使用 TLS 加密传输，服务端会发送证书。当证书为自签名证书（Windows 默认）或首次连接时，应用会弹出对话框显示证书详情，用户可选择 **Trust Once**（本次会话信任）或 **Reject**（拒绝并断开）。

## 4. 密码存储在哪里？安全吗？

密码存储在 `EncryptedSharedPreferences` 中，由 Android Keystore 管理的 AES-GCM 主密钥保护，密钥材料存储在安全芯片中，**不会以明文形式写入磁盘**。日志也不会打印明文密码。

## 5. 如何删除已保存的连接记录？

当前 MVP 版本不提供单条删除 UI。临时方案：  
通过系统设置 → 应用 → Phone RDP → 存储 → 清除数据，可清空所有记录（同时清除密码）。

## 6. 连接成功后画面卡顿/黑屏怎么办？

- 确认手机和目标 PC 在同一局域网或有稳定的路由可达性
- 参考 [TROUBLESHOOTING.md](./TROUBLESHOOTING.md) C 节进行排查
- 使用 `adb logcat -s RdpBridge --format=time` 观察帧渲染日志

## 7. 软键盘输入后对方没收到？

- 点击 **Send** 按键后检查 Session 页底部的 `Input code`，若为负数表示发送失败
- 确认 RDP 会话状态为 `CONNECTED`，未处于重连或断线状态
- 参考 [TROUBLESHOOTING.md](./TROUBLESHOOTING.md) D 节

## 8. 为什么只有"本地缩放"，没有"远端分辨率调整"？

当前 MVP 的设计目标是快速验证连通性，缩放仅改变本地 Compose 视图的 `scale`，不向远端发送 display update 请求。远端分辨率适配列入后续里程碑规划。

## 9. 支持多显示器吗？

不支持。当前版本固定连接主显示器（FreeRDP 默认行为），多显示器场景不在 MVP 范围内。

## 10. 如何从源码构建？

参见 README 的 **Build and verify** 章节，需要 JDK 17、Android SDK 35、NDK 26.1.10909125。
