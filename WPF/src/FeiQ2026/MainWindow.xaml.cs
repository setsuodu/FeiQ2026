using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Net;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using FeiQ2026.Services;
using FeiQ2026.Transport;
using Brush = System.Windows.Media.Brush;
using Color = System.Windows.Media.Color;

namespace FeiQ2026;

public partial class MainWindow : Window
{
    private readonly ObservableCollection<FriendItem> _friends = new();
    private readonly Dictionary<string, ChatWindow> _chats = new();
    private AppSettings _settings;
    private ChatStore _chatStore;
    private OutboxStore _outbox;
    private IpMsgService? _service;
    private ITransport? _transport;
    private bool _forceClose;
    /// <summary>当前在线 peer_key 集合（协议实时）</summary>
    private readonly HashSet<string> _onlineKeys = new(StringComparer.Ordinal);

    public MainWindow()
    {
        InitializeComponent();
        _settings = AppSettings.Load();
        _chatStore = new ChatStore(_settings.ChatDbPath);
        _outbox = new OutboxStore(Path.Combine(_settings.ChatDbDir, "outbox.db"));

        UserList.ItemsSource = _friends;
        ApplyProfileUi();
        ModeBox.SelectedIndex = _settings.LastModeIndex;

        // 启动时先从历史恢复会话列表（离线联系人保留）
        LoadSessionsFromStore();

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
            try { _outbox.Dispose(); } catch { }
            _outbox = new OutboxStore(Path.Combine(_settings.ChatDbDir, "outbox.db"));
            LoadSessionsFromStore();
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
            // 保留历史会话 + 合并当前在线
            LoadSessionsFromStore();
            UpdateOnlineCount();
            // 中继连上后冲刷本机待发文本
            if (isWs)
                _ = FlushOutboxAsync();
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
        _onlineKeys.Clear();
        // 保留会话：历史联系人仍显示，仅标记离线
        foreach (var f in _friends)
            f.IsOnline = false;
        LoadSessionsFromStore();
        UpdateOnlineCount();
        SetConnState(ok: false);
    }

    /// <summary>
    /// 聊天记录主键：优先 HostName，其次用户名；忽略 Loopback/Any（WS 占位 IP）。
    /// </summary>
    private static string PeerKey(Peer p) => ChatStore.MakePeerKey(p);

    private void OnPeerOnline(Peer peer)
    {
        Dispatcher.Invoke(() =>
        {
            var key = PeerKey(peer);
            _onlineKeys.Add(key);
            UpsertFriend(peer, isOnline: true);
            UpdateOnlineCount();
        });
        // 向新上线的 FeiQ2026 好友推送自己的头像
        _ = PushAvatarToAsync(peer);
    }

    private void OnPeerOffline(Peer peer)
    {
        Dispatcher.Invoke(() =>
        {
            // 只移出在线集合，会话列表保留（与 Android 一致）
            var key = PeerKey(peer);
            _onlineKeys.Remove(key);
            var exist = _friends.FirstOrDefault(f => PeerKey(f.Peer) == key);
            if (exist != null)
                exist.IsOnline = false;
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
            // 离线消息 / 普通消息一律写入聊天记录
            try { _chatStore.Add(key, peer.Name, "in", text); } catch { /* ignore db errors */ }
            // 有消息的联系人保留在列表（即便当时离线）
            UpsertFriend(peer, isOnline: _onlineKeys.Contains(key));

            if (_chats.TryGetValue(key, out var chat) && chat.IsLoaded)
            {
                chat.AppendIncoming(text, persist: false);
                if (!chat.IsActive)
                    App.Balloon($"来自 {peer.Name}", text, peer);
            }
            else
            {
                App.Balloon($"来自 {peer.Name}", text, peer);
            }
        });
    }

    /// <summary>合入会话列表：已有则更新，没有则插到最前（按显示名/host 逻辑身份去重）</summary>
    private void UpsertFriend(Peer peer, bool isOnline)
    {
        var key = PeerKey(peer);
        if (key == "unknown") return;
        var id = ChatStore.IdentityOf(key, peer.Name);
        // 也用显示名直接比一次，防止 host 脏后缀导致 identity 不一致
        var nameId = (peer.Name ?? "").Trim().ToLowerInvariant();
        var exist = _friends.FirstOrDefault(f =>
        {
            var fid = ChatStore.IdentityOf(PeerKey(f.Peer), f.Peer.Name);
            if (fid == id) return true;
            var fn = (f.Peer.Name ?? "").Trim().ToLowerInvariant();
            return !string.IsNullOrEmpty(nameId) && nameId == fn;
        });
        if (exist != null)
        {
            // 在线包优先覆盖（带真实 HostName/头像）
            if (isOnline || string.IsNullOrWhiteSpace(exist.Peer.HostName))
                exist.UpdatePeer(peer);
            exist.IsOnline = isOnline || exist.IsOnline;
            var idx = _friends.IndexOf(exist);
            if (idx > 0)
                _friends.Move(idx, 0);
        }
        else
        {
            _friends.Insert(0, new FriendItem(peer, isOnline));
        }
    }

    /// <summary>从 ChatStore 加载历史会话，与当前在线合并（同逻辑身份只留一条）</summary>
    private void LoadSessionsFromStore()
    {
        try
        {
            var fromDb = _chatStore.ListRecentSessions();
            var ordered = new List<FriendItem>();
            // 用 IdentityOf 去重，避免 host:xxx 与 name:xxx / ip:0.0.0.0 各占一条
            var seen = new HashSet<string>(StringComparer.Ordinal);

            bool TryAdd(FriendItem f)
            {
                var k = PeerKey(f.Peer);
                if (k == "unknown") return false;
                var id = ChatStore.IdentityOf(k, f.Peer.Name);
                var nameId = (f.Peer.Name ?? "").Trim().ToLowerInvariant();
                // 名字或 identity 任一已见过 → 合并
                if (!seen.Add(id)) return false;
                if (!string.IsNullOrEmpty(nameId) && id != nameId && !seen.Add(nameId))
                {
                    seen.Remove(id);
                    return false;
                }
                f.IsOnline = _onlineKeys.Contains(k)
                    || _onlineKeys.Any(ok => ChatStore.IdentityOf(ok, null) == id);
                ordered.Add(f);
                return true;
            }

            foreach (var f in _friends.Where(x => x.IsOnline).ToList())
                TryAdd(f);
            foreach (var f in _friends.ToList())
                TryAdd(f);
            foreach (var s in fromDb)
            {
                var k = ChatStore.CanonicalPeerKey(s.PeerKey, s.PeerName);
                if (k == "unknown") continue;
                var id = ChatStore.IdentityOf(k, s.PeerName);
                var nameId = (s.PeerName ?? "").Trim().ToLowerInvariant();
                if (!seen.Add(id)) continue;
                if (!string.IsNullOrEmpty(nameId) && id != nameId && !seen.Add(nameId))
                {
                    seen.Remove(id);
                    continue;
                }
                var p = ChatStore.PeerFromSession(s);
                ordered.Add(new FriendItem(p, isOnline: _onlineKeys.Contains(k)
                    || _onlineKeys.Any(ok => ChatStore.IdentityOf(ok, null) == id)));
            }

            _friends.Clear();
            foreach (var item in ordered)
                _friends.Add(item);
        }
        catch { /* ignore */ }
    }

    /// <summary>中继连上后：冲刷本机待发文本</summary>
    private async Task FlushOutboxAsync()
    {
        if (_service == null) return;
        List<OutboxRow> rows;
        try { rows = _outbox.ListAll().ToList(); }
        catch { return; }
        if (rows.Count == 0) return;

        foreach (var row in rows)
        {
            try
            {
                // 优先找在线同 key 的真实 IP；否则用 Any 让服务端按 host 路由
                var live = _friends.FirstOrDefault(f => PeerKey(f.Peer) == row.PeerKey && f.IsOnline);
                var ip = live?.Peer.Ip ?? IPAddress.Any;
                await _service.SendTextAsync(ip, row.Body, requireAck: false);
                _outbox.Delete(row.Id);

                await Dispatcher.InvokeAsync(() =>
                {
                    var peer = live?.Peer ?? new Peer
                    {
                        Name = row.PeerName,
                        HostName = row.HostName,
                        Ip = ip
                    };
                    try { _chatStore.Add(row.PeerKey, row.PeerName, "out", row.Body); } catch { }
                    try { _chatStore.Add(row.PeerKey, row.PeerName, "sys", "（离线队列已补发）"); } catch { }
                    UpsertFriend(peer, isOnline: live != null);
                    if (_chats.TryGetValue(row.PeerKey, out var chat) && chat.IsLoaded)
                    {
                        chat.AppendOutgoing(row.Body, persist: false);
                        chat.AppendSystem("（离线队列已补发）");
                    }
                });
            }
            catch
            {
                // 失败则停止，下次连上再试
                break;
            }
        }
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
        var online = _onlineKeys.Count;
        OnlineCountText.Text = $"({online}/{_friends.Count})";
        App.UpdateTrayTip($"FeiQ 2026（在线: {online} · 会话: {_friends.Count}）");
    }

    private void UserList_MouseDoubleClick(object sender, MouseButtonEventArgs e)
    {
        if (UserList.SelectedItem is FriendItem item)
            OpenChat(item.Peer);
    }

    /// <summary>由托盘通知点击调用：打开（或激活）与 peer 的聊天窗口，并确保主窗口可见。</summary>
    public void OpenChatFromNotification(Peer peer)
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
        OpenChat(peer);
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
                var pk = PeerKey(p);
                // 优先用当前在线同 key 的真实 IP
                var live = _friends.FirstOrDefault(f => PeerKey(f.Peer) == pk && f.IsOnline);
                var target = live?.Peer ?? p;

                if (_service == null)
                {
                    // 本机未连中继：入待发队列（聊天记录由 ChatWindow 写入）
                    _outbox.Enqueue(pk, p.Name, p.HostName ?? "", text);
                    UpsertFriend(p, isOnline: false);
                    return "（已入离线待发队列，连上中继后自动补发）";
                }

                // 对方离线（无真实 IP）时：仍尝试发送，由服务端入对方离线队列
                await _service.SendTextAsync(target.Ip, text);
                return null;
            },
            async (p, path) =>
            {
                if (_service == null) throw new InvalidOperationException("未连接，无法发文件");
                var pk = PeerKey(p);
                var id = ChatStore.IdentityOf(pk, p.Name);
                var live = _friends.FirstOrDefault(f =>
                    f.IsOnline && ChatStore.IdentityOf(PeerKey(f.Peer), f.Peer.Name) == id);
                var target = live?.Peer ?? p;
                var isWs = ModeBox.SelectedIndex == 1;
                // UDP 需要真实局域网 IP；WS 走中继，离线时由服务端入队（通知包），与 Android 一致
                if (!isWs)
                {
                    var ip = target.Ip;
                    if (ip is null
                        || Equals(ip, IPAddress.Any)
                        || Equals(ip, IPAddress.None)
                        || Equals(ip, IPAddress.Loopback)
                        || Equals(ip, IPAddress.IPv6Loopback))
                        throw new InvalidOperationException("对方当前离线，局域网模式无法离线发文件（请切 WebSocket 中继）");
                }
                // WS：占位 IP 用 Loopback 即可，传输层忽略地址
                var sendIp = target.Ip;
                if (isWs && (sendIp is null
                    || Equals(sendIp, IPAddress.Any)
                    || Equals(sendIp, IPAddress.None)))
                    sendIp = IPAddress.Loopback;
                await _service.SendFileAsync(sendIp!, path);
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
        try { _outbox.Dispose(); } catch { /* ignore */ }
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
    private Peer _peer;
    public Peer Peer => _peer;
    public string Name => _peer.Name;
    public string SubTitle
    {
        get
        {
            var host = (_peer.HostName ?? "").Trim();
            var pipe = host.IndexOf('|');
            if (pipe > 0) host = host[..pipe].Trim();
            var ip = _peer.Ip?.ToString() ?? "";
            // WS 在线也是 127.0.0.1，不能据此判离线；只看 IsOnline
            if (!_isOnline)
                return string.IsNullOrEmpty(host) ? "离线" : $"{host} · 离线";
            var placeholderIp = ip is "" or "0.0.0.0" or "127.0.0.1" or "::" or "::1";
            if (placeholderIp)
                return string.IsNullOrEmpty(host) ? "在线（中继）" : $"{host} · 在线";
            return string.IsNullOrEmpty(host) ? ip : $"{host} · {ip}";
        }
    }
    public string AvatarLetter => string.IsNullOrEmpty(_peer.Name) ? "?" : _peer.Name[..1].ToUpperInvariant();

    private bool _isOnline;
    public bool IsOnline
    {
        get => _isOnline;
        set
        {
            if (_isOnline == value) return;
            _isOnline = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(IsOnline)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(OnlineDotVisibility)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SubTitle)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(NameForeground)));
        }
    }

    public Visibility OnlineDotVisibility => IsOnline ? Visibility.Visible : Visibility.Collapsed;
    public Brush NameForeground => IsOnline
        ? new SolidColorBrush(Color.FromRgb(0x22, 0x22, 0x22))
        : new SolidColorBrush(Color.FromRgb(0x88, 0x88, 0x88));

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

    public FriendItem(Peer peer, bool isOnline = true)
    {
        _peer = peer;
        _isOnline = isOnline;
        ReloadAvatar();
    }

    public void UpdatePeer(Peer peer)
    {
        _peer = peer;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Name)));
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(SubTitle)));
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(AvatarLetter)));
        ReloadAvatar();
    }

    public void ReloadAvatar()
    {
        AvatarImage = AvatarCache.LoadImage(ChatStore.MakePeerKey(_peer));
    }
}
