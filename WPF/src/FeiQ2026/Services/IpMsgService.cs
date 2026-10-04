using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using FeiQ2026.Protocol;
using FeiQ2026.Transport;

namespace FeiQ2026.Services;

public sealed class Peer
{
    public required string Name { get; set; }
    public required string HostName { get; init; }
    public required IPAddress Ip { get; init; }
    public DateTime LastSeen { get; set; } = DateTime.UtcNow;

    public override string ToString() => $"{Name} ({Ip})";
}

/// <summary>
/// 收到对方发来的文件通知（尚未下载）
/// </summary>
public sealed class IncomingFileOffer
{
    public required Peer From { get; init; }
    public required long PacketNo { get; init; }
    public required FileAttachInfo Info { get; init; }
    public string Message { get; init; } = "";
}

/// <summary>
/// 文件接收进度
/// </summary>
public sealed class FileTransferProgress
{
    public required string FileName { get; init; }
    public long Received { get; init; }
    public long Total { get; init; }
    public bool Done { get; init; }
    public string? SavedPath { get; init; }
    public string? Error { get; init; }
}

/// <summary>
/// IPMSG / 飞秋协议服务。只依赖 ITransport，不关心底层是 UDP / TCP / WebSocket / KCP。
/// 文件：UDP 模式走 TCP:2425；WebSocket 模式走 MFQ 分片帧。
/// </summary>
public sealed class IpMsgService : IAsyncDisposable
{
    private readonly ITransport _transport;
    private readonly Encoding _encoding;
    private readonly string _userName;
    private readonly string _hostName;
    private long _packetNo;
    private int _nextFileId = 1;
    private readonly Dictionary<string, Peer> _peers = new();
    private readonly object _peersLock = new();

    // 本机共享出去的文件 key = packetNo:fileId
    private readonly ConcurrentDictionary<string, SharedFile> _shared = new();

    // WS 模式接收中的文件 key = packetNo:fileId
    private readonly ConcurrentDictionary<string, IncomingWsReceive> _wsReceiving = new();

    private TcpFileServer? _tcpServer;
    private readonly bool _isWebSocket;

    public event Action<Peer>? PeerOnline;
    public event Action<Peer>? PeerOffline;
    public event Action<Peer, string>? MessageReceived;
    public event Action<IncomingFileOffer>? FileOffered;
    public event Action<FileTransferProgress>? FileProgress;

    /// <summary>默认保存目录</summary>
    public string DownloadDir { get; set; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "FeiQ2026");

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
        _isWebSocket = transport is WebSocketTransport;

        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        _encoding = Encoding.GetEncoding("GBK"); // 兼容飞秋2013
        // UTF-8 用于含 emoji / 非 GBK 字符的报文
    }

    /// <summary>
    /// 智能解码：优先识别 UTF-8（emoji），否则 GBK。
    /// 飞秋2013 纯中文仍走 GBK；本客户端互发表情走 UTF-8。
    /// </summary>
    private string DecodePacket(byte[] buffer)
    {
        // 明显合法的 UTF-8（含多字节）优先
        if (LooksLikeUtf8(buffer))
        {
            try { return Encoding.UTF8.GetString(buffer); }
            catch { /* fall through */ }
        }
        try { return _encoding.GetString(buffer); }
        catch { return Encoding.UTF8.GetString(buffer); }
    }

    private static bool LooksLikeUtf8(byte[] data)
    {
        int i = 0;
        bool hasMulti = false;
        while (i < data.Length)
        {
            byte b = data[i];
            if (b <= 0x7F) { i++; continue; }
            int need;
            if ((b & 0xE0) == 0xC0) need = 1;
            else if ((b & 0xF0) == 0xE0) need = 2;
            else if ((b & 0xF8) == 0xF0) need = 3;
            else return false;
            if (i + need >= data.Length) return false;
            for (int j = 1; j <= need; j++)
                if ((data[i + j] & 0xC0) != 0x80) return false;
            i += need + 1;
            hasMulti = true;
        }
        return hasMulti; // 纯 ASCII 也可用 GBK，交给 GBK 即可
    }

    /// <summary>能完整用 GBK 表示则用 GBK，否则 UTF-8（保留 emoji）</summary>
    private byte[] EncodePacket(IpMsgPacket pkt)
    {
        if (CanEncodeGbk(pkt.Extra) && CanEncodeGbk(pkt.UserName)
            && CanEncodeGbk(pkt.HostName) && CanEncodeGbk(pkt.FileExtra))
            return pkt.ToBytes(_encoding);
        return pkt.ToBytes(Encoding.UTF8);
    }

    private bool CanEncodeGbk(string? s)
    {
        if (string.IsNullOrEmpty(s)) return true;
        try
        {
            var bytes = _encoding.GetBytes(s);
            return _encoding.GetString(bytes) == s;
        }
        catch { return false; }
    }

    public async Task StartAsync(CancellationToken ct = default)
    {
        _transport.DataReceived += OnDataReceived;
        await _transport.StartAsync(ct).ConfigureAwait(false);

        // UDP 模式启动 TCP 文件服务
        if (!_isWebSocket && _transport.LocalPort > 0)
        {
            _tcpServer = new TcpFileServer(_transport.LocalPort, _encoding, LookupShared);
            await _tcpServer.StartAsync(ct).ConfigureAwait(false);
        }

        await AnnounceOnlineAsync(ct).ConfigureAwait(false);
    }

    private SharedFile? LookupShared(long packetNo, int fileId)
    {
        _shared.TryGetValue($"{packetNo}:{fileId}", out var s);
        return s;
    }

    public async Task AnnounceOnlineAsync(CancellationToken ct = default)
    {
        var pkt = BuildPacket(IpMsgCommands.BrEntry, _userName);
        var data = EncodePacket(pkt);
        if (_transport.SupportsBroadcast)
            await _transport.BroadcastAsync(data, _transport.LocalPort, ct).ConfigureAwait(false);
    }

    public async Task AnnounceOfflineAsync(CancellationToken ct = default)
    {
        var pkt = BuildPacket(IpMsgCommands.BrExit, _userName);
        var data = EncodePacket(pkt);
        if (_transport.SupportsBroadcast)
            await _transport.BroadcastAsync(data, _transport.LocalPort, ct).ConfigureAwait(false);
    }

    public async Task SendTextAsync(IPAddress targetIp, string text, bool requireAck = true, CancellationToken ct = default)
    {
        var cmd = IpMsgCommands.SendMsg | (requireAck ? IpMsgCommands.SendCheckOpt : 0);
        var pkt = BuildPacket(cmd, text);
        var data = EncodePacket(pkt);
        await _transport.SendAsync(data, new IPEndPoint(targetIp, _transport.LocalPort), ct)
            .ConfigureAwait(false);
    }

    /// <summary>
    /// 发送文件。UDP：通知 + 等待对方 TCP 拉取；WS：通知后主动推送分片。
    /// </summary>
    public async Task SendFileAsync(IPAddress targetIp, string filePath, string? message = null,
        CancellationToken ct = default)
    {
        if (!File.Exists(filePath))
            throw new FileNotFoundException("文件不存在", filePath);

        var fi = new FileInfo(filePath);
        var fileId = Interlocked.Increment(ref _nextFileId);
        var packetNo = Interlocked.Increment(ref _packetNo);
        var mtime = new DateTimeOffset(fi.LastWriteTimeUtc).ToUnixTimeSeconds();

        var attach = new FileAttachInfo
        {
            FileId = fileId,
            FileName = fi.Name,
            Size = fi.Length,
            Mtime = mtime,
            FileAttr = IpMsgCommands.FileRegular
        };

        var shared = new SharedFile
        {
            PacketNo = packetNo,
            FileId = fileId,
            Path = fi.FullName,
            FileName = fi.Name,
            Size = fi.Length
        };
        _shared[$"{packetNo}:{fileId}"] = shared;

        var cmd = IpMsgCommands.SendMsg | IpMsgCommands.SendCheckOpt | IpMsgCommands.FileAttachOpt;
        var pkt = new IpMsgPacket
        {
            Version = "1",
            PacketNo = packetNo,
            UserName = _userName,
            HostName = _hostName,
            Command = cmd,
            Extra = message ?? fi.Name,
            FileExtra = attach.ToExtraString()
        };
        var data = EncodePacket(pkt);
        await _transport.SendAsync(data, new IPEndPoint(targetIp, _transport.LocalPort), ct)
            .ConfigureAwait(false);

        // WebSocket：等对方发 GETFILEDATA 后再推分片（见 HandleWsGetFileRequestAsync）
        // UDP：对方 TCP 连上来拉（TcpFileServer）
    }

    private async Task PushFileOverWsAsync(SharedFile shared, CancellationToken ct)
    {
        try
        {
            const int chunkSize = 48 * 1024;
            await using var fs = new FileStream(shared.Path, FileMode.Open, FileAccess.Read, FileShare.Read);
            var buf = new byte[chunkSize];
            long offset = 0;
            int n;
            while ((n = await fs.ReadAsync(buf, ct).ConfigureAwait(false)) > 0)
            {
                var frame = WsFileFrame.Build(shared.PacketNo, shared.FileId, offset, shared.Size,
                    shared.FileName, buf.AsSpan(0, n));
                await _transport.SendAsync(frame, new IPEndPoint(IPAddress.Loopback, 0), ct)
                    .ConfigureAwait(false);
                offset += n;
                FileProgress?.Invoke(new FileTransferProgress
                {
                    FileName = shared.FileName,
                    Received = offset,
                    Total = shared.Size,
                    Done = offset >= shared.Size
                });
            }
        }
        catch (Exception ex)
        {
            FileProgress?.Invoke(new FileTransferProgress
            {
                FileName = shared.FileName,
                Received = 0,
                Total = shared.Size,
                Done = true,
                Error = ex.Message
            });
        }
    }

    /// <summary>
    /// 接受并下载对方发来的文件（UDP 走 TCP；WS 等分片自动写盘，此方法仅触发请求）。
    /// </summary>
    public async Task AcceptFileAsync(IncomingFileOffer offer, string? savePath = null,
        CancellationToken ct = default)
    {
        Directory.CreateDirectory(DownloadDir);
        savePath ??= Path.Combine(DownloadDir, SanitizeFileName(offer.Info.FileName));
        savePath = EnsureUniquePath(savePath);

        if (_isWebSocket)
        {
            // WS：发 GETFILEDATA 信令（可选），实际数据靠对方推送的 MFQ 帧
            // 注册接收槽，如果已经在收则只更新路径
            var key = $"{offer.PacketNo}:{offer.Info.FileId}";
            _wsReceiving[key] = new IncomingWsReceive
            {
                FileName = offer.Info.FileName,
                Total = offer.Info.Size,
                SavePath = savePath,
                Stream = new FileStream(savePath, FileMode.Create, FileAccess.Write, FileShare.None)
            };

            // 请求对方开始推（若对方是旧逻辑已主动推，则无害）
            var extra = $"{offer.PacketNo:x}:{offer.Info.FileId:x}:0";
            var pkt = BuildPacket(IpMsgCommands.GetFileData, extra);
            await _transport.SendAsync(EncodePacket(pkt),
                new IPEndPoint(IPAddress.Loopback, 0), ct).ConfigureAwait(false);
            return;
        }

        // UDP：TCP 连对方拉文件
        await DownloadViaTcpAsync(offer.From.Ip, offer.PacketNo, offer.Info, savePath, ct)
            .ConfigureAwait(false);
    }

    private async Task DownloadViaTcpAsync(IPAddress ip, long packetNo, FileAttachInfo info,
        string savePath, CancellationToken ct)
    {
        try
        {
            using var client = new TcpClient();
            await client.ConnectAsync(ip, _transport.LocalPort, ct).ConfigureAwait(false);
            await using var stream = client.GetStream();

            var extra = $"{packetNo:x}:{info.FileId:x}:0";
            var pkt = BuildPacket(IpMsgCommands.GetFileData, extra);
            var req = EncodePacket(pkt);
            await stream.WriteAsync(req, ct).ConfigureAwait(false);

            await using var fs = new FileStream(savePath, FileMode.Create, FileAccess.Write, FileShare.None);
            var buf = new byte[64 * 1024];
            long received = 0;
            int n;
            while ((n = await stream.ReadAsync(buf, ct).ConfigureAwait(false)) > 0)
            {
                await fs.WriteAsync(buf.AsMemory(0, n), ct).ConfigureAwait(false);
                received += n;
                FileProgress?.Invoke(new FileTransferProgress
                {
                    FileName = info.FileName,
                    Received = received,
                    Total = info.Size,
                    Done = false
                });
            }

            FileProgress?.Invoke(new FileTransferProgress
            {
                FileName = info.FileName,
                Received = received,
                Total = info.Size,
                Done = true,
                SavedPath = savePath
            });
        }
        catch (Exception ex)
        {
            FileProgress?.Invoke(new FileTransferProgress
            {
                FileName = info.FileName,
                Received = 0,
                Total = info.Size,
                Done = true,
                Error = ex.Message
            });
        }
    }

    public async Task RefreshAsync(CancellationToken ct = default)
    {
        lock (_peersLock) _peers.Clear();
        await AnnounceOnlineAsync(ct).ConfigureAwait(false);
    }

    private void OnDataReceived(byte[] buffer, IPEndPoint remote)
    {
        // WebSocket 文件分片
        if (WsFileFrame.IsFileFrame(buffer))
        {
            HandleWsFileFrame(buffer);
            return;
        }

        string text;
        try { text = DecodePacket(buffer); }
        catch { return; }

        var pkt = IpMsgPacket.TryParse(text);
        if (pkt == null) return;

        if (IsLocal(remote.Address) && !_isWebSocket) return;

        var basic = pkt.BasicCommand;

        switch (basic)
        {
            case IpMsgCommands.BrEntry:
            case IpMsgCommands.AnsEntry:
                HandlePresence(pkt, remote.Address, isEntry: true);
                if (basic == IpMsgCommands.BrEntry)
                    _ = ReplyAnsEntryAsync(remote.Address);
                break;

            case IpMsgCommands.BrExit:
                HandlePresence(pkt, remote.Address, isEntry: false);
                break;

            case IpMsgCommands.SendMsg:
                HandleSendMsg(pkt, remote.Address);
                break;

            case IpMsgCommands.GetFileData:
                // WS 模式：对方请求我们推文件
                if (_isWebSocket)
                    _ = HandleWsGetFileRequestAsync(pkt);
                break;
        }
    }

    private void HandleWsFileFrame(byte[] buffer)
    {
        if (!WsFileFrame.TryParse(buffer, out var packetNo, out var fileId, out var offset,
                out var total, out var fileName, out var payload))
            return;

        var key = $"{packetNo}:{fileId}";
        if (!_wsReceiving.TryGetValue(key, out var recv))
        {
            // 用户尚未点「接收」，丢弃分片（等 AcceptFileAsync 注册后再收）
            return;
        }

        try
        {
            if (payload.Length > 0)
            {
                recv.Stream.Write(payload.Span);
                recv.Received += payload.Length;
            }

            var done = recv.Received >= total || (total > 0 && offset + payload.Length >= total);
            if (done)
            {
                recv.Stream.Dispose();
                _wsReceiving.TryRemove(key, out _);
                FileProgress?.Invoke(new FileTransferProgress
                {
                    FileName = recv.FileName,
                    Received = recv.Received,
                    Total = total,
                    Done = true,
                    SavedPath = recv.SavePath
                });
            }
            else
            {
                FileProgress?.Invoke(new FileTransferProgress
                {
                    FileName = recv.FileName,
                    Received = recv.Received,
                    Total = total,
                    Done = false
                });
            }
        }
        catch (Exception ex)
        {
            try { recv.Stream.Dispose(); } catch { }
            _wsReceiving.TryRemove(key, out _);
            FileProgress?.Invoke(new FileTransferProgress
            {
                FileName = fileName,
                Received = recv.Received,
                Total = total,
                Done = true,
                Error = ex.Message
            });
        }
    }

    private async Task HandleWsGetFileRequestAsync(IpMsgPacket pkt)
    {
        var parts = pkt.Extra.Split(':');
        if (parts.Length < 2) return;
        if (!long.TryParse(parts[0], System.Globalization.NumberStyles.HexNumber, null, out var packetNo))
            return;
        if (!int.TryParse(parts[1], System.Globalization.NumberStyles.HexNumber, null, out var fileId))
            return;

        var shared = LookupShared(packetNo, fileId);
        if (shared == null) return;
        await PushFileOverWsAsync(shared, CancellationToken.None).ConfigureAwait(false);
    }

    private void HandlePresence(IpMsgPacket pkt, IPAddress ip, bool isEntry)
    {
        // WS 模式 remote 都是 Loopback，用稳定 HostName 当 key（改名不产生重复）
        // 局域网仍用 IP
        var hostPart = string.IsNullOrWhiteSpace(pkt.HostName) ? pkt.UserName : pkt.HostName;
        var key = _isWebSocket ? $"ws:{hostPart}" : ip.ToString();
        Peer? peer;

        lock (_peersLock)
        {
            // 清理历史脏 key：旧版用 ws:UserName@HostName，改名后会残留
            if (_isWebSocket && !string.IsNullOrEmpty(pkt.HostName))
            {
                var stale = _peers.Keys
                    .Where(k => k.StartsWith("ws:", StringComparison.Ordinal)
                                && k.Contains('@')
                                && k.EndsWith("@" + pkt.HostName, StringComparison.OrdinalIgnoreCase)
                                && k != key)
                    .ToList();
                foreach (var sk in stale)
                    _peers.Remove(sk);
            }

            if (isEntry)
            {
                var name = string.IsNullOrWhiteSpace(pkt.Extra) ? pkt.UserName : pkt.Extra;
                if (_peers.TryGetValue(key, out peer))
                {
                    peer.LastSeen = DateTime.UtcNow;
                    // 改名：更新显示名并通知 UI（避免列表残留旧名条目）
                    if (!string.Equals(peer.Name, name, StringComparison.Ordinal))
                        peer.Name = name;
                    else
                        return; // 无变化，不重复触发 PeerOnline
                }
                else
                {
                    peer = new Peer
                    {
                        Name = name,
                        HostName = pkt.HostName,
                        Ip = ip,
                        LastSeen = DateTime.UtcNow
                    };
                    _peers[key] = peer;
                }
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
        if (pkt.HasOption(IpMsgCommands.SendCheckOpt))
            _ = ReplyRecvMsgAsync(ip, pkt.PacketNo);

        var peer = EnsurePeer(pkt, ip);

        // 文件附件
        if (pkt.HasOption(IpMsgCommands.FileAttachOpt) && !string.IsNullOrEmpty(pkt.FileExtra))
        {
            var files = FileAttachInfo.ParseList(pkt.FileExtra);
            foreach (var f in files)
            {
                FileOffered?.Invoke(new IncomingFileOffer
                {
                    From = peer,
                    PacketNo = pkt.PacketNo,
                    Info = f,
                    Message = pkt.Extra
                });
            }
            // 若有文字说明也显示
            if (!string.IsNullOrWhiteSpace(pkt.Extra) && pkt.Extra != files.FirstOrDefault()?.FileName)
                MessageReceived?.Invoke(peer, pkt.Extra);
            return;
        }

        MessageReceived?.Invoke(peer, pkt.Extra);
    }

    private Peer EnsurePeer(IpMsgPacket pkt, IPAddress ip)
    {
        // 与 HandlePresence 一致：WS 用 HostName 稳定身份，改名不换 key
        var key = _isWebSocket
            ? $"ws:{(string.IsNullOrWhiteSpace(pkt.HostName) ? pkt.UserName : pkt.HostName)}"
            : ip.ToString();
        lock (_peersLock)
        {
            if (_peers.TryGetValue(key, out var peer))
            {
                // 消息包也可能带新名字，同步更新
                var name = string.IsNullOrWhiteSpace(pkt.UserName) ? peer.Name : pkt.UserName;
                if (!string.Equals(peer.Name, name, StringComparison.Ordinal))
                {
                    peer.Name = name;
                    PeerOnline?.Invoke(peer); // 让 UI UpsertFriend 刷新显示名
                }
                return peer;
            }

            peer = new Peer
            {
                Name = pkt.UserName,
                HostName = pkt.HostName,
                Ip = ip
            };
            _peers[key] = peer;
            PeerOnline?.Invoke(peer);
            return peer;
        }
    }

    private async Task ReplyAnsEntryAsync(IPAddress ip)
    {
        try
        {
            var pkt = BuildPacket(IpMsgCommands.AnsEntry, _userName);
            await _transport.SendAsync(EncodePacket(pkt),
                new IPEndPoint(ip, _transport.LocalPort)).ConfigureAwait(false);
        }
        catch { }
    }

    private async Task ReplyRecvMsgAsync(IPAddress ip, long packetNo)
    {
        try
        {
            var pkt = BuildPacket(IpMsgCommands.RecvMsg, packetNo.ToString());
            await _transport.SendAsync(EncodePacket(pkt),
                new IPEndPoint(ip, _transport.LocalPort)).ConfigureAwait(false);
        }
        catch { }
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
        if (IPAddress.IsLoopback(ip)) return true;
        try
        {
            return Dns.GetHostAddresses(Dns.GetHostName()).Any(a => a.Equals(ip));
        }
        catch { return false; }
    }

    private static string SanitizeFileName(string name)
    {
        foreach (var c in Path.GetInvalidFileNameChars())
            name = name.Replace(c, '_');
        return string.IsNullOrWhiteSpace(name) ? "file.bin" : name;
    }

    private static string EnsureUniquePath(string path)
    {
        if (!File.Exists(path)) return path;
        var dir = Path.GetDirectoryName(path)!;
        var name = Path.GetFileNameWithoutExtension(path);
        var ext = Path.GetExtension(path);
        for (int i = 1; i < 1000; i++)
        {
            var candidate = Path.Combine(dir, $"{name}({i}){ext}");
            if (!File.Exists(candidate)) return candidate;
        }
        return Path.Combine(dir, $"{name}_{Guid.NewGuid():N}{ext}");
    }

    public async ValueTask DisposeAsync()
    {
        try { await AnnounceOfflineAsync().ConfigureAwait(false); } catch { }
        _transport.DataReceived -= OnDataReceived;
        if (_tcpServer != null)
        {
            await _tcpServer.DisposeAsync().ConfigureAwait(false);
            _tcpServer = null;
        }
        foreach (var kv in _wsReceiving)
        {
            try { kv.Value.Stream.Dispose(); } catch { }
        }
        _wsReceiving.Clear();
        await _transport.DisposeAsync().ConfigureAwait(false);
    }

    private sealed class IncomingWsReceive
    {
        public required string FileName { get; init; }
        public long Total { get; init; }
        public long Received { get; set; }
        public required string SavePath { get; init; }
        public required FileStream Stream { get; init; }
    }
}
