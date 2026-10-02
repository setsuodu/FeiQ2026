using System.IO;
using System.Net;
using Microsoft.Data.Sqlite;

namespace FeiQ2026.Services;

public sealed class ChatMessageRow
{
    public long Id { get; init; }
    public string PeerKey { get; init; } = "";
    public string PeerName { get; init; } = "";
    public string Direction { get; init; } = ""; // in / out / sys
    public string Text { get; init; } = "";
    public DateTime CreatedAt { get; init; }
}

/// <summary>最近会话摘要（用于列表保留离线联系人）</summary>
public sealed class PeerSession
{
    public string PeerKey { get; init; } = "";
    public string PeerName { get; init; } = "";
    public DateTime LastAt { get; init; }
}

/// <summary>
/// 聊天记录。peer_key 约定：host:{机器名小写}（协议 HostName），非 IP/用户名/MAC。
/// </summary>
public sealed class ChatStore : IDisposable
{
    private readonly SqliteConnection _conn;

    public ChatStore(string dbPath)
    {
        var dir = Path.GetDirectoryName(dbPath);
        if (!string.IsNullOrEmpty(dir))
            Directory.CreateDirectory(dir);

        _conn = new SqliteConnection($"Data Source={dbPath}");
        _conn.Open();
        InitSchema();
    }

    private void InitSchema()
    {
        using var cmd = _conn.CreateCommand();
        cmd.CommandText = """
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                peer_key TEXT NOT NULL,
                peer_name TEXT NOT NULL,
                direction TEXT NOT NULL,
                body TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_messages_peer ON messages(peer_key, created_at);
            """;
        cmd.ExecuteNonQuery();
    }

    public void Add(string peerKey, string peerName, string direction, string text)
    {
        using var cmd = _conn.CreateCommand();
        cmd.CommandText = """
            INSERT INTO messages(peer_key, peer_name, direction, body, created_at)
            VALUES ($k, $n, $d, $b, $t)
            """;
        cmd.Parameters.AddWithValue("$k", peerKey);
        cmd.Parameters.AddWithValue("$n", peerName);
        cmd.Parameters.AddWithValue("$d", direction);
        cmd.Parameters.AddWithValue("$b", text);
        cmd.Parameters.AddWithValue("$t", DateTime.Now.ToString("o"));
        cmd.ExecuteNonQuery();
    }

    public IReadOnlyList<ChatMessageRow> GetRecent(string peerKey, int limit = 200)
    {
        // 把历史脏 key 一并捞出，再按 id 排序截断
        var identity = IdentityOf(peerKey, null);
        var aliases = new HashSet<string>(StringComparer.Ordinal) { peerKey, CanonicalPeerKey(peerKey) };
        // 扫描一遍所有 key，凡 Identity 相同的都算别名
        using (var scan = _conn.CreateCommand())
        {
            scan.CommandText = "SELECT DISTINCT peer_key, peer_name FROM messages";
            using var sr = scan.ExecuteReader();
            while (sr.Read())
            {
                var raw = sr.GetString(0);
                var name = sr.IsDBNull(1) ? "" : sr.GetString(1);
                if (IdentityOf(raw, name) == identity)
                    aliases.Add(raw);
            }
        }

        using var cmd = _conn.CreateCommand();
        var paramNames = new List<string>();
        var i = 0;
        foreach (var a in aliases)
        {
            var pn = $"$k{i++}";
            paramNames.Add(pn);
            cmd.Parameters.AddWithValue(pn, a);
        }
        cmd.Parameters.AddWithValue("$limit", limit);
        cmd.CommandText = $"""
            SELECT id, peer_key, peer_name, direction, body, created_at
            FROM messages
            WHERE peer_key IN ({string.Join(",", paramNames)})
            ORDER BY id DESC
            LIMIT $limit
            """;

        var list = new List<ChatMessageRow>();
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            list.Add(new ChatMessageRow
            {
                Id = r.GetInt64(0),
                PeerKey = r.GetString(1),
                PeerName = r.GetString(2),
                Direction = r.GetString(3),
                Text = r.GetString(4),
                CreatedAt = DateTime.TryParse(r.GetString(5), out var dt) ? dt : DateTime.Now
            });
        }
        list.Reverse();
        return list;
    }

    /// <summary>
    /// 稳定会话主键：优先 host（小写），其次 name；忽略 0.0.0.0 / 127.0.0.1 等占位 IP。
    /// WS 模式下远端 IP 常为 Loopback，不能当身份。
    /// </summary>
    public static string MakePeerKey(string? hostName, string? userName, IPAddress? ip)
    {
        var host = (hostName ?? "").Trim();
        if (!string.IsNullOrEmpty(host))
            return "host:" + host.ToLowerInvariant();

        var name = (userName ?? "").Trim();
        if (!string.IsNullOrEmpty(name))
            return "name:" + name.ToLowerInvariant();

        var ipStr = ip?.ToString() ?? "";
        if (ipStr is "" or "0.0.0.0" or "127.0.0.1" or "::" or "::1")
            return "unknown";
        return "ip:" + ipStr;
    }

    public static string MakePeerKey(Peer p) =>
        MakePeerKey(p.HostName, p.Name, p.Ip);

    /// <summary>
    /// 把历史脏 key（ip:127.0.0.1 / ip:0.0.0.0 / 大小写 host）归一到规范 key。
    /// </summary>
    public static string CanonicalPeerKey(string rawKey, string? peerName = null)
    {
        if (string.IsNullOrWhiteSpace(rawKey))
            return MakePeerKey(null, peerName, null);

        var key = rawKey.Trim();
        if (key.StartsWith("host:", StringComparison.OrdinalIgnoreCase))
            return "host:" + key["host:".Length..].Trim().ToLowerInvariant();
        if (key.StartsWith("name:", StringComparison.OrdinalIgnoreCase))
            return "name:" + key["name:".Length..].Trim().ToLowerInvariant();
        if (key.StartsWith("ip:", StringComparison.OrdinalIgnoreCase))
        {
            var ipStr = key["ip:".Length..].Trim();
            if (ipStr is "" or "0.0.0.0" or "127.0.0.1" or "::" or "::1")
                return MakePeerKey(null, peerName, null);
            return "ip:" + ipStr;
        }
        return "host:" + key.ToLowerInvariant();
    }

    /// <summary>
    /// 按最后一条消息时间倒序，返回有过聊天记录的会话。
    /// 同一规范 key 只保留一条（合并历史脏 key 造成的重复）。
    /// </summary>
    public IReadOnlyList<PeerSession> ListRecentSessions(int limit = 100)
    {
        using var cmd = _conn.CreateCommand();
        cmd.CommandText = """
            SELECT peer_key, peer_name, MAX(created_at) AS last_at
            FROM messages
            GROUP BY peer_key
            ORDER BY last_at DESC
            """;

        var best = new Dictionary<string, PeerSession>(StringComparer.Ordinal);
        using (var r = cmd.ExecuteReader())
        {
            while (r.Read())
            {
                var rawKey = r.GetString(0);
                var name = r.IsDBNull(1) ? "" : r.GetString(1);
                var lastAt = DateTime.TryParse(r.GetString(2), out var dt) ? dt : DateTime.MinValue;
                var canon = CanonicalPeerKey(rawKey, name);
                if (canon == "unknown" && string.IsNullOrWhiteSpace(name))
                    continue;

                if (!best.TryGetValue(canon, out var old) || lastAt > old.LastAt)
                {
                    best[canon] = new PeerSession
                    {
                        PeerKey = canon,
                        PeerName = name,
                        LastAt = lastAt
                    };
                }
                else if (string.IsNullOrWhiteSpace(old.PeerName) && !string.IsNullOrWhiteSpace(name))
                {
                    best[canon] = new PeerSession
                    {
                        PeerKey = canon,
                        PeerName = name,
                        LastAt = old.LastAt
                    };
                }
            }
        }

        // 二次合并：host:xxx 与 name:xxx（同名）视为同一人
        var byIdentity = new Dictionary<string, PeerSession>(StringComparer.Ordinal);
        foreach (var s in best.Values)
        {
            var id = IdentityOf(s.PeerKey, s.PeerName);
            if (!byIdentity.TryGetValue(id, out var old) || s.LastAt > old.LastAt)
            {
                // 优先保留 host: 前缀的 key
                var prefer = s;
                if (old != null
                    && old.PeerKey.StartsWith("host:", StringComparison.Ordinal)
                    && !s.PeerKey.StartsWith("host:", StringComparison.Ordinal))
                {
                    prefer = new PeerSession
                    {
                        PeerKey = old.PeerKey,
                        PeerName = string.IsNullOrWhiteSpace(s.PeerName) ? old.PeerName : s.PeerName,
                        LastAt = s.LastAt > old.LastAt ? s.LastAt : old.LastAt
                    };
                }
                else if (old != null && s.PeerKey.StartsWith("host:", StringComparison.Ordinal))
                {
                    prefer = new PeerSession
                    {
                        PeerKey = s.PeerKey,
                        PeerName = string.IsNullOrWhiteSpace(s.PeerName) ? old.PeerName : s.PeerName,
                        LastAt = s.LastAt > old.LastAt ? s.LastAt : old.LastAt
                    };
                }
                byIdentity[id] = prefer;
            }
            else if (string.IsNullOrWhiteSpace(old.PeerName) && !string.IsNullOrWhiteSpace(s.PeerName))
            {
                byIdentity[id] = new PeerSession
                {
                    PeerKey = old.PeerKey,
                    PeerName = s.PeerName,
                    LastAt = old.LastAt
                };
            }
        }

        return byIdentity.Values
            .OrderByDescending(s => s.LastAt)
            .Take(limit)
            .ToList();
    }

    /// <summary>
    /// 用于列表去重的逻辑身份。
    /// 优先用显示名（小写），并剥掉 "|127.0.0.1" 这类脏后缀；
    /// 没有名字时再退回 host/name key 后缀。
    /// </summary>
    public static string IdentityOf(string peerKey, string? peerName)
    {
        static string Clean(string s)
        {
            s = (s ?? "").Trim().ToLowerInvariant();
            // 历史脏数据：host 或 name 里被拼进了 |ip
            var pipe = s.IndexOf('|');
            if (pipe > 0) s = s[..pipe].Trim();
            // 再剥一层 @xxx（若有）
            var at = s.IndexOf('@');
            if (at > 0) s = s[..at].Trim();
            return s;
        }

        var n = Clean(peerName ?? "");
        if (!string.IsNullOrEmpty(n) && n is not ("0.0.0.0" or "127.0.0.1" or "unknown"))
            return n;

        var k = CanonicalPeerKey(peerKey, peerName);
        if (k.StartsWith("host:", StringComparison.Ordinal))
            return Clean(k["host:".Length..]);
        if (k.StartsWith("name:", StringComparison.Ordinal))
            return Clean(k["name:".Length..]);
        return Clean(k);
    }

    /// <summary>收集与指定 peerKey / peerName 同一逻辑身份的所有历史 peer_key</summary>
    public List<string> ListAliasKeys(string peerKey, string? peerName = null)
    {
        var identity = IdentityOf(peerKey, peerName);
        var aliases = new HashSet<string>(StringComparer.Ordinal) { peerKey, CanonicalPeerKey(peerKey, peerName) };
        try
        {
            using var scan = _conn.CreateCommand();
            scan.CommandText = "SELECT DISTINCT peer_key, peer_name FROM messages";
            using var sr = scan.ExecuteReader();
            while (sr.Read())
            {
                var raw = sr.GetString(0);
                var name = sr.IsDBNull(1) ? "" : sr.GetString(1);
                if (IdentityOf(raw, name) == identity)
                    aliases.Add(raw);
            }
        }
        catch { /* ignore */ }
        return aliases.ToList();
    }

    /// <summary>从 peer_key 还原一个可展示的 Peer（IP 可能是占位）</summary>
    public static Peer PeerFromSession(PeerSession s)
    {
        var key = CanonicalPeerKey(s.PeerKey, s.PeerName);
        if (key.StartsWith("host:", StringComparison.Ordinal))
        {
            var host = key["host:".Length..];
            return new Peer
            {
                Name = string.IsNullOrWhiteSpace(s.PeerName) ? host : s.PeerName,
                HostName = host,
                Ip = IPAddress.Any
            };
        }
        if (key.StartsWith("name:", StringComparison.Ordinal))
        {
            var name = key["name:".Length..];
            return new Peer
            {
                Name = string.IsNullOrWhiteSpace(s.PeerName) ? name : s.PeerName,
                HostName = name,
                Ip = IPAddress.Any
            };
        }
        if (key.StartsWith("ip:", StringComparison.Ordinal))
        {
            var ipStr = key["ip:".Length..];
            IPAddress ip;
            try { ip = IPAddress.Parse(ipStr); }
            catch { ip = IPAddress.Any; }
            return new Peer
            {
                Name = string.IsNullOrWhiteSpace(s.PeerName) ? ipStr : s.PeerName,
                HostName = "",
                Ip = ip
            };
        }
        return new Peer
        {
            Name = string.IsNullOrWhiteSpace(s.PeerName) ? key : s.PeerName,
            HostName = "",
            Ip = IPAddress.Any
        };
    }

    /// <summary>清空与指定 peer 的本地聊天记录（含所有历史脏 key 别名）</summary>
    public void Clear(string peerKey, string? peerName = null)
    {
        var aliases = ListAliasKeys(peerKey, peerName);
        if (aliases.Count == 0)
        {
            using var cmd0 = _conn.CreateCommand();
            cmd0.CommandText = "DELETE FROM messages WHERE peer_key = $k";
            cmd0.Parameters.AddWithValue("$k", peerKey);
            cmd0.ExecuteNonQuery();
            return;
        }

        using var cmd = _conn.CreateCommand();
        var pnames = new List<string>();
        for (var i = 0; i < aliases.Count; i++)
        {
            var pn = $"$k{i}";
            pnames.Add(pn);
            cmd.Parameters.AddWithValue(pn, aliases[i]);
        }
        cmd.CommandText = $"DELETE FROM messages WHERE peer_key IN ({string.Join(",", pnames)})";
        cmd.ExecuteNonQuery();
    }

    /// <summary>清空全部聊天记录</summary>
    public void ClearAll()
    {
        using var cmd = _conn.CreateCommand();
        cmd.CommandText = "DELETE FROM messages";
        cmd.ExecuteNonQuery();
    }

    public void Dispose() => _conn.Dispose();
}
