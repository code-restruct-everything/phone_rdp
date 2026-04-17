# FAQ

本文回答项目中最常见的问题，优先给出可直接执行的结论。  
如果你遇到连接/构建异常，请同时查看 [TROUBLESHOOTING.md](./TROUBLESHOOTING.md)。

## 1. 这个项目支持哪些 Android 设备？

当前仅支持 `arm64-v8a`，最低 Android 版本为 **8.0（API 26）**。  
`x86/x86_64` 模拟器暂不在支持范围内。

## 2. 支持哪些 Windows 版本作为被控端？

支持开启远程桌面（RDP Host）的 Windows 版本，例如：

- Windows 10/11 专业版
- Windows 10/11 企业版

说明：Windows 家庭版默认不能作为 RDP 被控端。

## 3. 首次连接弹出证书确认窗口，正常吗？

正常。首次连接或自签名证书场景下，会出现证书确认。

- `Trust Once`：本次连接继续
- `Reject`：拒绝并断开

## 4. 密码是怎么保存的？会明文落盘吗？

不会。密码通过 `EncryptedSharedPreferences` 保存，并由 Android Keystore 保护密钥。  
默认日志也不会输出明文密码。

## 5. 怎么清空最近连接记录？

当前 MVP 没有“单条删除”UI。可用临时方案：

1. 系统设置 -> 应用 -> Phone RDP
2. 进入存储
3. 选择“清除数据”

这会同时清掉最近连接与本地加密凭据。

## 6. 连接成功但黑屏/卡住怎么办？

优先做这 3 步：

1. 等待 3-5 秒（首帧可能延迟）
2. 在远端 Windows 上移动鼠标触发刷新
3. 用下面命令看日志：

```powershell
adb logcat -s RdpBridge --format=time
```

详细处理见 [TROUBLESHOOTING.md](./TROUBLESHOOTING.md) 的“画面不显示”章节。

## 7. 软键盘输入后远端没反应怎么办？

先确认：

1. 会话状态是 `CONNECTED`
2. 远端窗口有焦点（例如记事本光标在输入框里）
3. 点击了 `Send`

详细处理见 [TROUBLESHOOTING.md](./TROUBLESHOOTING.md) 的“输入无效”章节。

## 8. 为什么只有本地缩放，没有远端分辨率调整？

这是 MVP 的范围控制：当前只做本地视图缩放，优先保证连接、显示、输入和稳定性。

## 9. 如何从源码构建？

在项目根目录执行：

```powershell
.\gradlew --version
.\gradlew assembleDebug
.\gradlew test
```

若失败，请先看 [TROUBLESHOOTING.md](./TROUBLESHOOTING.md) 的“构建失败”章节。

## 10. 为什么我在不同终端构建结果不一致？

推荐仅用 PowerShell，并优先使用 Android SDK 自带的 CMake/Ninja。  
不要混用 MSYS2/Conda 的同名工具链。

## 11. 上传到 GitHub 后，如何创建 Release？

下面是建议的标准流程。

### 第 1 步：确认发布内容

1. 工作区干净：

```powershell
git status
```

2. 把需要发布的变更提交到 `main`
3. 更新变更记录（建议更新 `docs/CHANGELOG.md`）

### 第 2 步：创建并推送版本 Tag

示例发布 `v0.1.0`：

```powershell
git checkout main
git pull

git tag -a v0.1.0 -m "Release v0.1.0"
git push origin main
git push origin v0.1.0
```

说明：建议使用 `v主版本.次版本.补丁版本`，例如 `v0.1.0`、`v0.1.1`。

### 第 3 步：在 GitHub 页面创建 Release

1. 打开仓库页面 -> `Releases`
2. 点击 `Draft a new release`
3. 选择刚推送的 Tag（如 `v0.1.0`）
4. 填写标题（如 `v0.1.0`）
5. 填写说明（可引用 `CHANGELOG`）
6. 可选：上传构建产物（例如 `app-debug.apk`）
7. 点击 `Publish release`

### 第 4 步：发布后自检

1. 确认 `Releases` 页面可见新版本
2. 下载附件验证可用性（如果上传了 APK）
3. 团队内同步版本号与更新说明

## 12. 如果 Tag 打错了，怎么修正？

若尚未被他人使用，可以删除并重建：

```powershell
git tag -d v0.1.0
git push origin :refs/tags/v0.1.0

# 重新打正确标签
git tag -a v0.1.0 -m "Release v0.1.0"
git push origin v0.1.0
```

注意：已被外部使用的 Tag 不建议强改，应新发一个补丁版本（如 `v0.1.1`）。

## 13. 我本地有很多 commit，但推到 GitHub 只想保留 1 个初始 commit，怎么做？

可以。推荐用“无历史分支（orphan）”方式，最稳妥。

### 目标

- 本地开发时可以有很多 commit
- 最终推送到 GitHub 时，只显示 1 个初始提交（当前最终代码状态）

### 操作步骤（可直接复制）

```powershell
# 0) 确保你当前工作区是最终状态
git status

# 1) 备份当前完整历史（强烈建议）
git branch backup/full-history

# 2) 从当前代码状态创建“无历史”分支
git switch --orphan main_clean

# 3) 提交当前全部文件为一个初始提交
git add -A
git commit -m "Initial commit"

# 4) 把这个分支改名为 main
git branch -M main

# 5) 推送
# 远端是空仓库：
git push -u origin main

# 若远端已有旧历史，需要覆盖：
# git push -u origin main --force-with-lease
```

说明：

- `--force-with-lease` 会改写远端历史，团队协作前请先沟通。
- 你的旧历史仍在 `backup/full-history` 分支里，不会丢。

## 14. 备份分支要不要删？怎么删？

不必须删。建议先保留一段时间，确认线上版本稳定后再删除。

### 删除本地备份分支

```powershell
# 查看当前分支（不要在要删的分支上）
git branch

# 若分支已合并可用 -d；未合并请用 -D
git branch -D backup/full-history
```

### 删除远端备份分支（如果你曾推送过）

```powershell
git push origin --delete backup/full-history
```

### 先检查是否存在该分支（可选）

```powershell
git branch --list backup/full-history
git branch -r | findstr backup/full-history
```
