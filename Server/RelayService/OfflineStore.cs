using Microsoft.Data.Sqlite;

namespace RelayService;

/// <summary>
/// SQLite 离线消息队列 + 轻量安全连接记录。
/// 当前中继是「广播网状」：在线全员收包；离线用户按 clientId 存一份，上线后补发。
/// </summary>
public sealed class OfflineStore : IDisposable
{
    private readonly string _dbPath;
    private readonly SqliteConnection _conn;
    private readonly object _lock = new();

    public int MaxPerClient { get; init; } = 500;
    public int MaxPayloadBytes { get; init; } = 1_048_576; // 1MB，更大的文件帧跳过落库
    public TimeSpan Ttl { get; init; } = TimeSpan.FromDays(7);
    public int MaxConnectionLogs { get; init; } = 2000; // 连接历史保留条数

    public OfflineStore(string dbPath)
    {
        _dbPath = dbPath;
        var dir = Path.GetDirectoryName(dbPath);
        if (!string.IsNullOrEmpty(dir))
            Directory.CreateDirectory(dir);

        _conn = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = dbPath,
            Mode = SqliteOpenMode.ReadWriteCreate
        }.ToString());
        _conn.Open();
        InitSchema();
    }

    private void InitSchema()
    {
        using var cmd = _conn.CreateCommand();
        cmd.CommandText =
            """
            CREATE TABLE IF NOT EXISTS known_clients (
                client_id TEXT PRIMARY KEY,
                last_seen INTEGER NOT NULL,
                last_ip TEXT,
                last_ua TEXT,
                connect_count INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS offline_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                to_client_id TEXT NOT NULL,
                payload BLOB NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_offline_to
                ON offline_messages(to_client_id, id);

            -- 连接/安全事件日志（轻量）
            CREATE TABLE IF NOT EXISTS connection_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                event TEXT NOT NULL,          -- connect / disconnect / kick / reject
                client_id TEXT,
                ip TEXT,
                ua TEXT,
                detail TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_conn_ts ON connection_logs(ts DESC);
            """;
        cmd.ExecuteNonQuery();

        // 兼容旧库：补列
        TryAddColumn("known_clients", "last_ip", "TEXT");
        TryAddColumn("known_clients", "last_ua", "TEXT");
        TryAddColumn("known_clients", "connect_count", "INTEGER NOT NULL DEFAULT 0");
    }

    private void TryAddColumn(string table, string column, string type)
    {
        try
        {
            using var cmd = _conn.CreateCommand();
            cmd.CommandText = $"ALTER TABLE {table} ADD COLUMN {column} {type}";
            cmd.ExecuteNonQuery();
        }
        catch
        {
            // 列已存在则忽略
        }
    }

    public void TouchClient(string clientId, string? ip = null, string? ua = null)
    {
        if (string.IsNullOrWhiteSpace(clientId)) return;
        lock (_lock)
        {
            using var cmd = _conn.CreateCommand();
            cmd.CommandText =
                """
                INSERT INTO known_clients(client_id, last_seen, last_ip, last_ua, connect_count)
                VALUES($id, $t, $ip, $ua, 1)
                ON CONFLICT(client_id) DO UPDATE SET
                    last_seen = $t,
                    last_ip = COALESCE($ip, last_ip),
                    last_ua = COALESCE($ua, last_ua),
                    connect_count = connect_count + 1
                """;
            cmd.Parameters.AddWithValue("$id", clientId);
            cmd.Parameters.AddWithValue("$t", DateTimeOffset.UtcNow.ToUnixTimeSeconds());
            cmd.Parameters.AddWithValue("$ip", (object?)ip ?? DBNull.Value);
            cmd.Parameters.AddWithValue("$ua", (object?)ua ?? DBNull.Value);
            cmd.ExecuteNonQuery();
        }
    }

    public void LogConnection(string eventType, string? clientId, string? ip, string? ua, string? detail = null)
    {
        lock (_lock)
        {
            using var cmd = _conn.CreateCommand();
            cmd.CommandText =
                """
                INSERT INTO connection_logs(ts, event, client_id, ip, ua, detail)
                VALUES($ts, $e, $cid, $ip, $ua, $d)
                """;
            cmd.Parameters.AddWithValue("$ts", DateTimeOffset.UtcNow.ToUnixTimeSeconds());
            cmd.Parameters.AddWithValue("$e", eventType);
            cmd.Parameters.AddWithValue("$cid", (object?)clientId ?? DBNull.Value);
            cmd.Parameters.AddWithValue("$ip", (object?)ip ?? DBNull.Value);
            cmd.Parameters.AddWithValue("$ua", (object?)ua ?? DBNull.Value);
            cmd.Parameters.AddWithValue("$d", (object?)detail ?? DBNull.Value);
            cmd.ExecuteNonQuery();

            // 简单滚动清理，防止无限增长
            using var cnt = _conn.CreateCommand();
            cnt.CommandText = "SELECT COUNT(*) FROM connection_logs";
            var total = Convert.ToInt32(cnt.ExecuteScalar());
            if (total > MaxConnectionLogs)
            {
                using var del = _conn.CreateCommand();
                del.CommandText =
                    """
                    DELETE FROM connection_logs WHERE id IN (
                        SELECT id FROM connection_logs ORDER BY id ASC LIMIT $n
                    )
                    """;
                del.Parameters.AddWithValue("$n", total - MaxConnectionLogs);
                del.ExecuteNonQuery();
            }
        }
    }

    public List<object> RecentConnections(int limit = 50)
    {
        lock (_lock)
        {
            var list = new List<object>();
            using var cmd = _conn.CreateCommand();
            cmd.CommandText =
                """
                SELECT ts, event, client_id, ip, ua, detail
                FROM connection_logs
                ORDER BY id DESC
                LIMIT $n
                """;
            cmd.Parameters.AddWithValue("$n", limit);
            using var r = cmd.ExecuteReader();
            while (r.Read())
            {
                list.Add(new
                {
                    ts = DateTimeOffset.FromUnixTimeSeconds(r.GetInt64(0)).ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss"),
                    eventType = r.GetString(1),
                    clientId = r.IsDBNull(2) ? null : r.GetString(2),
                    ip = r.IsDBNull(3) ? null : r.GetString(3),
                    ua = r.IsDBNull(4) ? null : r.GetString(4),
                    detail = r.IsDBNull(5) ? null : r.GetString(5)
                });
            }
            return list;
        }
    }

    public List<object> KnownClientsSummary()
    {
        lock (_lock)
        {
            var list = new List<object>();
            using var cmd = _conn.CreateCommand();
            cmd.CommandText =
                """
                SELECT client_id, last_seen, last_ip, last_ua, connect_count
                FROM known_clients
                ORDER BY last_seen DESC
                """;
            using var r = cmd.ExecuteReader();
            while (r.Read())
            {
                list.Add(new
                {
                    clientId = r.GetString(0),
                    lastSeen = DateTimeOffset.FromUnixTimeSeconds(r.GetInt64(1)).ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss"),
                    lastIp = r.IsDBNull(2) ? null : r.GetString(2),
                    lastUa = r.IsDBNull(3) ? null : r.GetString(3),
                    connectCount = r.IsDBNull(4) ? 0 : r.GetInt32(4)
                });
            }
            return list;
        }
    }

    public List<string> ListKnownClients()
    {
        lock (_lock)
        {
            var list = new List<string>();
            using var cmd = _conn.CreateCommand();
            cmd.CommandText = "SELECT client_id FROM known_clients";
            using var r = cmd.ExecuteReader();
            while (r.Read())
                list.Add(r.GetString(0));
            return list;
        }
    }

    /// <summary>给一批离线 client 各存一份（跳过过大包、已在线由调用方过滤）。</summary>
    public int EnqueueFor(IEnumerable<string> toClientIds, byte[] payload)
    {
        if (payload.Length == 0) return 0;
        if (payload.Length > MaxPayloadBytes) return 0;

        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var exp = now + (long)Ttl.TotalSeconds;
        var n = 0;

        lock (_lock)
        {
            using var tx = _conn.BeginTransaction();
            foreach (var to in toClientIds)
            {
                if (string.IsNullOrWhiteSpace(to)) continue;

                // 超限：删最旧的腾位置
                using (var cnt = _conn.CreateCommand())
                {
                    cnt.Transaction = tx;
                    cnt.CommandText = "SELECT COUNT(*) FROM offline_messages WHERE to_client_id = $to";
                    cnt.Parameters.AddWithValue("$to", to);
                    var count = Convert.ToInt32(cnt.ExecuteScalar());
                    if (count >= MaxPerClient)
                    {
                        var drop = count - MaxPerClient + 1;
                        using var del = _conn.CreateCommand();
                        del.Transaction = tx;
                        del.CommandText =
                            """
                            DELETE FROM offline_messages WHERE id IN (
                                SELECT id FROM offline_messages
                                WHERE to_client_id = $to
                                ORDER BY id ASC
                                LIMIT $n
                            )
                            """;
                        del.Parameters.AddWithValue("$to", to);
                        del.Parameters.AddWithValue("$n", drop);
                        del.ExecuteNonQuery();
                    }
                }

                using (var ins = _conn.CreateCommand())
                {
                    ins.Transaction = tx;
                    ins.CommandText =
                        """
                        INSERT INTO offline_messages(to_client_id, payload, created_at, expires_at)
                        VALUES($to, $p, $c, $e)
                        """;
                    ins.Parameters.AddWithValue("$to", to);
                    ins.Parameters.AddWithValue("$p", payload);
                    ins.Parameters.AddWithValue("$c", now);
                    ins.Parameters.AddWithValue("$e", exp);
                    ins.ExecuteNonQuery();
                    n++;
                }
            }
            tx.Commit();
        }
        return n;
    }

    public sealed record OfflineItem(long Id, byte[] Payload);

    public List<OfflineItem> DequeueAll(string clientId)
    {
        var list = new List<OfflineItem>();
        if (string.IsNullOrWhiteSpace(clientId)) return list;

        lock (_lock)
        {
            var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            // 先清过期
            using (var purge = _conn.CreateCommand())
            {
                purge.CommandText = "DELETE FROM offline_messages WHERE expires_at < $now";
                purge.Parameters.AddWithValue("$now", now);
                purge.ExecuteNonQuery();
            }

            using (var sel = _conn.CreateCommand())
            {
                sel.CommandText =
                    """
                    SELECT id, payload FROM offline_messages
                    WHERE to_client_id = $to
                    ORDER BY id ASC
                    """;
                sel.Parameters.AddWithValue("$to", clientId);
                using var r = sel.ExecuteReader();
                while (r.Read())
                {
                    var id = r.GetInt64(0);
                    var blob = (byte[])r[1];
                    list.Add(new OfflineItem(id, blob));
                }
            }

            if (list.Count > 0)
            {
                using var del = _conn.CreateCommand();
                del.CommandText = "DELETE FROM offline_messages WHERE to_client_id = $to";
                del.Parameters.AddWithValue("$to", clientId);
                del.ExecuteNonQuery();
            }
        }
        return list;
    }

    public (int messages, int clients) Stats()
    {
        lock (_lock)
        {
            using var c1 = _conn.CreateCommand();
            c1.CommandText = "SELECT COUNT(*) FROM offline_messages";
            var m = Convert.ToInt32(c1.ExecuteScalar());
            using var c2 = _conn.CreateCommand();
            c2.CommandText = "SELECT COUNT(DISTINCT to_client_id) FROM offline_messages";
            var u = Convert.ToInt32(c2.ExecuteScalar());
            return (m, u);
        }
    }

    public int PurgeExpired()
    {
        lock (_lock)
        {
            var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            using var cmd = _conn.CreateCommand();
            cmd.CommandText = "DELETE FROM offline_messages WHERE expires_at < $now";
            cmd.Parameters.AddWithValue("$now", now);
            return cmd.ExecuteNonQuery();
        }
    }

    public void Dispose()
    {
        lock (_lock)
        {
            _conn.Dispose();
        }
    }
}
