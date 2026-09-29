using System.Collections.ObjectModel;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Windows;

namespace MiniFeiQ;

public class User
{
    public string Name { get; set; }
    public IPAddress Ip { get; set; }
    public override string ToString() => $"{Name} ({Ip})";
}

public partial class MainWindow : Window
{
    const int Port = 2425;
    const int BR_ENTRY = 1, ANSENTRY = 3, SENDMSG = 0x20, RECVMSG = 0x21;
    const int SENDCHECKOPT = 0x100;

    static readonly Encoding Gbk = CreateGbk(); // 飞秋在中文 Windows 上用 GBK

    static Encoding CreateGbk()
    {
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        return Encoding.GetEncoding("GBK");
    }
    readonly UdpClient _udp;
    readonly ObservableCollection<User> _users = new();
    readonly string _me = Environment.UserName, _host = Environment.MachineName;
    long _no = DateTimeOffset.UtcNow.ToUnixTimeSeconds();

    public MainWindow()
    {
        InitializeComponent();
        UserList.ItemsSource = _users;

        _udp = new UdpClient(AddressFamily.InterNetwork) { EnableBroadcast = true };
        _udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _udp.Client.Bind(new IPEndPoint(IPAddress.Any, Port));

        _ = ReceiveLoop();
        Announce(IPAddress.Broadcast);
    }

    // 报文格式  版本:包编号:发送者:主机名:命令字:附加信息
    void Send(IPAddress ip, int cmd, string extra)
    {
        string head = $"1:{++_no}:{_me}:{_host}:{cmd}:";
        byte[] data = Gbk.GetBytes(head + extra);
        _udp.Send(data, data.Length, new IPEndPoint(ip, Port));
    }

    void Announce(IPAddress to) => Send(to, BR_ENTRY, _me); // 上线广播，附加信息为昵称

    async Task ReceiveLoop()
    {
        while (true)
        {
            UdpReceiveResult r;
            try { r = await _udp.ReceiveAsync(); } catch { return; }

            string text = Gbk.GetString(r.Buffer);
            string[] f = text.Split(':', 6);
            if (f.Length < 6 || !int.TryParse(f[4], out int raw)) continue;

            int cmd = raw & 0xFF;
            string extra = f[5].Split('\0')[0];
            var ip = r.RemoteEndPoint.Address;

            if (cmd == BR_ENTRY || cmd == ANSENTRY)
            {
                if (!_users.Any(u => u.Ip.Equals(ip)) && !IsLocal(ip))
                    _users.Add(new User { Name = string.IsNullOrEmpty(extra) ? f[2] : extra, Ip = ip });
                if (cmd == BR_ENTRY) Send(ip, ANSENTRY, _me); // 回应对方，让对方也能发现我们
            }
            else if (cmd == SENDMSG)
            {
                if ((raw & SENDCHECKOPT) != 0) Send(ip, RECVMSG, f[1]); // 回执
                Log.AppendText($"[{f[2]}@{ip}] {extra}\n");
            }
        }
    }

    static bool IsLocal(IPAddress ip) =>
        Dns.GetHostAddresses(Dns.GetHostName()).Any(a => a.Equals(ip));

    void Refresh_Click(object s, RoutedEventArgs e) { _users.Clear(); Announce(IPAddress.Broadcast); }

    void Send_Click(object s, RoutedEventArgs e)
    {
        if (UserList.SelectedItem is not User u || string.IsNullOrWhiteSpace(Input.Text)) return;
        Send(u.Ip, SENDMSG | SENDCHECKOPT, Input.Text + "\0");
        Log.AppendText($"[我 -> {u.Name}] {Input.Text}\n");
        Input.Clear();
    }
}