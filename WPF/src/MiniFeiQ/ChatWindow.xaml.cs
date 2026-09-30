using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using MiniFeiQ.Services;
using MediaColor = System.Windows.Media.Color;
using MediaBrush = System.Windows.Media.Brush;
using MediaBrushes = System.Windows.Media.Brushes;
using WpfHorizontalAlignment = System.Windows.HorizontalAlignment;
using WpfVisibility = System.Windows.Visibility;
using WpfCursor = System.Windows.Input.Cursor;
using WpfCursors = System.Windows.Input.Cursors;

namespace MiniFeiQ;

public partial class ChatWindow : Window
{
    public Peer Peer { get; }
    private readonly ChatStore _store;
    private readonly string _peerKey;
    private readonly ObservableCollection<ChatBubble> _messages = new();
    private readonly Func<Peer, string, Task>? _sendText;
    private readonly Func<Peer, string, Task>? _sendFile;

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
        Func<Peer, string, Task>? sendText,
        Func<Peer, string, Task>? sendFile)
    {
        InitializeComponent();
        Peer = peer;
        _store = store;
        _peerKey = !string.IsNullOrWhiteSpace(peer.HostName)
            ? "host:" + peer.HostName.Trim().ToLowerInvariant()
            : "ip:" + peer.Ip;
        _sendText = sendText;
        _sendFile = sendFile;

        Title = $"{peer.Name} - FeiQ 2026";
        PeerNameText.Text = peer.Name;
        PeerIpText.Text = peer.Ip.ToString();
        AvatarText.Text = string.IsNullOrEmpty(peer.Name) ? "?" : peer.Name[..1].ToUpperInvariant();
        MsgList.ItemsSource = _messages;

        LoadHistory();
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
        _messages.Add(ChatBubble.System(text));
        ScrollToEnd();
    }

    /// <summary>收到文件保存完成后调用，展示富媒体气泡</summary>
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
        // 存库用带路径的格式，方便历史恢复
        var storeText = $"{note}|{filePath}";
        try { _store.Add(_peerKey, Peer.Name, "in", storeText); } catch { }
        _messages.Add(ChatBubble.FromFile(filePath, fileName, kind, isOutgoing: false));
        ScrollToEnd();
    }

    private async void Send_Click(object sender, RoutedEventArgs e)
    {
        var text = Input.Text?.Trim();
        if (string.IsNullOrEmpty(text) || _sendText == null) return;
        try
        {
            await _sendText(Peer, text);
            try { _store.Add(_peerKey, Peer.Name, "out", text); } catch { }
            _messages.Add(ParseStoredMessage("out", text));
            Input.Clear();
            ScrollToEnd();
        }
        catch (Exception ex)
        {
            System.Windows.MessageBox.Show(ex.Message, "FeiQ 2026",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
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
            // 存库带本地路径，方便自己侧预览
            var storeText = $"{note}|{path}";
            try { _store.Add(_peerKey, Peer.Name, "out", storeText); } catch { }
            _messages.Add(ChatBubble.FromFile(path, name, kind, isOutgoing: true));
            ScrollToEnd();
        }
        catch (Exception ex)
        {
            System.Windows.MessageBox.Show(ex.Message, "发文件失败",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void Media_Click(object sender, MouseButtonEventArgs e)
    {
        if (sender is FrameworkElement fe && fe.DataContext is ChatBubble bubble)
        {
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

    private void ScrollToEnd()
    {
        Dispatcher.BeginInvoke(() => MsgScroll.ScrollToEnd());
    }

    private void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
    {
    }

    // ---------- 类型识别 ----------

    internal static FileKind DetectFileKind(string fileNameOrPath)
    {
        var ext = Path.GetExtension(fileNameOrPath);
        if (ImageExts.Contains(ext)) return FileKind.Image;
        if (AudioExts.Contains(ext)) return FileKind.Audio;
        if (VideoExts.Contains(ext)) return FileKind.Video;
        return FileKind.File;
    }

    private static ChatBubble ParseStoredMessage(string direction, string text, DateTime? at = null)
    {
        // 系统消息
        if (direction == "sys")
            return ChatBubble.System(text, at);

        // 带本地路径的富媒体： [图片] name|/path/to/file
        if (text.StartsWith("[") && text.Contains('|'))
        {
            var pipe = text.LastIndexOf('|');
            var head = text[..pipe];
            var path = text[(pipe + 1)..];
            var name = Path.GetFileName(path);
            // 从头部提取类型
            FileKind kind = FileKind.File;
            if (head.StartsWith("[图片]")) kind = FileKind.Image;
            else if (head.StartsWith("[音乐]")) kind = FileKind.Audio;
            else if (head.StartsWith("[视频]")) kind = FileKind.Video;
            else if (head.StartsWith("[文件]")) kind = FileKind.File;
            else kind = DetectFileKind(name);

            return ChatBubble.FromFile(path, name, kind, isOutgoing: direction == "out", at);
        }

        // 纯 [文件] name （旧格式，无路径）
        if (text.StartsWith("[文件]") || text.StartsWith("[图片]") || text.StartsWith("[音乐]") || text.StartsWith("[视频]"))
        {
            var name = text;
            if (text.StartsWith("[文件] ")) name = text[5..].Trim();
            else if (text.StartsWith("[图片] ")) name = text[5..].Trim();
            else if (text.StartsWith("[音乐] ")) name = text[5..].Trim();
            else if (text.StartsWith("[视频] ")) name = text[5..].Trim();
            var kind = DetectFileKind(name);
            return ChatBubble.FromFile(null, name, kind, isOutgoing: direction == "out", at);
        }

        // URL 检测
        var m = UrlRegex.Match(text);
        if (m.Success && m.Value.Length >= 10 && (text.Trim() == m.Value || text.Trim().StartsWith("http")))
        {
            return ChatBubble.FromUrl(m.Value, isOutgoing: direction == "out", at);
        }

        // 普通文本
        return direction == "out"
            ? ChatBubble.Outgoing(text, at)
            : ChatBubble.Incoming(text, at);
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

    public WpfVisibility ImageVisibility =>
        Kind == FileKind.Image && ImageSource != null
            ? WpfVisibility.Visible
            : WpfVisibility.Collapsed;

    public WpfVisibility FileCardVisibility
    {
        get
        {
            if (Kind == FileKind.Audio || Kind == FileKind.Video || Kind == FileKind.File)
                return WpfVisibility.Visible;
            // 图片加载失败时也显示卡片
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

    public static ChatBubble Incoming(string text, DateTime? at = null) => new()
    {
        Text = text,
        Time = (at ?? DateTime.Now).ToString("HH:mm:ss"),
        Align = WpfHorizontalAlignment.Left,
        BubbleBrush = MediaBrushes.White,
        TextBrush = MediaBrushes.Black,
        Kind = FileKind.Text
    };

    public static ChatBubble Outgoing(string text, DateTime? at = null) => new()
    {
        Text = text,
        Time = (at ?? DateTime.Now).ToString("HH:mm:ss"),
        Align = WpfHorizontalAlignment.Right,
        BubbleBrush = new SolidColorBrush(MediaColor.FromRgb(0x95, 0xEC, 0x69)),
        TextBrush = MediaBrushes.Black,
        Kind = FileKind.Text
    };

    public static ChatBubble System(string text, DateTime? at = null) => new()
    {
        Text = text,
        Time = (at ?? DateTime.Now).ToString("HH:mm:ss"),
        Align = WpfHorizontalAlignment.Center,
        BubbleBrush = new SolidColorBrush(MediaColor.FromRgb(0xE0, 0xE0, 0xE0)),
        TextBrush = new SolidColorBrush(MediaColor.FromRgb(0x66, 0x66, 0x66)),
        Kind = FileKind.Text
    };

    public static ChatBubble FromUrl(string url, bool isOutgoing, DateTime? at = null) => new()
    {
        Text = "🔗 " + url,
        Time = (at ?? DateTime.Now).ToString("HH:mm:ss"),
        Align = isOutgoing ? WpfHorizontalAlignment.Right : WpfHorizontalAlignment.Left,
        BubbleBrush = isOutgoing
            ? new SolidColorBrush(MediaColor.FromRgb(0x95, 0xEC, 0x69))
            : MediaBrushes.White,
        TextBrush = new SolidColorBrush(MediaColor.FromRgb(0x12, 0xB7, 0xF5)),
        Kind = FileKind.Url,
        MediaPath = url
    };

    public static ChatBubble FromFile(string? path, string fileName, FileKind kind, bool isOutgoing, DateTime? at = null)
    {
        ImageSource? img = null;
        if (kind == FileKind.Image && !string.IsNullOrEmpty(path) && File.Exists(path))
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

        return new ChatBubble
        {
            Text = $"{icon} {fileName}",
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
            ImageSource = img
        };
    }
}
