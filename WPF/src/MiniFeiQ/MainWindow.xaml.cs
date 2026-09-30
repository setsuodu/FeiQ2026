using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Windows;
using System.Windows.Data;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using MiniFeiQ.Services;
using MiniFeiQ.Transport;

namespace MiniFeiQ;

public partial class MainWindow : Window
{
    private readonly ObservableCollection<FriendItem> _friends = new();
    private readonly Dictionary<string, ChatWindow> _chats = new();
    private AppSettings _settings;
    private ChatStore _chatStore;
    private IpMsgService? _service;
    private ITransport? _transport;
    private bool _forceClose;

    public MainWindow()
    {
        InitializeComponent();
        _settings = AppSettings.Load();
        _chatStore = new ChatStore(_settings.ChatDbPath);

        UserList.ItemsSource = _friends;
        ApplyProfileUi();
        ServerUrlBox.Text = _settings.LastServerUrl;
        ModeBox.SelectedIndex = _settings.LastModeIndex;

        Loaded += async (_, _) => await StartServiceAsync();
    }

    public void ForceClose()
    {
        _forceClose = true;
        Close();
    }

    private void ApplyProfileUi()
    {
        SelfNameText.Text = _settings.UserName;
        var letter = string.IsNullOrEmpty(_settings.UserName) ? "FQ" : _settings.UserName[..1].ToUpperInvariant();
        AvatarLetterText.Text = letter;

        if (!string.IsNullOrEmpty(_settings.AvatarPath) && File.Exists(_settings.AvatarPath))
        {
            try
            {
                var bmp = new BitmapImage();
                bmp.BeginInit();
                bmp.CacheOption = BitmapCacheOption.OnLoad;
                bmp.UriSource = new Uri(_settings.AvatarPath, UriKind.Absolute);
                bmp.EndInit();
                SelfAvatarImage.Source = bmp;
                SelfAvatarImage.Visibility = Visibility.Visible;
                AvatarLetterText.Visibility = Visibility.Collapsed;
                return;
            }
            catch { /* fall through */ }
        }
        SelfAvatarImage.Source = null;
        SelfAvatarImage.Visibility = Visibility.Collapsed;
        AvatarLetterText.Visibility = Visibility.Visible;
        AvatarLetterText.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x12, 0xB7, 0xF5));
    }

    private void Avatar_Click(object sender, MouseButtonEventArgs e)
    {
        var dlg = new SettingsWindow(_settings) { Owner = this };
        if (dlg.ShowDialog() != true) return;

        // 重新加载（Save 已写入磁盘）
        var oldDb = _settings.ChatDbPath;
        _settings = AppSettings.Load();
        ApplyProfileUi();

        if (!string.Equals(oldDb, _settings.ChatDbPath, StringComparison.OrdinalIgnoreCase))
        {
            _chatStore.Dispose();
            _chatStore = new ChatStore(_settings.ChatDbPath);
        }

        // 用户名/下载目录变更：重连以生效
        _ = RestartServiceAsync();
    }

    private async Task RestartServiceAsync()
    {
        await StopServiceAsync();
        await StartServiceAsync();
    }

    private async void Connect_Click(object sender, RoutedEventArgs e)
    {
        _settings.LastServerUrl = ServerUrlBox.Text?.Trim() ?? _settings.LastServerUrl;
        _settings.LastModeIndex = ModeBox.SelectedIndex;
        _settings.Save();
        await StopServiceAsync();
        await StartServiceAsync();
    }

    private async void ModeBox_Changed(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (!IsLoaded) return;
        _settings.LastModeIndex = ModeBox.SelectedIndex;
        _settings.Save();
        await StopServiceAsync();
        await StartServiceAsync();
    }

    private async Task StartServiceAsync()
    {
        try
        {
            StatusText.Text = "连接中...";
            StatusText.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0xE0, 0xF7, 0xFF));

            var isWs = ModeBox.SelectedIndex == 1;
            if (isWs)
            {
                var url = ServerUrlBox.Text?.Trim();
                if (string.IsNullOrEmpty(url))
                {
                    System.Windows.MessageBox.Show("请填写中继地址，例如 ws://192.168.1.101:9000/ws", "FeiQ 2026");
                    return;
                }
                _transport = new WebSocketTransport(url);
            }
            else
            {
                _transport = new UdpTransport(2425);
            }

            _service = new IpMsgService(_transport, userName: _settings.UserName);
            _service.DownloadDir = _settings.DownloadDir;
            _service.PeerOnline += OnPeerOnline;
            _service.PeerOffline += OnPeerOffline;
            _service.MessageReceived += OnMessageReceived;
            _service.FileOffered += OnFileOffered;
            _service.FileProgress += OnFileProgress;

            await _service.StartAsync();

            StatusText.Text = isWs ? "已连接中继" : "UDP 已启动 · 飞秋兼容";
            StatusText.Foreground = System.Windows.Media.Brushes.White;
            App.UpdateTrayTip($"FeiQ 2026（在线人数: {_friends.Count}）");
        }
        catch (Exception ex)
        {
            StatusText.Text = "连接失败";
            StatusText.Foreground = System.Windows.Media.Brushes.LightPink;
            System.Windows.MessageBox.Show($"启动失败:\n{ex.Message}", "FeiQ 2026",
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
        _friends.Clear();
        UpdateOnlineCount();
    }

    private static string PeerKey(Peer p) => $"{p.Name}|{p.Ip}";

    private void OnPeerOnline(Peer peer)
    {
        Dispatcher.Invoke(() =>
        {
            if (_friends.All(f => PeerKey(f.Peer) != PeerKey(peer)))
                _friends.Add(new FriendItem(peer));
            UpdateOnlineCount();
        });
    }

    private void OnPeerOffline(Peer peer)
    {
        Dispatcher.Invoke(() =>
        {
            var exist = _friends.FirstOrDefault(f => PeerKey(f.Peer) == PeerKey(peer));
            if (exist != null) _friends.Remove(exist);
            UpdateOnlineCount();
        });
    }

    private void OnMessageReceived(Peer peer, string text)
    {
        Dispatcher.Invoke(() =>
        {
            var key = PeerKey(peer);
            try { _chatStore.Add(key, peer.Name, "in", text); } catch { /* ignore db errors */ }

            if (_chats.TryGetValue(key, out var chat) && chat.IsLoaded)
            {
                chat.AppendIncoming(text, persist: false);
                if (!chat.IsActive)
                    App.Balloon($"来自 {peer.Name}", text);
            }
            else
            {
                App.Balloon($"来自 {peer.Name}", text);
            }
        });
    }

    private void OnFileOffered(IncomingFileOffer offer)
    {
        Dispatcher.Invoke(async () =>
        {
            var sizeStr = FormatSize(offer.Info.Size);
            var result = System.Windows.MessageBox.Show(
                $"收到来自 {offer.From.Name} 的文件：\n\n{offer.Info.FileName}\n大小：{sizeStr}\n\n是否接收？",
                "FeiQ 2026 文件传输",
                MessageBoxButton.YesNo,
                MessageBoxImage.Question);

            if (result == MessageBoxResult.Yes && _service != null)
            {
                var chat = OpenChat(offer.From);
                chat?.AppendSystem($"正在接收文件 {offer.Info.FileName} ...");
                await _service.AcceptFileAsync(offer);
            }
        });
    }

    private void OnFileProgress(FileTransferProgress p)
    {
        Dispatcher.Invoke(() =>
        {
            foreach (var chat in _chats.Values)
            {
                if (!chat.IsLoaded) continue;
                if (p.Error != null)
                    chat.AppendSystem($"文件 {p.FileName} 失败：{p.Error}");
                else if (p.Done)
                    chat.AppendSystem(p.SavedPath != null
                        ? $"文件已保存：{p.SavedPath}"
                        : $"文件发送完成：{p.FileName}");
            }
        });
    }

    private void UpdateOnlineCount()
    {
        OnlineCountText.Text = $"({_friends.Count})";
        App.UpdateTrayTip($"FeiQ 2026（在线人数: {_friends.Count}）");
    }

    private void UserList_MouseDoubleClick(object sender, MouseButtonEventArgs e)
    {
        if (UserList.SelectedItem is FriendItem item)
            OpenChat(item.Peer);
    }

    private ChatWindow? OpenChat(Peer peer)
    {
        var key = PeerKey(peer);
        if (_chats.TryGetValue(key, out var existing))
        {
            if (existing.IsLoaded)
            {
                existing.Activate();
                if (existing.WindowState == WindowState.Minimized)
                    existing.WindowState = WindowState.Normal;
                return existing;
            }
            _chats.Remove(key);
        }

        var win = new ChatWindow(
            peer,
            _chatStore,
            async (p, text) =>
            {
                if (_service == null) throw new InvalidOperationException("未连接");
                await _service.SendTextAsync(p.Ip, text);
            },
            async (p, path) =>
            {
                if (_service == null) throw new InvalidOperationException("未连接");
                await _service.SendFileAsync(p.Ip, path);
            });
        win.Closed += (_, _) => _chats.Remove(key);
        _chats[key] = win;
        win.Show();
        return win;
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e)
    {
        if (_service == null) return;
        try { await _service.AnnounceOnlineAsync(); }
        catch { /* ignore */ }
    }

    private void SearchBox_TextChanged(object sender, System.Windows.Controls.TextChangedEventArgs e)
    {
        var q = SearchBox.Text?.Trim() ?? "";
        var view = CollectionViewSource.GetDefaultView(_friends);
        view.Filter = string.IsNullOrEmpty(q)
            ? null
            : o => o is FriendItem f &&
                   (f.Name.Contains(q, StringComparison.OrdinalIgnoreCase) ||
                    f.SubTitle.Contains(q, StringComparison.OrdinalIgnoreCase));
    }

    private void Window_StateChanged(object? sender, EventArgs e)
    {
        if (WindowState == WindowState.Minimized)
        {
            Hide();
            App.Balloon("FeiQ 2026", "已最小化到托盘，右键托盘图标可退出。");
        }
    }

    private async void Window_Closing(object sender, CancelEventArgs e)
    {
        if (!_forceClose)
        {
            e.Cancel = true;
            Hide();
            App.Balloon("FeiQ 2026", "已最小化到托盘，右键托盘图标可退出。");
            return;
        }

        foreach (var c in _chats.Values.ToList())
        {
            try { c.Close(); } catch { /* ignore */ }
        }
        _chats.Clear();
        await StopServiceAsync();
        try { _chatStore.Dispose(); } catch { /* ignore */ }
        try
        {
            _settings.LastServerUrl = ServerUrlBox.Text?.Trim() ?? _settings.LastServerUrl;
            _settings.LastModeIndex = ModeBox.SelectedIndex;
            _settings.Save();
        }
        catch { /* ignore */ }
    }

    private static string FormatSize(long bytes)
    {
        if (bytes < 1024) return $"{bytes} B";
        if (bytes < 1024 * 1024) return $"{bytes / 1024.0:F1} KB";
        if (bytes < 1024L * 1024 * 1024) return $"{bytes / (1024.0 * 1024):F1} MB";
        return $"{bytes / (1024.0 * 1024 * 1024):F2} GB";
    }
}

public sealed class FriendItem
{
    public Peer Peer { get; }
    public string Name => Peer.Name;
    public string SubTitle => $"{Peer.HostName} · {Peer.Ip}";
    public string AvatarLetter => string.IsNullOrEmpty(Peer.Name) ? "?" : Peer.Name[..1].ToUpperInvariant();

    public FriendItem(Peer peer) => Peer = peer;
}
