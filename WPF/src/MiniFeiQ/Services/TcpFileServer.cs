using System.Net;
using System.Net.Sockets;
using System.Text;
using MiniFeiQ.Protocol;

namespace MiniFeiQ.Services;

/// <summary>
/// UDP 模式下的 TCP 文件服务（端口与 UDP 相同，默认 2425）。
/// 接收方连上来发 GETFILEDATA，本端回传裸文件字节。
/// </summary>
public sealed class TcpFileServer : IAsyncDisposable
{
    private readonly int _port;
    private readonly Encoding _encoding;
    private readonly Func<long, int, SharedFile?> _lookup;
    private TcpListener? _listener;
    private CancellationTokenSource? _cts;
    private Task? _acceptTask;

    public TcpFileServer(int port, Encoding encoding, Func<long, int, SharedFile?> lookup)
    {
        _port = port;
        _encoding = encoding;
        _lookup = lookup;
    }

    public Task StartAsync(CancellationToken ct = default)
    {
        if (_listener != null) return Task.CompletedTask;
        _listener = new TcpListener(IPAddress.Any, _port);
        _listener.Start();
        _cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _acceptTask = AcceptLoopAsync(_cts.Token);
        return Task.CompletedTask;
    }

    private async Task AcceptLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _listener != null)
        {
            try
            {
                var client = await _listener.AcceptTcpClientAsync(ct).ConfigureAwait(false);
                _ = HandleClientAsync(client, ct);
            }
            catch (OperationCanceledException) { break; }
            catch { /* 继续接 */ }
        }
    }

    private async Task HandleClientAsync(TcpClient client, CancellationToken ct)
    {
        try
        {
            using (client)
            await using var stream = client.GetStream();
            // 读一条 IPMSG 文本请求（以 \0 结束或超时）
            var buf = new byte[4096];
            using var ms = new MemoryStream();
            while (ms.Length < 8192)
            {
                var n = await stream.ReadAsync(buf.AsMemory(0, buf.Length), ct).ConfigureAwait(false);
                if (n == 0) return;
                ms.Write(buf, 0, n);
                var arr = ms.ToArray();
                if (Array.IndexOf(arr, (byte)0) >= 0) break;
                if (!stream.DataAvailable && ms.Length > 0) break;
            }

            var text = _encoding.GetString(ms.ToArray());
            var pkt = IpMsgPacket.TryParse(text);
            if (pkt == null || pkt.BasicCommand != IpMsgCommands.GetFileData)
                return;

            // extra: packetID:fileID:offset （hex）
            var parts = pkt.Extra.Split(':');
            if (parts.Length < 3) return;
            if (!long.TryParse(parts[0], System.Globalization.NumberStyles.HexNumber, null, out var packetNo))
                return;
            if (!int.TryParse(parts[1], System.Globalization.NumberStyles.HexNumber, null, out var fileId))
                return;
            long.TryParse(parts[2], System.Globalization.NumberStyles.HexNumber, null, out var offset);

            var shared = _lookup(packetNo, fileId);
            if (shared == null || !File.Exists(shared.Path)) return;

            await using var fs = new FileStream(shared.Path, FileMode.Open, FileAccess.Read, FileShare.Read);
            if (offset > 0 && offset < fs.Length)
                fs.Seek(offset, SeekOrigin.Begin);

            var chunk = new byte[64 * 1024];
            int read;
            while ((read = await fs.ReadAsync(chunk, ct).ConfigureAwait(false)) > 0)
            {
                await stream.WriteAsync(chunk.AsMemory(0, read), ct).ConfigureAwait(false);
            }
            await stream.FlushAsync(ct).ConfigureAwait(false);
        }
        catch
        {
            // 单次传输失败忽略
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (_cts != null)
        {
            await _cts.CancelAsync();
            _cts.Dispose();
            _cts = null;
        }
        try { _listener?.Stop(); } catch { }
        _listener = null;
        if (_acceptTask != null)
        {
            try { await _acceptTask.ConfigureAwait(false); } catch { }
            _acceptTask = null;
        }
    }
}

/// <summary>本机对外共享的文件（等待对方来拉）</summary>
public sealed class SharedFile
{
    public long PacketNo { get; init; }
    public int FileId { get; init; }
    public string Path { get; init; } = "";
    public string FileName { get; init; } = "";
    public long Size { get; init; }
    public DateTime ExpireAt { get; init; } = DateTime.UtcNow.AddHours(1);
}
