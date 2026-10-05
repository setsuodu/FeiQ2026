using System.Windows;
using Velopack;
using Velopack.Exceptions;
using Velopack.Sources;
using MessageBox = System.Windows.MessageBox;

namespace FeiQ2026.Services;

/// <summary>
/// Velopack 自动更新封装。
/// 更新源默认指向本仓库 GitHub Releases（需用 vpk pack 发布 nupkg + releases.*.json）。
/// </summary>
public static class UpdateService
{
    /// <summary>GitHub 仓库地址（公开仓库可留 token 为 null）</summary>
    public const string RepoUrl = "https://github.com/setsuodu/FeiQ2026";

    /// <summary>
    /// 创建 UpdateManager。使用 GithubSource，仅取正式版（prerelease=false）。
    /// </summary>
    public static UpdateManager CreateManager()
    {
        var source = new GithubSource(RepoUrl, accessToken: null, prerelease: false);
        return new UpdateManager(source);
    }

    /// <summary>
    /// 静默检查：有新版本时用托盘气泡提示，不打断用户。
    /// 未通过 Velopack 安装（Debug/直接跑 exe）时忽略 NotInstalledException。
    /// </summary>
    public static async Task CheckSilentlyAsync()
    {
        try
        {
            var mgr = CreateManager();
            if (!mgr.IsInstalled)
                return;

            var update = await mgr.CheckForUpdatesAsync().ConfigureAwait(true);
            if (update == null)
                return;

            var ver = update.TargetFullRelease.Version?.ToString() ?? "?";
            App.Balloon(
                "发现新版本",
                $"FeiQ 2026 {ver} 可用，点击此通知可检查并安装。",
                onClick: () =>
                {
                    System.Windows.Application.Current?.Dispatcher.Invoke(() =>
                    {
                        App.ShowMain();
                        if (System.Windows.Application.Current.MainWindow is MainWindow mw)
                            mw.OpenSettingsAndCheckUpdate();
                    });
                });
        }
        catch (NotInstalledException)
        {
            // 开发/绿色版直接运行，非 Velopack 安装
        }
        catch
        {
            // 网络失败等静默忽略
        }
    }

    /// <summary>
    /// 交互式检查并（可选）下载+重启。在 UI 线程调用。
    /// </summary>
    /// <param name="owner">消息框父窗口</param>
    /// <param name="statusCallback">进度/状态回调，可为空</param>
    public static async Task CheckAndUpdateInteractiveAsync(
        Window? owner = null,
        Action<string>? statusCallback = null)
    {
        void Status(string s) => statusCallback?.Invoke(s);

        try
        {
            Status("正在检查更新…");
            var mgr = CreateManager();

            if (!mgr.IsInstalled)
            {
                MessageBox.Show(owner,
                    "当前不是通过安装包运行的版本（开发/绿色单文件），无法使用自动更新。\n\n" +
                    "请使用 Velopack 安装包（Setup.exe）安装后再检查更新。",
                    "FeiQ 2026",
                    MessageBoxButton.OK,
                    MessageBoxImage.Information);
                Status("未安装（非 Velopack 包）");
                return;
            }

            var current = mgr.CurrentVersion?.ToString() ?? "?";
            Status($"当前版本 {current}，检查中…");

            var update = await mgr.CheckForUpdatesAsync().ConfigureAwait(true);
            if (update == null)
            {
                MessageBox.Show(owner,
                    $"已是最新版本。\n当前：{current}",
                    "FeiQ 2026",
                    MessageBoxButton.OK,
                    MessageBoxImage.Information);
                Status($"已是最新（{current}）");
                return;
            }

            var newVer = update.TargetFullRelease.Version?.ToString() ?? "?";
            var result = MessageBox.Show(owner,
                $"发现新版本：{newVer}\n当前版本：{current}\n\n是否立即下载并重启安装？",
                "FeiQ 2026 更新",
                MessageBoxButton.YesNo,
                MessageBoxImage.Question);

            if (result != MessageBoxResult.Yes)
            {
                Status($"有更新 {newVer}（已取消）");
                return;
            }

            Status($"正在下载 {newVer}…");
            await mgr.DownloadUpdatesAsync(update, p =>
            {
                Status($"下载中 {p}%…");
            }).ConfigureAwait(true);

            Status("准备重启安装…");
            mgr.ApplyUpdatesAndRestart(update);
        }
        catch (NotInstalledException)
        {
            MessageBox.Show(owner,
                "当前不是通过安装包运行的版本，无法使用自动更新。",
                "FeiQ 2026",
                MessageBoxButton.OK,
                MessageBoxImage.Information);
            Status("未安装");
        }
        catch (Exception ex)
        {
            MessageBox.Show(owner,
                "检查/下载更新失败：\n" + ex.Message,
                "FeiQ 2026",
                MessageBoxButton.OK,
                MessageBoxImage.Warning);
            Status("失败：" + ex.Message);
        }
    }
}
