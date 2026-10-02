using System.Buffers;
using System.IO;
using System.Net;
using System.Net.WebSockets;

namespace FeiQ2026.Transport;

/// <summary>
/// WebSocket 传输实现。
/// 连接到中继服务器后，所有收发都经服务器转发到其他客户端。
/// 帧格式： [4字节LE长度][payload]
/// </summary>
public sealed class WebSocketTransport : ITransport
{
    private readonly Uri _serverUri;
    private readonly string _clientId;
    private ClientWebSocket? _ws;
    private CancellationTokenSource? _cts;
    private Task? _recvTask;
    private readonly SemaphoreSlim _sendLock = new(1, 1);

    public int LocalPort => 0; // WebSocket 无本地端口概念
    public bool SupportsBroadcast => true; // 通过服务器广播

    public event Action<byte[], IPEndPoint>? DataReceived;

    /// <param name="serverUrl">例如 ws://s0.v100.vip:xxxxx 或 ws://192.168.1.101:9000</param>
    /// <param name="clientId">本机标识，默认用机器名</param>
    public WebSocketTransport(string serverUrl, string? clientId = null)
    {
        _serverUri = new Uri(serverUrl);
        _clientId = clientId ?? Environment.MachineName;
    }

    public async Task StartAsync(CancellationToken ct = default)
    {
        if (_ws != null) return;

        _ws = new ClientWebSocket();
        // 握手时带上 clientId，方便服务器识别
        _ws.Options.SetRequestHeader("X-Client-Id", _clientId);

        await _ws.ConnectAsync(_serverUri, ct).ConfigureAwait(false);

        _cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _recvTask = ReceiveLoopAsync(_cts.Token);
    }

    private async Task ReceiveLoopAsync(CancellationToken ct)
    {
        var buffer = ArrayPool<byte>.Shared.Rent(64 * 1024);
        try
        {
            while (!ct.IsCancellationRequested && _ws?.State == WebSocketState.Open)
            {
                using var ms = new MemoryStream();
                WebSocketReceiveResult result;
                do
                {
                    result = await _ws.ReceiveAsync(buffer, ct).ConfigureAwait(false);
                    if (result.MessageType == WebSocketMessageType.Close)
                        return;
                    ms.Write(buffer, 0, result.Count);
                } while (!result.EndOfMessage);

                var data = ms.ToArray();
                if (data.Length == 0) continue;

                // 伪造一个远端终点，上层只关心数据
                var fakeEp = new IPEndPoint(IPAddress.Loopback, 0);
                DataReceived?.Invoke(data, fakeEp);
            }
        }
        catch (OperationCanceledException) { }
        catch (WebSocketException) { }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }

    public async Task SendAsync(byte[] data, IPEndPoint remote, CancellationToken ct = default)
    {
        // WebSocket 模式下 remote 被忽略，全部交给服务器转发
        await SendRawAsync(data, ct).ConfigureAwait(false);
    }

    public async Task BroadcastAsync(byte[] data, int port, CancellationToken ct = default)
    {
        // 广播同样走服务器
        await SendRawAsync(data, ct).ConfigureAwait(false);
    }

    private async Task SendRawAsync(byte[] data, CancellationToken ct)
    {
        if (_ws == null || _ws.State != WebSocketState.Open)
            throw new InvalidOperationException("WebSocket not connected");

        await _sendLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            await _ws.SendAsync(data, WebSocketMessageType.Binary, true, ct)
                .ConfigureAwait(false);
        }
        finally
        {
            _sendLock.Release();
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

        if (_recvTask != null)
        {
            try { await _recvTask.ConfigureAwait(false); } catch { }
            _recvTask = null;
        }

        if (_ws != null)
        {
            try
            {
                if (_ws.State == WebSocketState.Open)
                    await _ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", CancellationToken.None);
            }
            catch { }
            _ws.Dispose();
            _ws = null;
        }

        _sendLock.Dispose();
    }
}
