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
    private readonly ObservableCollection<ChatBubble> _messages = new();
    private readonly Func<Peer, string, Task>? _sendText;
    private readonly Func<Peer, string, Task>? _sendFile;

    public ChatWindow(Peer peer, Func<Peer, string, Task>? sendText, Func<Peer, string, Task>? sendFile)
    {
        InitializeComponent();
        Peer = peer;
        _sendText = sendText;
        _sendFile = sendFile;

        Title = $"{peer.Name} - FeiQ 2026";
        PeerNameText.Text = peer.Name;
        PeerIpText.Text = peer.Ip.ToString();
        AvatarText.Text = string.IsNullOrEmpty(peer.Name) ? "?" : peer.Name[..1].ToUpperInvariant();
        MsgList.ItemsSource = _messages;
    }

    public void AppendIncoming(string text)
    {
        _messages.Add(ChatBubble.Incoming(text));
        ScrollToEnd();
    }

    public void AppendSystem(string text)
    {
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
            _messages.Add(ChatBubble.Outgoing($"[文件] {name}"));
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
        // 允许关闭，由 MainWindow 从字典移除
    }
}

public sealed class ChatBubble
{
    public string Text { get; init; } = "";
    public string Time { get; init; } = "";
    public WpfHorizontalAlignment Align { get; init; }
    public MediaBrush BubbleBrush { get; init; } = MediaBrushes.White;
    public MediaBrush TextBrush { get; init; } = MediaBrushes.Black;

    public static ChatBubble Incoming(string text) => new()
    {
        Text = text,
        Time = DateTime.Now.ToString("HH:mm:ss"),
        Align = WpfHorizontalAlignment.Left,
        BubbleBrush = MediaBrushes.White,
        TextBrush = MediaBrushes.Black
    };

    public static ChatBubble Outgoing(string text) => new()
    {
        Text = text,
        Time = DateTime.Now.ToString("HH:mm:ss"),
        Align = WpfHorizontalAlignment.Right,
        BubbleBrush = new SolidColorBrush(MediaColor.FromRgb(0x95, 0xEC, 0x69)),
        TextBrush = MediaBrushes.Black
    };

    public static ChatBubble System(string text) => new()
    {
        Text = text,
        Time = DateTime.Now.ToString("HH:mm:ss"),
        Align = WpfHorizontalAlignment.Center,
        BubbleBrush = new SolidColorBrush(MediaColor.FromRgb(0xE0, 0xE0, 0xE0)),
        TextBrush = new SolidColorBrush(MediaColor.FromRgb(0x66, 0x66, 0x66))
    };
}
