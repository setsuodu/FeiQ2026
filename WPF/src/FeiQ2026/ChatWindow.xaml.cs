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
        _selfAvatarImage = LoadAvatarImage(settings.AvatarPath);
        _peerAvatarImage = AvatarCache.LoadImage(_peerKey);

        Title = $"{peer.Name} - FeiQ 2026";
        PeerNameText.Text = peer.Name;
        PeerIpText.Text = peer.Ip.ToString();
        AvatarText.Text = _peerLetter;
        MsgList.ItemsSource = _messages;

        LoadHistory();
    }

    /// <summary>收到对方头像同步后刷新气泡侧头像（新消息生效；历史保持字母亦可接受）</summary>
    public void UpdatePeerAvatar(string? path)
    {
        _peerAvatarImage = LoadAvatarImage(path);
    }

    private static ImageSource? LoadAvatarImage(string? path)
    {
        if (string.IsNullOrEmpty(path) || !File.Exists(path)) return null;
        try
        {
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad;
            bmp.DecodePixelWidth = 72;
            bmp.UriSource = new Uri(path, UriKind.Absolute);
            bmp.EndInit();
            bmp.Freeze();
            return bmp;
        }
        catch
        {
            return null;
        }
    }

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
    /// 表情选择弹窗。优先显示 Assets/Emoji 下的图片（文件名=codepoint），没有则回退 Unicode。
    /// 点击后仍插入 Unicode 字符（兼容飞秋2013 / Android），不发 [emoji:xxx]。
    /// 映射表见 Services/EmojiCatalog.cs
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
            Effect = new System.Windows.Media.Effects.DropShadowEffect
            {
                BlurRadius = 8,
                ShadowDepth = 2,
                Opacity = 0.25
            }
        };

        var wrap = new System.Windows.Controls.WrapPanel { Width = 280 };

        foreach (var entry in Services.EmojiCatalog.Preset)
        {
            var img = Services.EmojiCatalog.GetImage(entry);
            object content;
            if (img != null)
            {
                content = new System.Windows.Controls.Image
                {
                    Source = img,
                    Width = 28,
                    Height = 28,
                    Stretch = Stretch.Uniform
                };
            }
            else
            {
                content = entry.Unicode; // 没素材就显示 Unicode
            }

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
                Tag = entry.Unicode,
                ToolTip = $"{entry.ShortName} ({entry.Codepoint})"
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

        border.Child = wrap;
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
            Multiselect = false,
            Filter = "所有文件|*.*|图片|*.jpg;*.jpeg;*.png;*.gif;*.bmp;*.webp|音乐|*.mp3;*.wav;*.flac;*.aac;*.m4a|视频|*.mp4;*.avi;*.mkv;*.mov;*.wmv"
        };
        if (dlg.ShowDialog() != true) return;
        var path = dlg.FileName;
        var name = Path.GetFileName(path);
        var kind = DetectFileKind(name);
        try
        {
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
            _messages.Add(MakeFileBubble(path, name, kind, isOutgoing: true));
            ScrollToEnd();
        }
        catch (Exception ex)
        {
            System.Windows.MessageBox.Show(ex.Message, "发文件失败",
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

    private ChatBubble MakeTextBubble(string text, bool isOutgoing, DateTime? at = null) => new()
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
        SelfAvatarImage = _selfAvatarImage
    };

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

public sealed class ChatBubble
{
    public string Text { get; init; } = "";
    public string Time { get; init; } = "";
    public WpfHorizontalAlignment Align { get; init; }
    public MediaBrush BubbleBrush { get; init; } = MediaBrushes.White;
    public MediaBrush TextBrush { get; init; } = MediaBrushes.Black;

    public FileKind Kind { get; init; } = FileKind.Text;
    public string? MediaPath { get; init; }
    public string FileName { get; init; } = "";
    public string TypeIcon { get; init; } = "";
    public string TypeLabel { get; init; } = "";
    public ImageSource? ImageSource { get; init; }

    public bool IsSystem { get; init; }
    public bool IsOutgoing { get; init; }
    public bool IsFileMissing { get; init; }
    public string PeerAvatarLetter { get; init; } = "?";
    public string SelfAvatarLetter { get; init; } = "我";
    public ImageSource? SelfAvatarImage { get; init; }
    public ImageSource? PeerAvatarImage { get; init; }

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
        !IsFileMissing && Kind == FileKind.Image && ImageSource != null
            ? WpfVisibility.Visible
            : WpfVisibility.Collapsed;

    public WpfVisibility FileCardVisibility
    {
        get
        {
            if (IsFileMissing) return WpfVisibility.Visible;
            if (Kind == FileKind.Audio || Kind == FileKind.Video || Kind == FileKind.File)
                return WpfVisibility.Visible;
            if (Kind == FileKind.Image && ImageSource == null)
                return WpfVisibility.Visible;
            return WpfVisibility.Collapsed;
        }
    }

    public WpfVisibility TextVisibility =>
        Kind == FileKind.Text || Kind == FileKind.Url
            ? WpfVisibility.Visible
            : WpfVisibility.Collapsed;

    public WpfCursor TextCursor =>
        Kind == FileKind.Url ? WpfCursors.Hand : WpfCursors.Arrow;

    public WpfVisibility OpenFolderVisibility =>
        !IsFileMissing && !string.IsNullOrEmpty(MediaPath) && File.Exists(MediaPath) && Kind != FileKind.Url
            ? WpfVisibility.Visible
            : WpfVisibility.Collapsed;
}
