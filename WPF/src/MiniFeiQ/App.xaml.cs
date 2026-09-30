using System.Windows;
using Forms = System.Windows.Forms;

namespace MiniFeiQ;

public partial class App : System.Windows.Application
{
    private static Forms.NotifyIcon? _tray;
    private static MainWindow? _main;

    protected override void OnStartup(StartupEventArgs e)
    {
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

    public static void Balloon(string title, string text)
    {
        try
        {
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
        base.OnExit(e);
    }
}
