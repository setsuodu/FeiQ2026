using System.Net;

namespace MiniFeiQ.Transport;

/// <summary>
/// 传输层抽象。后续可实现 WebSocket / 纯TCP / KCP / QUIC 等，
/// 上层协议（IPMSG）不感知具体传输方式。
/// </summary>
public interface ITransport : IAsyncDisposable
{
    /// <summary>本地绑定端口（UDP时有意义）</summary>
    int LocalPort { get; }

    /// <summary>是否支持广播/组播发现</summary>
    bool SupportsBroadcast { get; }

    /// <summary>收到原始数据时触发（payload + 远端终点）</summary>
    event Action<byte[], IPEndPoint>? DataReceived;

    /// <summary>启动监听</summary>
    Task StartAsync(CancellationToken ct = default);

    /// <summary>发送到指定终点</summary>
    Task SendAsync(byte[] data, IPEndPoint remote, CancellationToken ct = default);

    /// <summary>广播（仅 SupportsBroadcast 为 true 时有效）</summary>
    Task BroadcastAsync(byte[] data, int port, CancellationToken ct = default);
}
