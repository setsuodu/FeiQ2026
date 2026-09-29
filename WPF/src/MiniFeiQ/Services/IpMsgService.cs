using System.Net;
using System.Text;
using MiniFeiQ.Protocol;
using MiniFeiQ.Transport;

namespace MiniFeiQ.Services;

public sealed class Peer
{
    public required string Name { get; init; }
    public required string HostName { get; init; }
    public required IPAddress Ip { get; init; }
    public DateTime LastSeen { get; set; } = DateTime.UtcNow;

    public override string ToString() => $"{Name} ({Ip})";
}

/// <summary>
/// IPMSG / 飞秋协议服务。只依赖 ITransport，不关心底层是 UDP / TCP / WebSocket / KCP。
/// </summary>
public sealed class IpMsgService : IAsyncDisposable
{
    private readonly ITransport _transport;
    private readonly Encoding _encoding;
    private readonly string _userName;
    private readonly string _hostName;
    private long _packetNo;
    private readonly Dictionary<string, Peer> _peers = new(); // key = IP
    private readonly object _peersLock = new();

    public event Action<Peer>? PeerOnline;
    public event Action<Peer>? PeerOffline;
    public event Action<Peer, string>? MessageReceived;

    public IReadOnlyCollection<Peer> Peers
    {
        get { lock (_peersLock) return _peers.Values.ToList(); }
    }

    public IpMsgService(ITransport transport, string? userName = null, string? hostName = null)
    {
        _transport = transport;
        _userName = userName ?? Environment.UserName;
        _hostName = hostName ?? Environment.MachineName;
        _packetNo = DateTimeOffset.UtcNow.ToUnixTimeSeconds();

        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        _encoding = Encoding.GetEncoding("GBK"); // 飞秋2013 默认 GBK
    }

    public async Task StartAsync(CancellationToken ct = default)
    {
        _transport.DataReceived += OnDataReceived;
        await _transport.StartAsync(ct).ConfigureAwait(false);
        await AnnounceOnlineAsync(ct).ConfigureAwait(false);
    }

    public async Task AnnounceOnlineAsync(CancellationToken ct = default)
    {
        var pkt = BuildPacket(IpMsgCommands.BrEntry, _userName);
        var data = pkt.ToBytes(_encoding);

        if (_transport.SupportsBroadcast)
            await _transport.BroadcastAsync(data, _transport.LocalPort, ct).ConfigureAwait(false);
    }

    public async Task AnnounceOfflineAsync(CancellationToken ct = default)
    {
        var pkt = BuildPacket(IpMsgCommands.BrExit, _userName);
        var data = pkt.ToBytes(_encoding);

        if (_transport.SupportsBroadcast)
            await _transport.BroadcastAsync(data, _transport.LocalPort, ct).ConfigureAwait(false);
    }

    public async Task SendTextAsync(IPAddress targetIp, string text, bool requireAck = true, CancellationToken ct = default)
    {
        var cmd = IpMsgCommands.SendMsg | (requireAck ? IpMsgCommands.SendCheckOpt : 0);
        var pkt = BuildPacket(cmd, text);
        var data = pkt.ToBytes(_encoding);
        await _transport.SendAsync(data, new IPEndPoint(targetIp, _transport.LocalPort), ct)
            .ConfigureAwait(false);
    }

    public async Task RefreshAsync(CancellationToken ct = default)
    {
        lock (_peersLock) _peers.Clear();
        await AnnounceOnlineAsync(ct).ConfigureAwait(false);
    }

    private void OnDataReceived(byte[] buffer, IPEndPoint remote)
    {
        string text;
        try { text = _encoding.GetString(buffer); }
        catch { return; }

        var pkt = IpMsgPacket.TryParse(text);
        if (pkt == null) return;

        // 忽略自己发出的包
        if (IsLocal(remote.Address)) return;

        var basic = pkt.BasicCommand;

        switch (basic)
        {
            case IpMsgCommands.BrEntry:
            case IpMsgCommands.AnsEntry:
                HandlePresence(pkt, remote.Address, isEntry: true);
                if (basic == IpMsgCommands.BrEntry)
                {
                    // 回应 ANSENTRY，让对方也能发现我们
                    _ = ReplyAnsEntryAsync(remote.Address);
                }
                break;

            case IpMsgCommands.BrExit:
                HandlePresence(pkt, remote.Address, isEntry: false);
                break;

            case IpMsgCommands.SendMsg:
                HandleSendMsg(pkt, remote.Address);
                break;
        }
    }

    private void HandlePresence(IpMsgPacket pkt, IPAddress ip, bool isEntry)
    {
        var key = ip.ToString();
        Peer? peer;

        lock (_peersLock)
        {
            if (isEntry)
            {
                var name = string.IsNullOrWhiteSpace(pkt.Extra) ? pkt.UserName : pkt.Extra;
                if (_peers.TryGetValue(key, out peer))
                {
                    peer.LastSeen = DateTime.UtcNow;
                    return; // 已存在，只更新时间
                }

                peer = new Peer
                {
                    Name = name,
                    HostName = pkt.HostName,
                    Ip = ip,
                    LastSeen = DateTime.UtcNow
                };
                _peers[key] = peer;
            }
            else
            {
                if (!_peers.Remove(key, out peer)) return;
            }
        }

        if (isEntry) PeerOnline?.Invoke(peer);
        else PeerOffline?.Invoke(peer);
    }

    private void HandleSendMsg(IpMsgPacket pkt, IPAddress ip)
    {
        // 需要回执
        if (pkt.HasOption(IpMsgCommands.SendCheckOpt))
        {
            _ = ReplyRecvMsgAsync(ip, pkt.PacketNo);
        }

        Peer? peer;
        lock (_peersLock)
        {
            _peers.TryGetValue(ip.ToString(), out peer);
        }

        // 如果还没在列表里，先补一个
        if (peer == null)
        {
            peer = new Peer
            {
                Name = pkt.UserName,
                HostName = pkt.HostName,
                Ip = ip
            };
            lock (_peersLock) _peers[ip.ToString()] = peer;
            PeerOnline?.Invoke(peer);
        }

        MessageReceived?.Invoke(peer, pkt.Extra);
    }

    private async Task ReplyAnsEntryAsync(IPAddress ip)
    {
        try
        {
            var pkt = BuildPacket(IpMsgCommands.AnsEntry, _userName);
            await _transport.SendAsync(pkt.ToBytes(_encoding),
                new IPEndPoint(ip, _transport.LocalPort)).ConfigureAwait(false);
        }
        catch { /* ignore */ }
    }

    private async Task ReplyRecvMsgAsync(IPAddress ip, long packetNo)
    {
        try
        {
            var pkt = BuildPacket(IpMsgCommands.RecvMsg, packetNo.ToString());
            await _transport.SendAsync(pkt.ToBytes(_encoding),
                new IPEndPoint(ip, _transport.LocalPort)).ConfigureAwait(false);
        }
        catch { /* ignore */ }
    }

    private IpMsgPacket BuildPacket(int command, string extra) => new()
    {
        Version = "1",
        PacketNo = Interlocked.Increment(ref _packetNo),
        UserName = _userName,
        HostName = _hostName,
        Command = command,
        Extra = extra
    };

    private static bool IsLocal(IPAddress ip)
    {
        try
        {
            return Dns.GetHostAddresses(Dns.GetHostName())
                      .Any(a => a.Equals(ip));
        }
        catch { return false; }
    }

    public async ValueTask DisposeAsync()
    {
        try { await AnnounceOfflineAsync().ConfigureAwait(false); } catch { }
        _transport.DataReceived -= OnDataReceived;
        await _transport.DisposeAsync().ConfigureAwait(false);
    }
}
