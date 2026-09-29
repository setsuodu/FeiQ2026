using System.Collections.ObjectModel;
using System.Net;
using System.Windows;
using MiniFeiQ.Services;
using MiniFeiQ.Transport;

namespace MiniFeiQ;

public partial class MainWindow : Window
{
    private readonly ObservableCollection<Peer> _users = new();
    private IpMsgService? _service;

    public MainWindow()
    {
        InitializeComponent();
        UserList.ItemsSource = _users;
        Loaded += OnLoaded;
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        // ========== 传输层在这里切换 ==========
        // 当前使用 UDP（飞秋2013 兼容）
        // 以后要加 WebSocket / TCP / KCP，只需实现 ITransport 然后在这里替换即可
        ITransport transport = new UdpTransport(port: 2425);

        // 示例：以后可以这样切换
        // ITransport transport = new WebSocketTransport("ws://...");
        // ITransport transport = new KcpTransport(...);
        // ITransport transport = new TcpTransport(...);

        _service = new IpMsgService(transport);
        _service.PeerOnline += OnPeerOnline;
        _service.PeerOffline += OnPeerOffline;
        _service.MessageReceived += OnMessageReceived;

        try
        {
            await _service.StartAsync();
            AppendLog("[系统] 已启动，使用 UDP 传输层（兼容飞秋2013）");
        }
        catch (Exception ex)
        {
            AppendLog($"[错误] 启动失败: {ex.Message}");
            MessageBox.Show($"启动失败，请检查防火墙是否放行 2425 端口。\n\n{ex.Message}",
                "MiniFeiQ", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void OnPeerOnline(Peer peer)
    {
        Dispatcher.Invoke(() =>
        {
            if (_users.All(u => !u.Ip.Equals(peer.Ip)))
                _users.Add(peer);
            AppendLog($"[上线] {peer.Name} ({peer.Ip})");
        });
    }

    private void OnPeerOffline(Peer peer)
    {
        Dispatcher.Invoke(() =>
        {
            var exist = _users.FirstOrDefault(u => u.Ip.Equals(peer.Ip));
            if (exist != null) _users.Remove(exist);
            AppendLog($"[下线] {peer.Name} ({peer.Ip})");
        });
    }

    private void OnMessageReceived(Peer peer, string text)
    {
        Dispatcher.Invoke(() =>
        {
            AppendLog($"[{peer.Name}@{peer.Ip}] {text}");
        });
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e)
    {
        if (_service == null) return;
        _users.Clear();
        await _service.RefreshAsync();
        AppendLog("[系统] 已刷新在线列表");
    }

    private async void Send_Click(object sender, RoutedEventArgs e)
    {
        if (_service == null) return;
        if (UserList.SelectedItem is not Peer peer) return;
        var text = Input.Text?.Trim();
        if (string.IsNullOrEmpty(text)) return;

        try
        {
            await _service.SendTextAsync(peer.Ip, text);
            AppendLog($"[我 -> {peer.Name}] {text}");
            Input.Clear();
        }
        catch (Exception ex)
        {
            AppendLog($"[发送失败] {ex.Message}");
        }
    }

    private async void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
    {
        if (_service != null)
        {
            await _service.DisposeAsync();
            _service = null;
        }
    }

    private void AppendLog(string line)
    {
        Log.AppendText($"{DateTime.Now:HH:mm:ss} {line}\n");
        Log.ScrollToEnd();
    }
}
