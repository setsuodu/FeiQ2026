using System.Collections.Concurrent;
using System.Net;
using System.Net.WebSockets;
using System.Text;

// ============================================================
// MiniFeiQ WebSocket 中继服务器
// 用法: dotnet run -- --port 9000
// Docker: 暴露 9000，再用内网穿透映射到公网
// ============================================================

var port = 9000;
for (int i = 0; i < args.Length - 1; i++)
{
    if (args[i] is "--port" or "-p" && int.TryParse(args[i + 1], out var p))
        port = p;
}

var clients = new ConcurrentDictionary<string, ClientSession>();

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.UseUrls($"http://0.0.0.0:{port}");
var app = builder.Build();

app.UseWebSockets(new WebSocketOptions
{
    KeepAliveInterval = TimeSpan.FromSeconds(30)
});

app.Map("/ws", async context =>
{
    if (!context.WebSockets.IsWebSocketRequest)
    {
        context.Response.StatusCode = 400;
        await context.Response.WriteAsync("WebSocket only");
        return;
    }

    var clientId = context.Request.Headers["X-Client-Id"].FirstOrDefault()
                   ?? Guid.NewGuid().ToString("N")[..8];

    var ws = await context.WebSockets.AcceptWebSocketAsync();
    var session = new ClientSession(clientId, ws);

    if (!clients.TryAdd(clientId, session))
    {
        // ID 冲突，换一个
        clientId = clientId + "_" + Guid.NewGuid().ToString("N")[..4];
        session = new ClientSession(clientId, ws);
        clients[clientId] = session;
    }

    Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] + 上线  {clientId}  当前在线: {clients.Count}");

    try
    {
        await ReceiveLoop(session, clients);
    }
    finally
    {
        clients.TryRemove(clientId, out _);
        try { await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", CancellationToken.None); }
        catch { }
        ws.Dispose();
        Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] - 下线  {clientId}  当前在线: {clients.Count}");
    }
});

app.MapGet("/", () => Results.Text(
    $"MiniFeiQ Relay Server OK\nOnline: {clients.Count}\nWS endpoint: /ws\n", "text/plain"));

Console.WriteLine($"MiniFeiQ Relay 已启动  ws://0.0.0.0:{port}/ws");
Console.WriteLine("按 Ctrl+C 退出");
app.Run();

// ---------- 转发逻辑 ----------
static async Task ReceiveLoop(ClientSession self, ConcurrentDictionary<string, ClientSession> clients)
{
    var buffer = new byte[64 * 1024];
    try
    {
        while (self.Socket.State == WebSocketState.Open)
        {
            using var ms = new MemoryStream();
            WebSocketReceiveResult result;
            do
            {
                result = await self.Socket.ReceiveAsync(buffer, CancellationToken.None);
                if (result.MessageType == WebSocketMessageType.Close)
                    return;
                ms.Write(buffer, 0, result.Count);
            } while (!result.EndOfMessage);

            var data = ms.ToArray();
            if (data.Length == 0) continue;

            // 简单广播：转发给除自己以外的所有在线客户端
            var tasks = new List<Task>();
            foreach (var kv in clients)
            {
                if (kv.Key == self.Id) continue;
                if (kv.Value.Socket.State != WebSocketState.Open) continue;
                tasks.Add(SendSafe(kv.Value, data));
            }
            if (tasks.Count > 0)
                await Task.WhenAll(tasks);
        }
    }
    catch (WebSocketException)
    {
        // 对端突然断开（未完成 close handshake），正常下线即可
    }
    catch (OperationCanceledException)
    {
        // 取消时正常退出
    }
}

static async Task SendSafe(ClientSession target, byte[] data)
{
    try
    {
        await target.SendLock.WaitAsync();
        try
        {
            if (target.Socket.State == WebSocketState.Open)
                await target.Socket.SendAsync(data, WebSocketMessageType.Binary, true, CancellationToken.None);
        }
        finally
        {
            target.SendLock.Release();
        }
    }
    catch (Exception ex)
    {
        Console.WriteLine($"[转发失败] -> {target.Id}: {ex.Message}");
    }
}

sealed class ClientSession
{
    public string Id { get; }
    public WebSocket Socket { get; }
    public SemaphoreSlim SendLock { get; } = new(1, 1);

    public ClientSession(string id, WebSocket socket)
    {
        Id = id;
        Socket = socket;
    }
}
