using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Controls;
using MiniFeiQ.Services;
using MiniFeiQ.Transport;

namespace MiniFeiQ;

public partial class MainWindow : Window
{
    private readonly ObservableCollection<Peer> _users = new();
    private IpMsgService? _service;
    private ITransport? _transport;

    public MainWindow()
    {
        InitializeComponent();
        UserList.ItemsSource = _users;
    }

    private async void Connect_Click(object sender, RoutedEventArgs e)
    {
        await StopServiceAsync();
        await StartServiceAsync();
    }

    private async void ModeBox_Changed(object sender, SelectionChangedEventArgs e)
    {
        // 切换模式时自动重连
        if (!IsLoaded) return;
        await StopServiceAsync();
        await StartServiceAsync();
    }

    private async Task StartServiceAsync()
    {
        try
        {
            StatusText.Text = "  连接中...";
            StatusText.Foreground = System.Windows.Media.Brushes.Orange;

            var isWs = ModeBox.SelectedIndex == 1;
            if (isWs)
            {
                var url = ServerUrlBox.Text?.Trim();
                if (string.IsNullOrEmpty(url))
                {
                    MessageBox.Show("请填写中继地址，例如 ws://127.0.0.1:9000/ws");
                    return;
                }
                _transport = new WebSocketTransport(url);
                AppendLog($"[系统] 使用 WebSocket 中继 → {url}");
            }
            else
            {
                _transport = new UdpTransport(2425);
                AppendLog("[系统] 使用 UDP 局域网（飞秋2013兼容）");
            }

            _service = new IpMsgService(_transport);
            _service.PeerOnline += OnPeerOnline;
            _service.PeerOffline += OnPeerOffline;
            _service.MessageReceived += OnMessageReceived;

            await _service.StartAsync();

            StatusText.Text = isWs ? "  已连接中继" : "  UDP 已启动";
            StatusText.Foreground = System.Windows.Media.Brushes.Green;
            AppendLog("[系统] 启动成功");
        }
        catch (Exception ex)
        {
            StatusText.Text = "  连接失败";
            StatusText.Foreground = System.Windows.Media.Brushes.Red;
            AppendLog($"[错误] {ex.Message}");
            MessageBox.Show($"启动失败:\n{ex.Message}", "MiniFeiQ",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private async Task StopServiceAsync()
    {
        if (_service != null)
        {
            _service.PeerOnline -= OnPeerOnline;
            _service.PeerOffline -= OnPeerOffline;
            _service.MessageReceived -= OnMessageReceived;
            await _service.DisposeAsync();
            _service = null;
        }
        _transport = null;
        _users.Clear();
    }

    private void OnPeerOnline(Peer peer)
    {
        Dispatcher.Invoke(() =>
        {
            if (_users.All(u => !u.Ip.Equals(peer.Ip)))
                _users.Add(peer);
            AppendLog($"[上线] {peer.Name}");
        });
    }

    private void OnPeerOffline(Peer peer)
    {
        Dispatcher.Invoke(() =>
        {
            var exist = _users.FirstOrDefault(u => u.Ip.Equals(peer.Ip));
            if (exist != null) _users.Remove(exist);
            AppendLog($"[下线] {peer.Name}");
        });
    }

    private void OnMessageReceived(Peer peer, string text)
    {
        Dispatcher.Invoke(() => AppendLog($"[{peer.Name}] {text}"));
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e)
    {
        if (_service == null) return;
        _users.Clear();
        await _service.RefreshAsync();
        AppendLog("[系统] 已刷新");
    }

    private async void Send_Click(object sender, RoutedEventArgs e)
    {
        if (_service == null) return;
        var text = Input.Text?.Trim();
        if (string.IsNullOrEmpty(text)) return;

        // WebSocket 模式下没有真实 IP，选中谁都广播；UDP 模式按选中用户发
        if (ModeBox.SelectedIndex == 1)
        {
            // 中继广播：随便给个占位 IP
            await _service.SendTextAsync(System.Net.IPAddress.Loopback, text);
            AppendLog($"[我：] {text}");
        }
        else
        {
            if (UserList.SelectedItem is not Peer peer) return;
            await _service.SendTextAsync(peer.Ip, text);
            AppendLog($"[我 → {peer.Name}] {text}");
        }
        Input.Clear();
    }

    private async void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
    {
        await StopServiceAsync();
    }

    private void AppendLog(string line)
    {
        Log.AppendText($"{DateTime.Now:HH:mm:ss} {line}\n");
        Log.ScrollToEnd();
    }
}
