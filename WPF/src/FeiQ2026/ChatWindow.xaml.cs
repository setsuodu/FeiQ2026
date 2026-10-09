using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using FeiQ2026.Services;
using MediaColor = System.Windows.Media.Color;
using MediaBrush = System.Windows.Media.Brush;
using MediaBrushes = System.Windows.Media.Brushes;
using WpfHorizontalAlignment = System.Windows.HorizontalAlignment;
using WpfVisibility = System.Windows.Visibility;
using WpfCursor = System.Windows.Input.Cursor;
using WpfCursors = System.Windows.Input.Cursors;
using WpfButton = System.Windows.Controls.Button;
using WpfMenuItem = System.Windows.Controls.MenuItem;
using WpfContextMenu = System.Windows.Controls.ContextMenu;

namespace FeiQ2026;

public partial class ChatWindow : Window
{
    public Peer Peer { get; }
    private readonly ChatStore _store;
    private readonly string _peerKey;
    private readonly ObservableCollection<ChatBubble> _messages = new();
    /// <summary>发送文本；返回非 null 时作为系统提示（如已入离线队列）</summary>
    private readonly Func<Peer, string, Task<string?>>? _sendText;
    private readonly Func<Peer, string, Task>? _sendFile;
    private readonly string _peerLetter;
    private readonly string _selfLetter;
    private readonly ImageSource? _selfAvatarImage;
    private ImageSource? _peerAvatarImage;

    private static readonly HashSet<string> ImageExts = new(StringComparer.OrdinalIgnoreCase)
        { ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".ico", ".tiff", ".tif" };
    private static readonly HashSet<string> AudioExts = new(StringComparer.OrdinalIgnoreCase)
        { ".mp3", ".wav", ".flac", ".aac", ".ogg", ".m4a", ".wma", ".ape", ".opus" };
    private static readonly HashSet<string> VideoExts = new(StringComparer.OrdinalIgnoreCase)
        { ".mp4", ".avi", ".mkv", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".ts", ".mpeg", ".mpg" };

    private static readonly Regex UrlRegex = new(
        @"https?://[^\s<>""']+",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    public ChatWindow(
        Peer peer,
        ChatStore store,
        Func<Peer, string, Task<string?>>? sendText,
        Func<Peer, string, Task>? sendFile)
    {
        InitializeComponent();
        Peer = peer;
        _store = store;
        _peerKey = ChatStore.MakePeerKey(peer);
        _sendText = sendText;
        _sendFile = sendFile;

        _peerLetter = string.IsNullOrEmpty(peer.Name) ? "?" : peer.Name[..1].ToUpperInvariant();
        var settings = AppSettings.Load();
        var selfName = settings.UserName;
        _selfLetter = string.IsNullOrEmpty(selfName) ? "我" : selfName[..1].ToUpperInvariant();
        _selfAvatarImage = AvatarCache.LoadImageFromPath(settings.AvatarPath);
        _peerAvatarImage = AvatarCache.LoadImage(_peerKey);

        Title = $"{peer.Name} - FeiQ 2026";
        PeerNameText.Text = peer.Name;
        PeerIpText.Text = peer.Ip.ToString();
        AvatarText.Text = _peerLetter;
        ApplyTitleAvatar(_peerAvatarImage);
        MsgList.ItemsSource = _messages;

        LoadHistory();
    }

    /// <summary>收到对方头像同步后：刷新标题栏 + 后续气泡用的字段</summary>
    public void UpdatePeerAvatar(string? path)
    {
        _peerAvatarImage = AvatarCache.LoadImageFromPath(path) ?? AvatarCache.LoadImage(_peerKey);
        ApplyTitleAvatar(_peerAvatarImage);
    }

    private void ApplyTitleAvatar(ImageSource? img)
    {
        if (img != null)
        {
            PeerTitleAvatarImage.Source = img;
            PeerTitleAvatarImage.Visibility = Visibility.Visible;
            AvatarText.Visibility = Visibility.Collapsed;
        }
        else
        {
            PeerTitleAvatarImage.Source = null;
            PeerTitleAvatarImage.Visibility = Visibility.Collapsed;
            AvatarText.Visibility = Visibility.Visible;
            AvatarText.Text = _peerLetter;
        }
    }

    private static ImageSource? LoadAvatarImage(string? path)
        => AvatarCache.LoadImageFromPath(path);

    private void LoadHistory()
    {
        try
        {
            foreach (var row in _store.GetRecent(_peerKey, 200))
            {
                _messages.Add(ParseStoredMessage(row.Direction, row.Text, row.CreatedAt));
            }
            ScrollToEnd();
        }
        catch { /* ignore */ }
    }

    public void AppendIncoming(string text, bool persist = true)
    {
        if (persist)
        {
            try { _store.Add(_peerKey, Peer.Name, "in", text); } catch { }
        }
        _messages.Add(ParseStoredMessage("in", text));
        ScrollToEnd();
    }

    public void AppendSystem(string text)
    {
        try { _store.Add(_peerKey, Peer.Name, "sys", text); } catch { }
        _messages.Add(MakeSystem(text));
        ScrollToEnd();
    }

    /// <summary>
    /// 开始接收：先在聊天里放一个「传输中」的文件气泡（进度条在气泡内）。
    /// </summary>
    public ChatBubble BeginReceiveFile(string fileName, long totalBytes)
    {
        var kind = DetectFileKind(fileName);
        var bubble = MakeFileBubble(null, fileName, kind, isOutgoing: false);
        bubble.SetTransferring(true, totalBytes, isSend: false);
        _messages.Add(bubble);
        ScrollToEnd();
        return bubble;
    }

    /// <summary>
    /// 普通文件：气泡内「接收 / 拒绝」，不弹 MessageBox。
    /// </summary>
    public ChatBubble ShowFileOffer(IncomingFileOffer offer, Func<IncomingFileOffer, Task> onAccept)
    {
        var fileName = offer.Info.FileName;
        var kind = DetectFileKind(fileName);
        var bubble = MakeFileBubble(null, fileName, kind, isOutgoing: false);
        bubble.SetPendingOffer(offer, offer.Info.Size, onAccept);
        _messages.Add(bubble);
        ScrollToEnd();
        return bubble;
    }

    private async void AcceptOffer_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (sender is not WpfButton btn || btn.Tag is not ChatBubble bubble) return;
        if (bubble.PendingOffer is null || bubble.PendingAccept is null) return;

        var offer = bubble.PendingOffer;
        var accept = bubble.PendingAccept;
        bubble.ClearPendingOffer(startTransfer: true);

        try
        {
            await accept(offer);
        }
        catch (Exception ex)
        {
            bubble.FinishTransfer(false, ex.Message);
        }
    }

    private void RejectOffer_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (sender is not WpfButton btn || btn.Tag is not ChatBubble bubble) return;
        bubble.RejectOffer();
    }

    /// <summary>
    /// 按文件名更新对应气泡内的进度条（多文件互不干扰，不会来回闪）。
    /// </summary>
    public void UpdateTransferProgress(string fileName, long received, long total, bool done, string? error,
        string? savedPath = null, bool fromLocalCache = false)
    {
        // 只更新本窗口里已有的同名文件气泡（发送时已加、接收时 BeginReceive 已加）
        // 避免多窗口时在无关会话里乱建气泡
        ChatBubble? bubble = null;
        for (int i = _messages.Count - 1; i >= 0; i--)
        {
            var m = _messages[i];
            if (m.IsSystem) continue;
            if (!string.Equals(m.FileName, fileName, StringComparison.OrdinalIgnoreCase)) continue;
            if (m.Kind is FileKind.File or FileKind.Image or FileKind.Audio or FileKind.Video)
            {
                // 优先取仍在传输中的；否则取最近一条
                if (m.IsTransferring)
                {
                    bubble = m;
                    break;
                }
                bubble ??= m;
            }
        }

        if (bubble == null)
            return; // 本会话没有该文件气泡，忽略（属于别的聊天窗口）

        if (error != null)
        {
            bubble.FinishTransfer(success: false, error);
            return;
        }

        if (done)
        {
            if (!string.IsNullOrEmpty(savedPath))
            {
                // 接收完成：补全路径 + 写库（不重复加气泡）
                bubble.CompleteReceive(savedPath, fromLocalCache);
                var kind = DetectFileKind(fileName);
                var note = kind switch
                {
                    FileKind.Image => $"[图片] {fileName}",
                    FileKind.Audio => $"[音乐] {fileName}",
                    FileKind.Video => $"[视频] {fileName}",
                    _ => $"[文件] {fileName}"
                };
                try { _store.Add(_peerKey, Peer.Name, "in", $"{note}|{savedPath}"); } catch { }
            }
            else
            {
                bubble.FinishTransfer(success: true, null);
            }
            return;
        }

        bubble.UpdateProgress(received, total > 0 ? total : bubble.TransferTotal);
    }

    private static string FormatBytes(long bytes)
    {
        if (bytes < 1024) return $"{bytes} B";
        if (bytes < 1024 * 1024) return $"{bytes / 1024.0:0.#} KB";
        if (bytes < 1024L * 1024 * 1024) return $"{bytes / (1024.0 * 1024):0.##} MB";
        return $"{bytes / (1024.0 * 1024 * 1024):0.##} GB";
    }

    public void AppendReceivedFile(string filePath, string? fileName = null)
    {
        fileName ??= Path.GetFileName(filePath);
        var kind = DetectFileKind(fileName);
        var note = kind switch
        {
            FileKind.Image => $"[图片] {fileName}",
            FileKind.Audio => $"[音乐] {fileName}",
            FileKind.Video => $"[视频] {fileName}",
            _ => $"[文件] {fileName}"
        };
        var storeText = $"{note}|{filePath}";
        try { _store.Add(_peerKey, Peer.Name, "in", storeText); } catch { }
        _messages.Add(MakeFileBubble(filePath, fileName, kind, isOutgoing: false));
        ScrollToEnd();
    }

    private void Input_PreviewKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        // Enter 发送；Shift+Enter 换行
        if (e.Key == System.Windows.Input.Key.Enter
            && System.Windows.Input.Keyboard.Modifiers != System.Windows.Input.ModifierKeys.Shift)
        {
            e.Handled = true;
            Send_Click(sender, new RoutedEventArgs());
        }
    }

    /// <summary>
    /// 表情选择弹窗。从 emoji_map.json 加载分类，有图显示图片，点击插入 Unicode。
    /// 气泡会把 Unicode 渲染成图片；输入框仍显示系统 Unicode（TextBox 限制）。
    /// </summary>
    private void Emoji_Click(object sender, RoutedEventArgs e)
    {
        var popup = new System.Windows.Controls.Primitives.Popup
        {
            PlacementTarget = EmojiBtn,
            Placement = System.Windows.Controls.Primitives.PlacementMode.Top,
            StaysOpen = false,
            AllowsTransparency = true
        };

        var border = new System.Windows.Controls.Border
        {
            Background = MediaBrushes.White,
            BorderBrush = new SolidColorBrush(MediaColor.FromRgb(0xDD, 0xDD, 0xDD)),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(8),
            MaxHeight = 320,
            Effect = new System.Windows.Media.Effects.DropShadowEffect
            {
                BlurRadius = 8,
                ShadowDepth = 2,
                Opacity = 0.25
            }
        };

        var scroll = new System.Windows.Controls.ScrollViewer
        {
            VerticalScrollBarVisibility = System.Windows.Controls.ScrollBarVisibility.Auto,
            MaxHeight = 300
        };
        var root = new System.Windows.Controls.StackPanel();

        foreach (var cat in Services.EmojiCatalog.Categories)
        {
            root.Children.Add(new System.Windows.Controls.TextBlock
            {
                Text = cat.CategoryTitle,
                FontSize = 12,
                Foreground = new SolidColorBrush(MediaColor.FromRgb(0x88, 0x88, 0x88)),
                Margin = new Thickness(4, 6, 4, 2)
            });

            var wrap = new System.Windows.Controls.WrapPanel { Width = 280 };
            foreach (var item in cat.Emojis)
            {
                var img = Services.EmojiCatalog.GetImage(item);
                object content = img != null
                    ? new System.Windows.Controls.Image
                    {
                        Source = img,
                        Width = 28,
                        Height = 28,
                        Stretch = Stretch.Uniform
                    }
                    : item.Char;

                var btn = new WpfButton
                {
                    Content = content,
                    Width = 36,
                    Height = 36,
                    FontSize = 18,
                    Margin = new Thickness(2),
                    Background = MediaBrushes.Transparent,
                    BorderThickness = new Thickness(0),
                    Cursor = WpfCursors.Hand,
                    Tag = item.Char,
                    ToolTip = $"{item.Shortcode} ({item.Code})"
                };
                btn.Click += (s, _) =>
                {
                    if (s is WpfButton b && b.Tag is string em)
                    {
                        var caret = Input.CaretIndex;
                        Input.Text = Input.Text.Insert(caret, em);
                        Input.CaretIndex = caret + em.Length;
                        Input.Focus();
                    }
                    popup.IsOpen = false;
                };
                wrap.Children.Add(btn);
            }
            root.Children.Add(wrap);
        }

        scroll.Content = root;
        border.Child = scroll;
        popup.Child = border;
        popup.IsOpen = true;
    }

    private async void Send_Click(object sender, RoutedEventArgs e)
    {
        var text = Input.Text?.Trim();
        if (string.IsNullOrEmpty(text) || _sendText == null) return;
        try
        {
            var note = await _sendText(Peer, text);
            try { _store.Add(_peerKey, Peer.Name, "out", text); } catch { }
            _messages.Add(ParseStoredMessage("out", text));
            if (!string.IsNullOrEmpty(note))
                AppendSystem(note);
            Input.Clear();
            ScrollToEnd();
        }
        catch (Exception ex)
        {
            System.Windows.MessageBox.Show(ex.Message, "FeiQ 2026",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    /// <summary>外部（如 outbox 补发）追加发出的消息气泡，可选是否再写库</summary>
    public void AppendOutgoing(string text, bool persist = true)
    {
        if (persist)
        {
            try { _store.Add(_peerKey, Peer.Name, "out", text); } catch { }
        }
        _messages.Add(ParseStoredMessage("out", text));
        ScrollToEnd();
    }

    private async void SendFile_Click(object sender, RoutedEventArgs e)
    {
        if (_sendFile == null) return;
        var dlg = new Microsoft.Win32.OpenFileDialog
        {
            Title = "选择要发送的文件",
            CheckFileExists = true,
            Multiselect = true,
            Filter = "所有文件|*.*|图片|*.jpg;*.jpeg;*.png;*.gif;*.bmp;*.webp|音乐|*.mp3;*.wav;*.flac;*.aac;*.m4a|视频|*.mp4;*.avi;*.mkv;*.mov;*.wmv"
        };
        if (dlg.ShowDialog() != true) return;
        await SendFilesAsync(dlg.FileNames);
    }

    // ── 拖拽发文件 ──────────────────────────────────────────

    private void Window_PreviewDragOver(object sender, System.Windows.DragEventArgs e)
    {
        if (HasFileDrop(e.Data))
        {
            e.Effects = System.Windows.DragDropEffects.Copy;
            DropOverlay.Visibility = WpfVisibility.Visible;
        }
        else
        {
            e.Effects = System.Windows.DragDropEffects.None;
            DropOverlay.Visibility = WpfVisibility.Collapsed;
        }
        e.Handled = true;
    }

    private async void Window_Drop(object sender, System.Windows.DragEventArgs e)
    {
        DropOverlay.Visibility = WpfVisibility.Collapsed;
        if (_sendFile == null) return;
        var paths = GetDroppedFilePaths(e.Data);
        if (paths.Count == 0) return;
        e.Handled = true;
        await SendFilesAsync(paths);
    }

    private void Window_PreviewDragLeave(object sender, System.Windows.DragEventArgs e)
    {
        // 只有真正离开窗口客户区时才隐藏（避免移入子控件反复闪烁）
        var pos = e.GetPosition(this);
        if (pos.X <= 0 || pos.Y <= 0 || pos.X >= ActualWidth || pos.Y >= ActualHeight)
            DropOverlay.Visibility = WpfVisibility.Collapsed;
    }

    private static bool HasFileDrop(System.Windows.IDataObject data)
    {
        if (!data.GetDataPresent(System.Windows.DataFormats.FileDrop)) return false;
        try
        {
            var arr = data.GetData(System.Windows.DataFormats.FileDrop) as string[];
            return arr != null && arr.Any(p => File.Exists(p));
        }
        catch { return false; }
    }

    private static List<string> GetDroppedFilePaths(System.Windows.IDataObject data)
    {
        var result = new List<string>();
        if (!data.GetDataPresent(System.Windows.DataFormats.FileDrop)) return result;
        try
        {
            var arr = data.GetData(System.Windows.DataFormats.FileDrop) as string[];
            if (arr == null) return result;
            foreach (var p in arr)
            {
                if (File.Exists(p))
                    result.Add(p);
                // 目录：展开一层普通文件（不递归，避免拖整个盘）
                else if (Directory.Exists(p))
                {
                    try
                    {
                        foreach (var f in Directory.EnumerateFiles(p))
                            result.Add(f);
                    }
                    catch { /* 权限等忽略 */ }
                }
            }
        }
        catch { /* ignore */ }
        return result;
    }

    /// <summary>批量发送本地文件路径（对话框 / 拖拽共用）</summary>
    private async Task SendFilesAsync(IEnumerable<string> paths)
    {
        if (_sendFile == null) return;
        var list = paths.Where(File.Exists).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        if (list.Count == 0) return;

        var errors = new List<string>();
        foreach (var path in list)
        {
            var name = Path.GetFileName(path);
            var kind = DetectFileKind(name);
            try
            {
                // 先放气泡并标「发送中」，进度在气泡内更新，多文件互不抢
                var bubble = MakeFileBubble(path, name, kind, isOutgoing: true);
                long size = 0;
                try { size = new FileInfo(path).Length; } catch { }
                bubble.SetTransferring(true, size, isSend: true);
                _messages.Add(bubble);
                ScrollToEnd();

                await _sendFile(Peer, path);
                var note = kind switch
                {
                    FileKind.Image => $"[图片] {name}",
                    FileKind.Audio => $"[音乐] {name}",
                    FileKind.Video => $"[视频] {name}",
                    _ => $"[文件] {name}"
                };
                var storeText = $"{note}|{path}";
                try { _store.Add(_peerKey, Peer.Name, "out", storeText); } catch { }
                ScrollToEnd();
            }
            catch (Exception ex)
            {
                errors.Add($"{name}: {ex.Message}");
            }
        }

        if (errors.Count > 0)
        {
            var msg = errors.Count == 1
                ? errors[0]
                : $"有 {errors.Count} 个文件发送失败：\n" + string.Join("\n", errors.Take(5));
            System.Windows.MessageBox.Show(msg, "发文件失败",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void Menu_Click(object sender, RoutedEventArgs e)
    {
        if (sender is WpfButton btn && btn.ContextMenu != null)
        {
            btn.ContextMenu.PlacementTarget = btn;
            btn.ContextMenu.IsOpen = true;
        }
    }

    private void ClearHistory_Click(object sender, RoutedEventArgs e)
    {
        var result = System.Windows.MessageBox.Show(
            $"确定清空与 {Peer.Name} 的本地聊天记录？此操作不可恢复。",
            "清空聊天记录",
            MessageBoxButton.YesNo,
            MessageBoxImage.Warning);
        if (result != MessageBoxResult.Yes) return;

        try { _store.Clear(_peerKey, Peer.Name); } catch { /* ignore */ }
        _messages.Clear();
    }

    private void Media_Click(object sender, MouseButtonEventArgs e)
    {
        if (sender is FrameworkElement fe && fe.DataContext is ChatBubble bubble)
        {
            if (bubble.IsFileMissing)
            {
                System.Windows.MessageBox.Show("本地文件已失效或不存在。", "FeiQ 2026");
                return;
            }
            var path = bubble.MediaPath;
            if (string.IsNullOrEmpty(path) || !File.Exists(path)) return;
            try
            {
                Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
            }
            catch (Exception ex)
            {
                System.Windows.MessageBox.Show($"无法打开：{ex.Message}", "FeiQ 2026");
            }
        }
    }

    private void Url_Click(object sender, MouseButtonEventArgs e)
    {
        // 若正在拖选文字则不打开链接
        if (sender is System.Windows.Controls.TextBox tb && tb.SelectionLength > 0)
            return;

        if (sender is FrameworkElement fe && fe.DataContext is ChatBubble bubble
            && bubble.Kind == FileKind.Url && !string.IsNullOrEmpty(bubble.MediaPath))
        {
            try
            {
                Process.Start(new ProcessStartInfo(bubble.MediaPath) { UseShellExecute = true });
            }
            catch { /* ignore */ }
        }
    }

    /// <summary>气泡文字双击全选，方便一键复制</summary>
    private void BubbleText_DoubleClick(object sender, MouseButtonEventArgs e)
    {
        if (sender is System.Windows.Controls.TextBox tb)
        {
            tb.SelectAll();
            e.Handled = true;
        }
    }

    private void OpenFolder_Click(object sender, RoutedEventArgs e)
    {
        if (sender is WpfMenuItem mi && mi.Parent is WpfContextMenu cm
            && cm.PlacementTarget is FrameworkElement fe
            && fe.DataContext is ChatBubble bubble
            && !string.IsNullOrEmpty(bubble.MediaPath)
            && File.Exists(bubble.MediaPath))
        {
            try
            {
                Process.Start(new ProcessStartInfo
                {
                    FileName = "explorer.exe",
                    Arguments = $"/select,\"{bubble.MediaPath}\"",
                    UseShellExecute = true
                });
            }
            catch (Exception ex)
            {
                System.Windows.MessageBox.Show($"无法打开所在位置：{ex.Message}", "FeiQ 2026");
            }
        }
    }

    private void CopyBubble_Click(object sender, RoutedEventArgs e)
    {
        if (sender is WpfMenuItem mi && mi.Parent is WpfContextMenu cm
            && cm.PlacementTarget is FrameworkElement fe
            && fe.DataContext is ChatBubble bubble
            && !string.IsNullOrWhiteSpace(bubble.Text))
        {
            try
            {
                System.Windows.Clipboard.SetText(bubble.Text);
            }
            catch { /* ignore */ }
        }
    }

    private void ScrollToEnd()
    {
        Dispatcher.BeginInvoke(() => MsgScroll.ScrollToEnd());
    }

    private void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
    {
    }

    internal static FileKind DetectFileKind(string fileNameOrPath)
    {
        var ext = Path.GetExtension(fileNameOrPath);
        if (ImageExts.Contains(ext)) return FileKind.Image;
        if (AudioExts.Contains(ext)) return FileKind.Audio;
        if (VideoExts.Contains(ext)) return FileKind.Video;
        return FileKind.File;
    }

    private ChatBubble ParseStoredMessage(string direction, string text, DateTime? at = null)
    {
        if (direction == "sys")
            return MakeSystem(text, at);

        if (text.StartsWith("[") && text.Contains('|'))
        {
            var pipe = text.LastIndexOf('|');
            var head = text[..pipe];
            var path = text[(pipe + 1)..];
            var name = Path.GetFileName(path);
            FileKind kind = FileKind.File;
            if (head.StartsWith("[图片]")) kind = FileKind.Image;
            else if (head.StartsWith("[音乐]")) kind = FileKind.Audio;
            else if (head.StartsWith("[视频]")) kind = FileKind.Video;
            else if (head.StartsWith("[文件]")) kind = FileKind.File;
            else kind = DetectFileKind(name);

            return MakeFileBubble(path, name, kind, isOutgoing: direction == "out", at);
        }

        if (text.StartsWith("[文件]") || text.StartsWith("[图片]") || text.StartsWith("[音乐]") || text.StartsWith("[视频]"))
        {
            var name = text;
            if (text.StartsWith("[文件] ")) name = text[5..].Trim();
            else if (text.StartsWith("[图片] ")) name = text[5..].Trim();
            else if (text.StartsWith("[音乐] ")) name = text[5..].Trim();
            else if (text.StartsWith("[视频] ")) name = text[5..].Trim();
            var kind = DetectFileKind(name);
            return MakeFileBubble(null, name, kind, isOutgoing: direction == "out", at);
        }

        var m = UrlRegex.Match(text);
        if (m.Success && m.Value.Length >= 10 && (text.Trim() == m.Value || text.Trim().StartsWith("http")))
        {
            return MakeUrlBubble(m.Value, isOutgoing: direction == "out", at);
        }

        return direction == "out"
            ? MakeTextBubble(text, isOutgoing: true, at)
            : MakeTextBubble(text, isOutgoing: false, at);
    }

    private ChatBubble MakeSystem(string text, DateTime? at = null) => new()
    {
        Text = text,
        Time = (at ?? DateTime.Now).ToString("HH:mm:ss"),
        Align = WpfHorizontalAlignment.Center,
        BubbleBrush = new SolidColorBrush(MediaColor.FromRgb(0xE0, 0xE0, 0xE0)),
        TextBrush = new SolidColorBrush(MediaColor.FromRgb(0x66, 0x66, 0x66)),
        Kind = FileKind.Text,
        IsSystem = true
    };

    private ChatBubble MakeTextBubble(string text, bool isOutgoing, DateTime? at = null)
    {
        var segs = BuildSegments(text);
        return new ChatBubble
        {
            Text = text,
            Time = (at ?? DateTime.Now).ToString("HH:mm:ss"),
            Align = isOutgoing ? WpfHorizontalAlignment.Right : WpfHorizontalAlignment.Left,
            BubbleBrush = isOutgoing
                ? new SolidColorBrush(MediaColor.FromRgb(0x95, 0xEC, 0x69))
                : MediaBrushes.White,
            TextBrush = MediaBrushes.Black,
            Kind = FileKind.Text,
            IsOutgoing = isOutgoing,
            PeerAvatarLetter = _peerLetter,
            PeerAvatarImage = _peerAvatarImage,
            SelfAvatarLetter = _selfLetter,
            SelfAvatarImage = _selfAvatarImage,
            Segments = segs
        };
    }

    /// <summary>把文本拆成文字/表情图片段（用于气泡混排）</summary>
    private static List<EmojiSegment> BuildSegments(string? text)
    {
        var list = new List<EmojiSegment>();
        if (string.IsNullOrEmpty(text)) return list;
        foreach (var (isEmoji, s, img) in Services.EmojiCatalog.ParseSegments(text))
        {
            list.Add(new EmojiSegment
            {
                Text = isEmoji ? "" : s,
                Image = img
            });
        }
        return list;
    }

    private ChatBubble MakeUrlBubble(string url, bool isOutgoing, DateTime? at = null) => new()
    {
        Text = "🔗 " + url,
        Time = (at ?? DateTime.Now).ToString("HH:mm:ss"),
        Align = isOutgoing ? WpfHorizontalAlignment.Right : WpfHorizontalAlignment.Left,
        BubbleBrush = isOutgoing
            ? new SolidColorBrush(MediaColor.FromRgb(0x95, 0xEC, 0x69))
            : MediaBrushes.White,
        TextBrush = new SolidColorBrush(MediaColor.FromRgb(0x12, 0xB7, 0xF5)),
        Kind = FileKind.Url,
        MediaPath = url,
        IsOutgoing = isOutgoing,
        PeerAvatarLetter = _peerLetter,
        PeerAvatarImage = _peerAvatarImage,
        SelfAvatarLetter = _selfLetter,
        SelfAvatarImage = _selfAvatarImage
    };

    private ChatBubble MakeFileBubble(string? path, string fileName, FileKind kind, bool isOutgoing, DateTime? at = null)
    {
        var missing = !string.IsNullOrEmpty(path) && !File.Exists(path);

        ImageSource? img = null;
        if (!missing && kind == FileKind.Image && !string.IsNullOrEmpty(path))
        {
            try
            {
                var bmp = new BitmapImage();
                bmp.BeginInit();
                bmp.CacheOption = BitmapCacheOption.OnLoad;
                bmp.DecodePixelWidth = 480;
                bmp.UriSource = new Uri(path, UriKind.Absolute);
                bmp.EndInit();
                bmp.Freeze();
                img = bmp;
            }
            catch { /* fall to card */ }
        }

        var (icon, label) = kind switch
        {
            FileKind.Image => ("🖼️", "图片"),
            FileKind.Audio => ("🎵", "音乐"),
            FileKind.Video => ("🎬", "视频"),
            _ => ("📄", "文件")
        };

        if (missing)
        {
            icon = "⚠️";
            label = "文件已失效";
        }

        return new ChatBubble
        {
            Text = missing ? $"⚠️ {fileName}（已失效）" : $"{icon} {fileName}",
            Time = (at ?? DateTime.Now).ToString("HH:mm:ss"),
            Align = isOutgoing ? WpfHorizontalAlignment.Right : WpfHorizontalAlignment.Left,
            BubbleBrush = isOutgoing
                ? new SolidColorBrush(MediaColor.FromRgb(0x95, 0xEC, 0x69))
                : MediaBrushes.White,
            TextBrush = MediaBrushes.Black,
            Kind = kind,
            MediaPath = path,
            FileName = fileName,
            TypeIcon = icon,
            TypeLabel = label,
            ImageSource = img,
            IsOutgoing = isOutgoing,
            IsFileMissing = missing,
            PeerAvatarLetter = _peerLetter,
            PeerAvatarImage = _peerAvatarImage,
            SelfAvatarLetter = _selfLetter,
            SelfAvatarImage = _selfAvatarImage
        };
    }
}

public enum FileKind
{
    Text,
    Image,
    Audio,
    Video,
    File,
    Url
}

/// <summary>气泡内混排片段：纯文字 或 表情图</summary>
public sealed class EmojiSegment
{
    public string Text { get; init; } = "";
    public ImageSource? Image { get; init; }
    public WpfVisibility TextVisibility => Image == null ? WpfVisibility.Visible : WpfVisibility.Collapsed;
    public WpfVisibility ImageVisibility => Image != null ? WpfVisibility.Visible : WpfVisibility.Collapsed;
}

public sealed class ChatBubble : System.ComponentModel.INotifyPropertyChanged
{
    public event System.ComponentModel.PropertyChangedEventHandler? PropertyChanged;
    private void Notify(string name) =>
        PropertyChanged?.Invoke(this, new System.ComponentModel.PropertyChangedEventArgs(name));

    public string Text { get; init; } = "";
    public string Time { get; init; } = "";
    public WpfHorizontalAlignment Align { get; init; }
    public MediaBrush BubbleBrush { get; init; } = MediaBrushes.White;
    public MediaBrush TextBrush { get; init; } = MediaBrushes.Black;

    public FileKind Kind { get; init; } = FileKind.Text;
    public string? MediaPath { get; set; }
    public string FileName { get; init; } = "";
    public string TypeIcon { get; set; } = "";
    public string TypeLabel { get; set; } = "";
    public ImageSource? ImageSource { get; set; }

    /// <summary>富文本片段（有表情图时用）</summary>
    public List<EmojiSegment> Segments { get; init; } = new();

    public bool IsSystem { get; init; }
    public bool IsOutgoing { get; init; }
    public bool IsFileMissing { get; set; }
    public string PeerAvatarLetter { get; init; } = "?";
    public string SelfAvatarLetter { get; init; } = "我";
    public ImageSource? SelfAvatarImage { get; init; }
    public ImageSource? PeerAvatarImage { get; init; }

    // —— 气泡内传输进度（类音乐播放器 slider）——
    private bool _isTransferring;
    private double _transferPercent;
    private string _transferStatusText = "";
    private long _transferTotal;
    private bool _isSendTransfer;

    // —— 普通文件待确认（气泡内接收/拒绝）——
    private bool _isPendingOffer;
    public IncomingFileOffer? PendingOffer { get; private set; }
    public Func<IncomingFileOffer, Task>? PendingAccept { get; private set; }

    public bool IsPendingOffer
    {
        get => _isPendingOffer;
        private set
        {
            if (_isPendingOffer == value) return;
            _isPendingOffer = value;
            Notify(nameof(IsPendingOffer));
            Notify(nameof(OfferActionsVisibility));
            Notify(nameof(FileCardVisibility));
        }
    }

    public WpfVisibility OfferActionsVisibility =>
        IsPendingOffer ? WpfVisibility.Visible : WpfVisibility.Collapsed;

    public void SetPendingOffer(IncomingFileOffer offer, long size, Func<IncomingFileOffer, Task> onAccept)
    {
        PendingOffer = offer;
        PendingAccept = onAccept;
        IsPendingOffer = true;
        TypeLabel = size > 0 ? $"待接收 · {FormatSize(size)}" : "待接收";
        Notify(nameof(TypeLabel));
    }

    public void ClearPendingOffer(bool startTransfer)
    {
        var size = PendingOffer?.Info.Size ?? _transferTotal;
        PendingOffer = null;
        PendingAccept = null;
        IsPendingOffer = false;
        if (startTransfer)
            SetTransferring(true, size, isSend: false);
    }

    public void RejectOffer()
    {
        PendingOffer = null;
        PendingAccept = null;
        IsPendingOffer = false;
        TypeIcon = "🚫";
        TypeLabel = "已拒绝";
        Notify(nameof(TypeIcon));
        Notify(nameof(TypeLabel));
    }

    public bool IsTransferring
    {
        get => _isTransferring;
        private set
        {
            if (_isTransferring == value) return;
            _isTransferring = value;
            Notify(nameof(IsTransferring));
            Notify(nameof(TransferProgressVisibility));
            Notify(nameof(OpenFolderVisibility));
            Notify(nameof(ImageVisibility));
            Notify(nameof(FileCardVisibility));
        }
    }

    public double TransferPercent
    {
        get => _transferPercent;
        private set
        {
            if (Math.Abs(_transferPercent - value) < 0.05) return;
            _transferPercent = value;
            Notify(nameof(TransferPercent));
        }
    }

    public string TransferStatusText
    {
        get => _transferStatusText;
        private set
        {
            if (_transferStatusText == value) return;
            _transferStatusText = value;
            Notify(nameof(TransferStatusText));
        }
    }

    public long TransferTotal => _transferTotal;

    public WpfVisibility TransferProgressVisibility =>
        IsTransferring ? WpfVisibility.Visible : WpfVisibility.Collapsed;

    public void SetTransferring(bool transferring, long totalBytes, bool isSend)
    {
        _isSendTransfer = isSend;
        _transferTotal = totalBytes;
        IsTransferring = transferring;
        if (transferring)
        {
            TransferPercent = 0;
            TransferStatusText = isSend
                ? (totalBytes > 0 ? $"等待发送 · {FormatSize(totalBytes)}" : "等待发送…")
                : (totalBytes > 0 ? $"准备接收 · {FormatSize(totalBytes)}" : "准备接收…");
        }
    }

    public void UpdateProgress(long received, long total)
    {
        if (total > 0) _transferTotal = total;
        var t = _transferTotal > 0 ? _transferTotal : 1;
        var pct = Math.Clamp(received * 100.0 / t, 0, 100);
        IsTransferring = true;
        TransferPercent = pct;
        var verb = _isSendTransfer ? "发送中" : "接收中";
        TransferStatusText = $"{verb} {pct:0}%  {FormatSize(received)}/{FormatSize(_transferTotal)}";
    }

    public void FinishTransfer(bool success, string? error)
    {
        if (!success)
        {
            TransferPercent = 0;
            TransferStatusText = string.IsNullOrEmpty(error) ? "传输失败" : $"失败：{error}";
            TypeLabel = "传输失败";
            TypeIcon = "⚠️";
            Notify(nameof(TypeLabel));
            Notify(nameof(TypeIcon));
            // 短暂保留失败状态再隐藏进度条
            IsTransferring = false;
            return;
        }
        TransferPercent = 100;
        TransferStatusText = _isSendTransfer ? "发送完成" : "接收完成";
        IsTransferring = false;
    }

    /// <summary>接收完成：写入本地路径，图片则加载缩略图</summary>
    public void CompleteReceive(string savedPath, bool fromLocalCache = false)
    {
        MediaPath = savedPath;
        IsFileMissing = !File.Exists(savedPath);
        if (!IsFileMissing && Kind == FileKind.Image)
        {
            try
            {
                var bmp = new BitmapImage();
                bmp.BeginInit();
                bmp.CacheOption = BitmapCacheOption.OnLoad;
                bmp.DecodePixelWidth = 480;
                bmp.UriSource = new Uri(savedPath, UriKind.Absolute);
                bmp.EndInit();
                bmp.Freeze();
                ImageSource = bmp;
                Notify(nameof(ImageSource));
            }
            catch { /* keep card */ }
        }
        var (icon, label) = Kind switch
        {
            FileKind.Image => ("🖼️", "图片"),
            FileKind.Audio => ("🎵", "音乐"),
            FileKind.Video => ("🎬", "视频"),
            _ => ("📄", "文件")
        };
        if (IsFileMissing) { icon = "⚠️"; label = "文件已失效"; }
        else if (fromLocalCache) { label = "本地已有 · 秒收"; }
        TypeIcon = icon;
        TypeLabel = label;
        Notify(nameof(TypeIcon));
        Notify(nameof(TypeLabel));
        Notify(nameof(MediaPath));
        Notify(nameof(IsFileMissing));
        Notify(nameof(ImageVisibility));
        Notify(nameof(FileCardVisibility));
        Notify(nameof(OpenFolderVisibility));
        if (fromLocalCache)
        {
            TransferPercent = 100;
            TransferStatusText = "本地已有 · 秒收";
            IsTransferring = false;
        }
        else
        {
            FinishTransfer(true, null);
        }
    }

    private static string FormatSize(long bytes)
    {
        if (bytes < 1024) return $"{bytes} B";
        if (bytes < 1024 * 1024) return $"{bytes / 1024.0:0.#} KB";
        if (bytes < 1024L * 1024 * 1024) return $"{bytes / (1024.0 * 1024):0.##} MB";
        return $"{bytes / (1024.0 * 1024 * 1024):0.##} GB";
    }

    public MediaBrush PeerAvatarBg { get; } = new SolidColorBrush(MediaColor.FromRgb(0x12, 0xB7, 0xF5));
    public MediaBrush SelfAvatarBg { get; } = new SolidColorBrush(MediaColor.FromRgb(0x07, 0xC1, 0x60));

    public WpfVisibility SystemVisibility => IsSystem ? WpfVisibility.Visible : WpfVisibility.Collapsed;
    public WpfVisibility RowVisibility => IsSystem ? WpfVisibility.Collapsed : WpfVisibility.Visible;

    public WpfVisibility PeerAvatarVisibility =>
        !IsSystem && !IsOutgoing ? WpfVisibility.Visible : WpfVisibility.Collapsed;
    public WpfVisibility SelfAvatarVisibility =>
        !IsSystem && IsOutgoing ? WpfVisibility.Visible : WpfVisibility.Collapsed;

    public WpfVisibility PeerAvatarImageVisibility =>
        PeerAvatarImage != null ? WpfVisibility.Visible : WpfVisibility.Collapsed;
    public WpfVisibility PeerAvatarLetterVisibility =>
        PeerAvatarImage == null ? WpfVisibility.Visible : WpfVisibility.Collapsed;

    public WpfVisibility SelfAvatarImageVisibility =>
        SelfAvatarImage != null ? WpfVisibility.Visible : WpfVisibility.Collapsed;
    public WpfVisibility SelfAvatarLetterVisibility =>
        SelfAvatarImage == null ? WpfVisibility.Visible : WpfVisibility.Collapsed;

    public WpfVisibility ImageVisibility =>
        !IsFileMissing && !IsTransferring && Kind == FileKind.Image && ImageSource != null
            ? WpfVisibility.Visible
            : WpfVisibility.Collapsed;

    public WpfVisibility FileCardVisibility
    {
        get
        {
            if (IsFileMissing || IsTransferring || IsPendingOffer) return WpfVisibility.Visible;
            if (Kind == FileKind.Audio || Kind == FileKind.Video || Kind == FileKind.File)
                return WpfVisibility.Visible;
            if (Kind == FileKind.Image && ImageSource == null)
                return WpfVisibility.Visible;
            return WpfVisibility.Collapsed;
        }
    }

    /// <summary>有表情图片段时用富文本，否则用普通 TextBlock</summary>
    public bool HasRichEmoji =>
        (Kind == FileKind.Text || Kind == FileKind.Url) && Segments.Any(s => s.Image != null);

    public WpfVisibility PlainTextVisibility =>
        (Kind == FileKind.Text || Kind == FileKind.Url) && !HasRichEmoji
            ? WpfVisibility.Visible
            : WpfVisibility.Collapsed;

    public WpfVisibility RichTextVisibility =>
        HasRichEmoji ? WpfVisibility.Visible : WpfVisibility.Collapsed;

    public WpfVisibility TextVisibility =>
        Kind == FileKind.Text || Kind == FileKind.Url
            ? WpfVisibility.Visible
            : WpfVisibility.Collapsed;

    public WpfCursor TextCursor =>
        Kind == FileKind.Url ? WpfCursors.Hand : WpfCursors.Arrow;

    public WpfVisibility OpenFolderVisibility =>
        !IsTransferring && !IsFileMissing && !string.IsNullOrEmpty(MediaPath) && File.Exists(MediaPath) && Kind != FileKind.Url
            ? WpfVisibility.Visible
            : WpfVisibility.Collapsed;
}
