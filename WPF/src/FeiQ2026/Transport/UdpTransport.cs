using System.Net;
using System.Net.Sockets;

namespace FeiQ2026.Transport;

/// <summary>
/// 基于 UDP 的传输实现（飞秋2013 / IPMSG 默认方式）
/// </summary>
public sealed class UdpTransport : ITransport
{
    private readonly int _port;
    private UdpClient? _udp;
    private CancellationTokenSource? _cts;
    private Task? _recvTask;

    public int LocalPort => _port;
    public bool SupportsBroadcast => true;

    public event Action<byte[], IPEndPoint>? DataReceived;

    public UdpTransport(int port = 2425)
    {
        _port = port;
    }

    public Task StartAsync(CancellationToken ct = default)
    {
        if (_udp != null) return Task.CompletedTask;

        _udp = new UdpClient(AddressFamily.InterNetwork)
        {
            EnableBroadcast = true
        };
        _udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _udp.Client.Bind(new IPEndPoint(IPAddress.Any, _port));

        _cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _recvTask = ReceiveLoopAsync(_cts.Token);
        return Task.CompletedTask;
    }

    private async Task ReceiveLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _udp != null)
        {
            try
            {
                var result = await _udp.ReceiveAsync(ct).ConfigureAwait(false);
                DataReceived?.Invoke(result.Buffer, result.RemoteEndPoint);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch
            {
                // 忽略单次接收错误，继续循环
            }
        }
    }

    public async Task SendAsync(byte[] data, IPEndPoint remote, CancellationToken ct = default)
    {
        if (_udp == null) throw new InvalidOperationException("Transport not started");
        await _udp.SendAsync(data, remote, ct).ConfigureAwait(false);
    }

    public async Task BroadcastAsync(byte[] data, int port, CancellationToken ct = default)
    {
        if (_udp == null) throw new InvalidOperationException("Transport not started");
        var ep = new IPEndPoint(IPAddress.Broadcast, port);
        await _udp.SendAsync(data, ep, ct).ConfigureAwait(false);
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
            try { await _recvTask.ConfigureAwait(false); } catch { /* ignore */ }
            _recvTask = null;
        }

        _udp?.Dispose();
        _udp = null;
    }
}
