using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using FeiQ2026.Services;
using FeiQ2026.Transport;

namespace FeiQ2026;

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

        // 头像变更：推给所有在线 FeiQ2026 好友
        _ = PushAvatarToAllAsync();

        // 用户名/下载目录变更：重连以生效
        _ = RestartServiceAsync();
    }

    private async Task RestartServiceAsync()
    {
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
            SetConnState(connecting: true);
            StatusText.Text = "连接中...";
            StatusText.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0xE0, 0xF7, 0xFF));

            var isWs = ModeBox.SelectedIndex == 1;
            if (isWs)
            {
                var url = _settings.LastServerUrl?.Trim();
                if (string.IsNullOrEmpty(url))
                {
                    SetConnState(ok: false);
                    System.Windows.MessageBox.Show(
                        "请先在设置中填写中继地址（点击左上角头像）\n例如 wss://s0.v100.vip:27658/ws",
                        "FeiQ 2026");
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
            SetConnState(ok: true);
            App.UpdateTrayTip($"FeiQ 2026（在线人数: {_friends.Count}）");
        }
        catch (Exception ex)
        {
            StatusText.Text = "连接失败";
            StatusText.Foreground = System.Windows.Media.Brushes.LightPink;
            SetConnState(ok: false);
            System.Windows.MessageBox.Show($"启动失败:\n{ex.Message}", "FeiQ 2026",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void SetConnState(bool ok = false, bool connecting = false)
    {
        if (ConnDot == null) return;
        if (connecting)
        {
            ConnDot.Fill = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0xFF, 0xB3, 0x00)); // amber
            ConnDot.ToolTip = "连接中...";
        }
        else if (ok)
        {
            ConnDot.Fill = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x22, 0xC5, 0x5E)); // green
            ConnDot.ToolTip = "已连接";
        }
        else
        {
            ConnDot.Fill = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0xBB, 0xBB, 0xBB));
            ConnDot.ToolTip = "未连接";
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
        SetConnState(ok: false);
    }

    /// <summary>
    /// 聊天记录主键：用协议里的 HostName（机器名），比用户名/IP 稳定。
    /// IPMSG 不带 MAC；路由器 ARP 表客户端拿不到。
    /// </summary>
    private static string PeerKey(Peer p)
    {
        var host = (p.HostName ?? "").Trim();
        if (!string.IsNullOrEmpty(host))
            return "host:" + host.ToLowerInvariant();
        // 极端兜底（无主机名时）
        return "ip:" + p.Ip;
    }

    private void OnPeerOnline(Peer peer)
    {
        Dispatcher.Invoke(() =>
        {
            var key = PeerKey(peer);
            if (_friends.All(f => PeerKey(f.Peer) != key))
                _friends.Add(new FriendItem(peer));
            else
                RefreshFriendAvatar(key);
            UpdateOnlineCount();
        });
        // 向新上线的 FeiQ2026 好友推送自己的头像
        _ = PushAvatarToAsync(peer);
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
        // FeiQ2026 头像同步包：不进聊天记录、不弹气泡
        if (AvatarCache.TryParse(text, out var jpeg))
        {
            var key = PeerKey(peer);
            AvatarCache.Save(key, jpeg);
            Dispatcher.Invoke(() =>
            {
                RefreshFriendAvatar(key);
                if (_chats.TryGetValue(key, out var chat) && chat.IsLoaded)
                    chat.UpdatePeerAvatar(AvatarCache.GetPath(key));
            });
            return;
        }

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

    private void RefreshFriendAvatar(string peerKey)
    {
        var item = _friends.FirstOrDefault(f => PeerKey(f.Peer) == peerKey);
        item?.ReloadAvatar();
    }

    private async Task PushAvatarToAsync(Peer peer)
    {
        if (_service == null) return;
        var msg = AvatarCache.BuildSyncMessage(_settings.AvatarPath);
        if (msg == null) return;
        try
        {
            await _service.SendTextAsync(peer.Ip, msg, requireAck: false);
        }
        catch { /* ignore */ }
    }

    private async Task PushAvatarToAllAsync()
    {
        if (_service == null) return;
        var msg = AvatarCache.BuildSyncMessage(_settings.AvatarPath);
        if (msg == null) return;
        foreach (var f in _friends.ToList())
        {
            try { await _service.SendTextAsync(f.Peer.Ip, msg, requireAck: false); }
            catch { /* ignore */ }
        }
    }

    /// <summary>
    /// 图片/音乐/视频：自动接收并直接展示，不弹确认框、不刷系统提示。
    /// 其它类型文件：弹确认框，走传统接收流程。
    /// </summary>
    private static bool IsAutoReceiveMedia(string fileName)
    {
        var kind = ChatWindow.DetectFileKind(fileName);
        return kind is FileKind.Image or FileKind.Audio or FileKind.Video;
    }

    private void OnFileOffered(IncomingFileOffer offer)
    {
        Dispatcher.Invoke(async () =>
        {
            if (_service == null) return;

            var fileName = offer.Info.FileName;
            var autoMedia = IsAutoReceiveMedia(fileName);

            if (autoMedia)
            {
                // 媒体文件：静默自动接收，打开聊天窗口（不写“正在接收”）
                OpenChat(offer.From);
                await _service.AcceptFileAsync(offer);
                return;
            }

            // 普通文件：弹出确认
            var sizeStr = FormatSize(offer.Info.Size);
            var result = System.Windows.MessageBox.Show(
                $"收到来自 {offer.From.Name} 的文件：\n\n{fileName}\n大小：{sizeStr}\n\n是否接收？",
                "FeiQ 2026 文件传输",
                MessageBoxButton.YesNo,
                MessageBoxImage.Question);

            if (result == MessageBoxResult.Yes)
            {
                var chat = OpenChat(offer.From);
                chat?.AppendSystem($"正在接收文件 {fileName} ...");
                await _service.AcceptFileAsync(offer);
            }
        });
    }

    private void OnFileProgress(FileTransferProgress p)
    {
        Dispatcher.Invoke(() =>
        {
            var isMedia = IsAutoReceiveMedia(p.FileName);

            if (p.Error != null)
            {
                // 错误仍提示（媒体/普通都提示）
                var msg = $"文件 {p.FileName} 失败：{p.Error}";
                foreach (var chat in _chats.Values.Where(x => x.IsLoaded))
                    chat.AppendSystem(msg);
                return;
            }

            if (!p.Done) return;

            if (p.SavedPath != null)
            {
                // 接收完成
                if (isMedia)
                {
                    // 媒体：只展示富媒体气泡，不写“已保存”等系统消息
                    foreach (var chat in _chats.Values.Where(x => x.IsLoaded))
                        chat.AppendReceivedFile(p.SavedPath, p.FileName);
                }
                else
                {
                    // 普通文件：系统提示 + 富媒体气泡（若能识别）
                    foreach (var chat in _chats.Values.Where(x => x.IsLoaded))
                    {
                        chat.AppendSystem($"文件已保存：{p.SavedPath}");
                        chat.AppendReceivedFile(p.SavedPath, p.FileName);
                    }
                }
            }
            else
            {
                // 发送完成：媒体不刷系统消息，普通文件才提示
                if (!isMedia)
                {
                    var msg = $"文件发送完成：{p.FileName}";
                    foreach (var chat in _chats.Values.Where(x => x.IsLoaded))
                        chat.AppendSystem(msg);
                }
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

public sealed class FriendItem : INotifyPropertyChanged
{
    public Peer Peer { get; }
    public string Name => Peer.Name;
    public string SubTitle => $"{Peer.HostName} · {Peer.Ip}";
    public string AvatarLetter => string.IsNullOrEmpty(Peer.Name) ? "?" : Peer.Name[..1].ToUpperInvariant();

    private ImageSource? _avatarImage;
    public ImageSource? AvatarImage
    {
        get => _avatarImage;
        private set
        {
            _avatarImage = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(AvatarImage)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(ImageVisibility)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(LetterVisibility)));
        }
    }

    public Visibility ImageVisibility => AvatarImage != null ? Visibility.Visible : Visibility.Collapsed;
    public Visibility LetterVisibility => AvatarImage == null ? Visibility.Visible : Visibility.Collapsed;

    public event PropertyChangedEventHandler? PropertyChanged;

    public FriendItem(Peer peer)
    {
        Peer = peer;
        ReloadAvatar();
    }

    public void ReloadAvatar()
    {
        var key = !string.IsNullOrWhiteSpace(Peer.HostName)
            ? "host:" + Peer.HostName.Trim().ToLowerInvariant()
            : "ip:" + Peer.Ip;
        AvatarImage = AvatarCache.LoadImage(key);
    }
}
