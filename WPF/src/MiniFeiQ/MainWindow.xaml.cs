using System.Collections.ObjectModel;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Win32;
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
                    MessageBox.Show("请填写中继地址，例如 ws://192.168.1.101:9000/ws");
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
            _service.FileOffered += OnFileOffered;
            _service.FileProgress += OnFileProgress;

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
            _service.FileOffered -= OnFileOffered;
            _service.FileProgress -= OnFileProgress;
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
            if (_users.All(u => u.Name != peer.Name || !u.Ip.Equals(peer.Ip)))
                _users.Add(peer);
            AppendLog($"[上线] {peer.Name}");
        });
    }

    private void OnPeerOffline(Peer peer)
    {
        Dispatcher.Invoke(() =>
        {
            var exist = _users.FirstOrDefault(u => u.Name == peer.Name && u.Ip.Equals(peer.Ip));
            if (exist != null) _users.Remove(exist);
            AppendLog($"[下线] {peer.Name}");
        });
    }

    private void OnMessageReceived(Peer peer, string text)
    {
        Dispatcher.Invoke(() => AppendLog($"[{peer.Name}] {text}"));
    }

    private void OnFileOffered(IncomingFileOffer offer)
    {
        Dispatcher.Invoke(async () =>
        {
            var sizeStr = FormatSize(offer.Info.Size);
            AppendLog($"[文件] {offer.From.Name} 发来「{offer.Info.FileName}」({sizeStr})");

            var result = MessageBox.Show(
                $"收到来自 {offer.From.Name} 的文件：\n\n{offer.Info.FileName}\n大小：{sizeStr}\n\n是否接收？",
                "文件传输",
                MessageBoxButton.YesNo,
                MessageBoxImage.Question);

            if (result == MessageBoxResult.Yes && _service != null)
            {
                AppendLog($"[文件] 开始接收 {offer.Info.FileName} ...");
                await _service.AcceptFileAsync(offer);
            }
            else
            {
                AppendLog($"[文件] 已拒绝 {offer.Info.FileName}");
            }
        });
    }

    private void OnFileProgress(FileTransferProgress p)
    {
        Dispatcher.Invoke(() =>
        {
            if (p.Error != null)
            {
                AppendLog($"[文件] {p.FileName} 失败：{p.Error}");
                return;
            }
            if (p.Done)
            {
                var where = p.SavedPath != null ? $" → {p.SavedPath}" : "";
                AppendLog($"[文件] {p.FileName} 完成 {FormatSize(p.Received)}{where}");
            }
            else if (p.Total > 0)
            {
                var pct = (int)(p.Received * 100 / p.Total);
                // 避免刷屏：每 10% 打一次
                if (pct % 10 == 0)
                    AppendLog($"[文件] {p.FileName} {pct}%");
            }
        });
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

        if (ModeBox.SelectedIndex == 1)
        {
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

    private async void SendFile_Click(object sender, RoutedEventArgs e)
    {
        if (_service == null)
        {
            MessageBox.Show("请先连接");
            return;
        }

        var dlg = new OpenFileDialog
        {
            Title = "选择要发送的文件",
            CheckFileExists = true,
            Multiselect = false
        };
        if (dlg.ShowDialog() != true) return;

        var path = dlg.FileName;
        var name = Path.GetFileName(path);

        try
        {
            if (ModeBox.SelectedIndex == 1)
            {
                // WS 广播发文件
                await _service.SendFileAsync(System.Net.IPAddress.Loopback, path);
                AppendLog($"[我：] 发送文件 {name}");
            }
            else
            {
                if (UserList.SelectedItem is not Peer peer)
                {
                    MessageBox.Show("请先在左侧选中接收方");
                    return;
                }
                await _service.SendFileAsync(peer.Ip, path);
                AppendLog($"[我 → {peer.Name}] 发送文件 {name}");
            }
        }
        catch (Exception ex)
        {
            AppendLog($"[错误] 发文件失败：{ex.Message}");
            MessageBox.Show(ex.Message, "发文件失败");
        }
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

    private static string FormatSize(long bytes)
    {
        if (bytes < 1024) return $"{bytes} B";
        if (bytes < 1024 * 1024) return $"{bytes / 1024.0:F1} KB";
        if (bytes < 1024L * 1024 * 1024) return $"{bytes / (1024.0 * 1024):F1} MB";
        return $"{bytes / (1024.0 * 1024 * 1024):F2} GB";
    }
}
