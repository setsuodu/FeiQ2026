using System.Collections.Concurrent;
using System.Net;
using System.Net.WebSockets;
using RelayService;

// ============================================================
// FeiQ 2026 WebSocket 中继服务器（含 SQLite 离线消息 + 安全连接日志）
// 用法: dotnet run -- --port 9000
// 数据目录: 环境变量 RELAY_DATA_DIR，默认 ./data
// Docker: 暴露 9000，挂载 /app/data
//
// 安全日志增强：
//   - 记录真实客户端 IP（支持反代 X-Forwarded-For / X-Real-IP）
//   - 记录 User-Agent
//   - 连接/断开/顶号 事件写入 SQLite connection_logs
//   - known_clients 记录 last_ip / last_ua / connect_count
//   - /stats 返回在线列表 + 最近连接历史 + 已知用户摘要
// ============================================================

var port = 9000;
for (int i = 0; i < args.Length - 1; i++)
{
    if (args[i] is "--port" or "-p" && int.TryParse(args[i + 1], out var p))
        port = p;
}

var dataDir = Environment.GetEnvironmentVariable("RELAY_DATA_DIR");
if (string.IsNullOrWhiteSpace(dataDir))
    dataDir = Path.Combine(AppContext.BaseDirectory, "data");
Directory.CreateDirectory(dataDir);
var dbPath = Path.Combine(dataDir, "offline.db");

using var offlineStore = new OfflineStore(dbPath);
var clients = new ConcurrentDictionary<string, ClientSession>();

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.UseUrls($"http://0.0.0.0:{port}");

// 信任反代头（家用常见 Nginx / Caddy / FRP 等）
builder.Services.Configure<Microsoft.AspNetCore.Builder.ForwardedHeadersOptions>(options =>
{
    options.ForwardedHeaders = Microsoft.AspNetCore.HttpOverrides.ForwardedHeaders.XForwardedFor
                             | Microsoft.AspNetCore.HttpOverrides.ForwardedHeaders.XForwardedProto;
    // 家用部署通常信任所有，生产可再收紧 KnownProxies / KnownNetworks
    options.KnownNetworks.Clear();
    options.KnownProxies.Clear();
});

var app = builder.Build();
app.UseForwardedHeaders();

app.UseWebSockets(new WebSocketOptions
{
    KeepAliveInterval = TimeSpan.FromSeconds(30)
});

// 定时清理过期离线消息
_ = Task.Run(async () =>
{
    while (true)
    {
        try
        {
            await Task.Delay(TimeSpan.FromHours(1));
            var n = offlineStore.PurgeExpired();
            if (n > 0)
                Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] 清理过期离线消息 {n} 条");
        }
        catch { /* ignore */ }
    }
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
                   ?? context.Request.Query["clientId"].FirstOrDefault()
                   ?? Guid.NewGuid().ToString("N")[..8];

    var ip = GetClientIp(context);
    var ua = context.Request.Headers.UserAgent.ToString();
    if (string.IsNullOrWhiteSpace(ua)) ua = "(none)";

    var ws = await context.WebSockets.AcceptWebSocketAsync();
    var session = new ClientSession(clientId, ws, ip, ua);

    // 同 clientId 重连：踢掉旧连接
    if (clients.TryGetValue(clientId, out var old))
    {
        clients.TryRemove(clientId, out _);
        try { old.Socket.Abort(); } catch { }
        var kickMsg = $"旧连接被顶 (oldIp={old.Ip})";
        Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] ~ 顶号  {clientId}  ip={ip}  {kickMsg}");
        offlineStore.LogConnection("kick", clientId, ip, ua, kickMsg);
    }

    clients[clientId] = session;
    offlineStore.TouchClient(clientId, ip, ua);
    offlineStore.LogConnection("connect", clientId, ip, ua, $"online={clients.Count}");

    Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] + 上线  {clientId}  ip={ip}  ua={Truncate(ua, 80)}  当前在线: {clients.Count}");

    // 补发离线队列
    try
    {
        var pending = offlineStore.DequeueAll(clientId);
        if (pending.Count > 0)
        {
            Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] → 补发离线 {pending.Count} 条 -> {clientId}");
            foreach (var item in pending)
            {
                if (ws.State != WebSocketState.Open) break;
                await SendSafe(session, item.Payload);
            }
        }
    }
    catch (Exception ex)
    {
        Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] 补发失败 {clientId}: {ex.Message}");
    }

    try
    {
        await ReceiveLoop(session, clients, offlineStore);
    }
    finally
    {
        clients.TryRemove(clientId, out _);
        offlineStore.TouchClient(clientId); // 保留 known，便于下次离线堆积
        offlineStore.LogConnection("disconnect", clientId, ip, ua, $"online={clients.Count}");
        try { await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", CancellationToken.None); }
        catch { }
        ws.Dispose();
        Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] - 下线  {clientId}  ip={ip}  当前在线: {clients.Count}");
    }
});

app.MapGet("/", () =>
{
    var (msgCount, offlineUsers) = offlineStore.Stats();
    return Results.Text(
        $"FeiQ 2026 Relay Server OK\n" +
        $"Online: {clients.Count}\n" +
        $"Offline queue: {msgCount} msgs / {offlineUsers} users\n" +
        $"WS endpoint: /ws\n" +
        $"DB: {dbPath}\n" +
        $"Security: connection_logs + known_clients IP/UA tracking enabled\n",
        "text/plain");
});

app.MapGet("/stats", () =>
{
    var (msgCount, offlineUsers) = offlineStore.Stats();
    return Results.Json(new
    {
        online = clients.Count,
        onlineClients = clients.Values
            .OrderBy(c => c.Id)
            .Select(c => new
            {
                clientId = c.Id,
                ip = c.Ip,
                ua = c.Ua,
                connectedAt = c.ConnectedAt.ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss")
            })
            .ToArray(),
        offlineMessages = msgCount,
        offlineUsers,
        knownClients = offlineStore.KnownClientsSummary(),
        recentConnections = offlineStore.RecentConnections(80),
        dbPath
    });
});

// 简单健康检查
app.MapGet("/health", () => Results.Ok(new { status = "ok", online = clients.Count }));

Console.WriteLine($"FeiQ 2026 Relay 已启动  ws://0.0.0.0:{port}/ws");
Console.WriteLine($"离线库 + 安全日志: {dbPath}");
Console.WriteLine("按 Ctrl+C 退出");
app.Run();

// ---------- 工具方法 ----------
static string GetClientIp(HttpContext context)
{
    // 1. 优先 X-Forwarded-For（取最左边第一个非空）
    var xff = context.Request.Headers["X-Forwarded-For"].FirstOrDefault();
    if (!string.IsNullOrWhiteSpace(xff))
    {
        var first = xff.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                       .FirstOrDefault();
        if (!string.IsNullOrWhiteSpace(first))
            return first;
    }

    // 2. X-Real-IP
    var xri = context.Request.Headers["X-Real-IP"].FirstOrDefault();
    if (!string.IsNullOrWhiteSpace(xri))
        return xri.Trim();

    // 3. 直连
    return context.Connection.RemoteIpAddress?.ToString() ?? "unknown";
}

static string Truncate(string s, int max)
{
    if (string.IsNullOrEmpty(s) || s.Length <= max) return s;
    return s[..max] + "…";
}

// ---------- 转发 + 离线落库 ----------
static async Task ReceiveLoop(
    ClientSession self,
    ConcurrentDictionary<string, ClientSession> clients,
    OfflineStore store)
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

            // 1) 转发给当前在线（除自己）
            var tasks = new List<Task>();
            foreach (var kv in clients)
            {
                if (kv.Key == self.Id) continue;
                if (kv.Value.Socket.State != WebSocketState.Open) continue;
                tasks.Add(SendSafe(kv.Value, data));
            }
            if (tasks.Count > 0)
                await Task.WhenAll(tasks);

            // 2) 给「已知但不在线」的 client 各存一份（与广播语义一致）
            var online = clients.Keys.ToHashSet(StringComparer.Ordinal);
            var offlineTargets = store.ListKnownClients()
                .Where(id => !online.Contains(id) && id != self.Id)
                .ToList();
            if (offlineTargets.Count > 0)
            {
                var n = store.EnqueueFor(offlineTargets, data);
                if (n > 0)
                    Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] 离线寄存 {n} 份 from={self.Id} size={data.Length}");
            }
        }
    }
    catch (WebSocketException)
    {
        // 对端突然断开
    }
    catch (OperationCanceledException)
    {
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
    public string Ip { get; }
    public string Ua { get; }
    public DateTimeOffset ConnectedAt { get; } = DateTimeOffset.UtcNow;
    public SemaphoreSlim SendLock { get; } = new(1, 1);

    public ClientSession(string id, WebSocket socket, string ip, string ua)
    {
        Id = id;
        Socket = socket;
        Ip = ip;
        Ua = ua;
    }
}
