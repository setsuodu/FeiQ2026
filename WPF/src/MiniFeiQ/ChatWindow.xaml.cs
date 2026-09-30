using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Media;
using MiniFeiQ.Services;
using MediaColor = System.Windows.Media.Color;
using MediaBrush = System.Windows.Media.Brush;
using MediaBrushes = System.Windows.Media.Brushes;
using WpfHorizontalAlignment = System.Windows.HorizontalAlignment;

namespace MiniFeiQ;

public partial class ChatWindow : Window
{
    public Peer Peer { get; }
    private readonly ChatStore _store;
    private readonly string _peerKey;
    private readonly ObservableCollection<ChatBubble> _messages = new();
    private readonly Func<Peer, string, Task>? _sendText;
    private readonly Func<Peer, string, Task>? _sendFile;

    public ChatWindow(
        Peer peer,
        ChatStore store,
        Func<Peer, string, Task>? sendText,
        Func<Peer, string, Task>? sendFile)
    {
        InitializeComponent();
        Peer = peer;
        _store = store;
        _peerKey = $"{peer.Name}|{peer.Ip}";
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
                _messages.Add(row.Direction switch
                {
                    "out" => ChatBubble.Outgoing(row.Text, row.CreatedAt),
                    "sys" => ChatBubble.System(row.Text, row.CreatedAt),
                    _ => ChatBubble.Incoming(row.Text, row.CreatedAt)
                });
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
        _messages.Add(ChatBubble.Incoming(text));
        ScrollToEnd();
    }

    public void AppendSystem(string text)
    {
        try { _store.Add(_peerKey, Peer.Name, "sys", text); } catch { }
        _messages.Add(ChatBubble.System(text));
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
            _messages.Add(ChatBubble.Outgoing(text));
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
            Multiselect = false
        };
        if (dlg.ShowDialog() != true) return;
        var path = dlg.FileName;
        var name = System.IO.Path.GetFileName(path);
        try
        {
            await _sendFile(Peer, path);
            var note = $"[文件] {name}";
            try { _store.Add(_peerKey, Peer.Name, "out", note); } catch { }
            _messages.Add(ChatBubble.Outgoing(note));
            ScrollToEnd();
        }
        catch (Exception ex)
        {
            System.Windows.MessageBox.Show(ex.Message, "发文件失败",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void ScrollToEnd()
    {
        Dispatcher.BeginInvoke(() => MsgScroll.ScrollToEnd());
    }

    private void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
    {
    }
}

public sealed class ChatBubble
{
    public string Text { get; init; } = "";
    public string Time { get; init; } = "";
    public WpfHorizontalAlignment Align { get; init; }
    public MediaBrush BubbleBrush { get; init; } = MediaBrushes.White;
    public MediaBrush TextBrush { get; init; } = MediaBrushes.Black;

    public static ChatBubble Incoming(string text, DateTime? at = null) => new()
    {
        Text = text,
        Time = (at ?? DateTime.Now).ToString("HH:mm:ss"),
        Align = WpfHorizontalAlignment.Left,
        BubbleBrush = MediaBrushes.White,
        TextBrush = MediaBrushes.Black
    };

    public static ChatBubble Outgoing(string text, DateTime? at = null) => new()
    {
        Text = text,
        Time = (at ?? DateTime.Now).ToString("HH:mm:ss"),
        Align = WpfHorizontalAlignment.Right,
        BubbleBrush = new SolidColorBrush(MediaColor.FromRgb(0x95, 0xEC, 0x69)),
        TextBrush = MediaBrushes.Black
    };

    public static ChatBubble System(string text, DateTime? at = null) => new()
    {
        Text = text,
        Time = (at ?? DateTime.Now).ToString("HH:mm:ss"),
        Align = WpfHorizontalAlignment.Center,
        BubbleBrush = new SolidColorBrush(MediaColor.FromRgb(0xE0, 0xE0, 0xE0)),
        TextBrush = new SolidColorBrush(MediaColor.FromRgb(0x66, 0x66, 0x66))
    };
}
