using System.Threading;
using System.Windows;
using FeiQ2026.Services;
using Forms = System.Windows.Forms;

namespace FeiQ2026;

public partial class App : System.Windows.Application
{
    /// <summary>
    /// 本机单实例互斥体。软件绑定机器而非账号，同一台机器只允许一个进程。
    /// </summary>
    private static Mutex? _singleInstanceMutex;
    private const string MutexName = "Global\\FeiQ2026_SingleInstance";

    private static Forms.NotifyIcon? _tray;
    private static MainWindow? _main;
    private static Peer? _lastBalloonPeer;

    protected override void OnStartup(StartupEventArgs e)
    {
        bool createdNew;
        try
        {
            _singleInstanceMutex = new Mutex(true, MutexName, out createdNew);
        }
        catch
        {
            // 权限或命名空间异常时仍尝试继续，避免误杀启动
            createdNew = true;
        }

        if (!createdNew)
        {
            System.Windows.MessageBox.Show(
                "FeiQ 2026 已经在运行中。\n\n本软件按机器绑定，同一台电脑只允许打开一个实例。",
                "已在运行",
                MessageBoxButton.OK,
                MessageBoxImage.Warning);
            Shutdown();
            return;
        }

        base.OnStartup(e);

        _main = new MainWindow();
        MainWindow = _main;
        _main.Show();

        InitTray();
    }

    private static void InitTray()
    {
        _tray = new Forms.NotifyIcon
        {
            Visible = true,
            Text = "FeiQ 2026",
            Icon = LoadTrayIcon()
        };

        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("打开主面板", null, (_, _) => ShowMain());
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("退出 FeiQ 2026", null, (_, _) => ExitApp());
        _tray.ContextMenuStrip = menu;

        _tray.DoubleClick += (_, _) => ShowMain();
        // 点击 Windows 气泡通知 → 打开对应 Chat 窗口（无 peer 时打开主面板）
        _tray.BalloonTipClicked += (_, _) =>
        {
            if (_lastBalloonPeer != null && _main != null)
                _main.OpenChatFromNotification(_lastBalloonPeer);
            else
                ShowMain();
        };
    }

    private static System.Drawing.Icon LoadTrayIcon()
    {
        try
        {
            var uri = new Uri("pack://application:,,,/Assets/app.ico");
            var info = System.Windows.Application.GetResourceStream(uri);
            if (info != null)
                return new System.Drawing.Icon(info.Stream);
        }
        catch { /* fall through */ }
        return System.Drawing.SystemIcons.Application;
    }

    public static void ShowMain()
    {
        if (_main == null) return;
        _main.Show();
        _main.WindowState = WindowState.Normal;
        _main.Activate();
    }

    public static void UpdateTrayTip(string tip)
    {
        if (_tray == null) return;
        _tray.Text = tip.Length <= 63 ? tip : tip[..63];
    }

    /// <param name="peer">若提供，点击通知时会打开与该 peer 的 Chat 窗口</param>
    public static void Balloon(string title, string text, Peer? peer = null)
    {
        try
        {
            _lastBalloonPeer = peer;
            _tray?.ShowBalloonTip(3000, title,
                text.Length > 80 ? text[..80] + "…" : text,
                Forms.ToolTipIcon.Info);
        }
        catch { /* ignore */ }
    }

    private static void ExitApp()
    {
        if (_tray != null)
        {
            _tray.Visible = false;
            _tray.Dispose();
            _tray = null;
        }
        _main?.ForceClose();
        Current.Shutdown();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        if (_tray != null)
        {
            _tray.Visible = false;
            _tray.Dispose();
            _tray = null;
        }
        try
        {
            _singleInstanceMutex?.ReleaseMutex();
            _singleInstanceMutex?.Dispose();
            _singleInstanceMutex = null;
        }
        catch { /* ignore */ }
        base.OnExit(e);
    }
}
