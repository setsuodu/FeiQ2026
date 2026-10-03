# FeiQ 2026 — Velopack 自动更新说明

## 已改动的文件

| 文件 | 说明 |
|------|------|
| `src/FeiQ2026/FeiQ2026.csproj` | 引用 `Velopack`，`StartupObject`，App.xaml 改为 Page |
| `src/FeiQ2026/App.xaml.cs` | 自定义 `[STAThread] Main`，最前面 `VelopackApp.Build().Run()` |
| `src/FeiQ2026/Services/UpdateService.cs` | **新建** — GithubSource + 静默/交互更新 |
| `src/FeiQ2026/SettingsWindow.xaml(.cs)` | 设置页增加「检查更新」按钮 |
| `src/FeiQ2026/MainWindow.xaml.cs` | 启动后静默检查（托盘气泡） |
| `.github/workflows/wpf-publish.yml` | publish 后 `vpk pack`，Release 附带 Setup + releases 清单 |

## 本地开发

```bash
cd WPF/src/FeiQ2026
dotnet restore
dotnet build
# 直接跑 exe 会触发 NotInstalledException，已捕获，不影响调试
```

## 本地打 Velopack 包

```bash
# 1. 发布
dotnet publish WPF/src/FeiQ2026/FeiQ2026.csproj -c Release -r win-x64 --self-contained true \
  -p:PublishSingleFile=true -o ./publish

# 2. 安装 CLI（一次）
dotnet tool install -g vpk

# 3. 打包（版本号与 csproj / tag 一致）
vpk pack \
  --packId FeiQ2026 \
  --packVersion 1.0.0 \
  --packDir ./publish \
  --mainExe FeiQ2026.exe \
  --icon ./WPF/src/FeiQ2026/Assets/app.ico \
  --outputDir ./releases

# 4. 安装测试：运行 releases 里的 FeiQ2026-win-Setup.exe
# 5. 把 releases/* 上传到 GitHub Release（或推 tag wpf/v1.0.0 走 CI）
```

## 更新源

客户端使用：

```csharp
new GithubSource("https://github.com/setsuodu/FeiQ2026", null, prerelease: false)
```

因此 **必须把 vpk 生成的 nupkg / releases.win.json / Setup.exe 等上传到该仓库的 GitHub Releases**，仅上传绿色 `FeiQ2026.exe` 时自动更新不会生效。

## 用户侧行为

1. 用 **Setup.exe** 安装（写入 `%LocalAppData%\FeiQ2026`）
2. 启动后静默检查；有新版本 → 托盘气泡
3. 设置 →「检查更新」→ 确认后下载并 `ApplyUpdatesAndRestart`
