# MiniFeiQ

模拟协议，可以与 FeiQ2013 通信。

## 结构

组件|技术|作用|是否必须
--|--|--|--
WPF 客户端|.NET 10 + WPF|Windows 端，兼容飞秋2013 + 互联网模式|必须
Kotlin 客户端|Android|另一端，同样走抽象传输层|必须
中继服务|.NET Console（或随便什么）|互联网模式下的信令/消息/文件中转|只有要互联网时才需要


## 扩展方向

- [x] 消息持久化（SQLite）
- [x] 头像设置（base64 随 UDP 包广播）
- [x] 镜像改名 minifeiq-relay 👉 feiq2026-relay
- [x] ci 和 Android & WPF Version 注入关联
- [x] 图片、音频、视频：图片&视频独立 MediaViewer（ExoPlayer）；音频 Chat 内点播
- [ ] 长按语音发送
- [ ] 实时童话、会议（WebRTC）
- [ ] 消息通知（Windows Toast / Android Notification）
- [ ] 图片压缩（大图走文件通道）
- [ ] 传输加密（TLS）
- [ ] 群组广播消息
- [ ] macOS/Linux 客户端
- [ ] CA证书 或 [微软开发者登记](https://www.microsoft.com/en-us/wdsi/filesubmission)

---

## 注意事项

1. **防火墙**：Windows 首次运行会弹出防火墙提示，需允许 UDP 52000 / TCP 52001
2. **同一子网**：两端需在同一 Wi-Fi / 局域网下
3. **Android Wi-Fi**：确保 Wi-Fi 已连接，需 `CHANGE_WIFI_MULTICAST_STATE` 权限
4. **大文件**：文件通过流式 TCP 传输，理论上无大小限制，受限于局域网带宽

# 🚀 Monorepo 技术栈发布与 Git Tag 隔离指南

本项目采用 Monorepo（单一仓库）结构。为了防止各端发布时 Git Tag 相互污染或误触发自动化流水线，系统对 **Android、WPF、Server** 进行了严格的 Tag 命名空间隔离。

### 1. 核心 Tag 规则清单

| 目标端 | 本地关联目录 | 发布 Tag 格式示例 | 触发产物 |
| :--- | :--- | :--- | :--- |
| **Android 客户端** | `Kotlin/**` | `android/v1.0.0` | GitHub Release（含 `.apk`） |
| **WPF 桌面端** | `WPF/**` | `wpf/v1.0.0` | GitHub Release（含 `MiniFeiQ.exe`） |
| **Server 服务端** | `Server/**` | `server/v1.0.0` | GHCR 镜像（带有 `:v1.0.0` 标签） |

> ⚠️ **反模式警告**：请勿使用无前缀的裸版本号（如 `v1.0.0` 或 `1.0.0`），此类 Tag 将无法精准触发对应的流水线。

---

### 2. 各端发布实操命令

#### 🤖 发布 Android 新版本
```bash
git tag android/v1.0.0
git push origin android/v1.0.0
```
* **效果**：触发 Android 单独打包，并在 GitHub Releases 页面生成带有安装包的 `Android Release android/v1.0.0`。版本号会自动从 tag 注入 `versionName` / `versionCode`。

#### 💻 发布 WPF 新版本
```bash
git tag wpf/v1.0.0
git push origin wpf/v1.0.0
```
* **效果**：触发 Windows 环境编译，并在 GitHub Releases 页面生成带有单文件绿色版的 `WPF Release wpf/v1.0.0`。版本号会自动从 tag 注入 `Version` / `FileVersion`。

#### 🐳 发布 Server 新版本
```bash
git tag server/v1.0.0
git push origin server/v1.0.0
```
* **效果**：触发 Docker 构建，并向 GitHub Packages 自动推送以下镜像标签：
  * `ghcr.io/.../feiq2026-relay:v1.0.0` (精准版本)
  * `ghcr.io/.../feiq2026-relay:latest` (最新稳定版)
