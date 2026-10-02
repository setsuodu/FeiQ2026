using System.IO;
using Microsoft.Data.Sqlite;

namespace FeiQ2026.Services;

public sealed class OutboxRow
{
    public long Id { get; init; }
    public string PeerKey { get; init; } = "";
    public string PeerName { get; init; } = "";
    public string HostName { get; init; } = "";
    public string Body { get; init; } = "";
    public DateTime CreatedAt { get; init; }
}

/// <summary>
/// 本机待发文本队列：中继断开时先落库，连上后再发出。
/// （服务端负责「对方离线」的补发；这里负责「自己当时没连上中继」。）
/// </summary>
public sealed class OutboxStore : IDisposable
{
    private readonly SqliteConnection _conn;

    public OutboxStore(string dbPath)
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
            CREATE TABLE IF NOT EXISTS outbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                peer_key TEXT NOT NULL,
                peer_name TEXT NOT NULL,
                host_name TEXT NOT NULL,
                body TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            """;
        cmd.ExecuteNonQuery();
    }

    public long Enqueue(string peerKey, string peerName, string hostName, string body)
    {
        using var cmd = _conn.CreateCommand();
        cmd.CommandText = """
            INSERT INTO outbox(peer_key, peer_name, host_name, body, created_at)
            VALUES ($k, $n, $h, $b, $t);
            SELECT last_insert_rowid();
            """;
        cmd.Parameters.AddWithValue("$k", peerKey);
        cmd.Parameters.AddWithValue("$n", peerName);
        cmd.Parameters.AddWithValue("$h", hostName ?? "");
        cmd.Parameters.AddWithValue("$b", body);
        cmd.Parameters.AddWithValue("$t", DateTime.Now.ToString("o"));
        var id = cmd.ExecuteScalar();
        return id is long l ? l : Convert.ToInt64(id);
    }

    public IReadOnlyList<OutboxRow> ListAll()
    {
        using var cmd = _conn.CreateCommand();
        cmd.CommandText = """
            SELECT id, peer_key, peer_name, host_name, body, created_at
            FROM outbox ORDER BY id ASC
            """;
        var list = new List<OutboxRow>();
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            list.Add(new OutboxRow
            {
                Id = r.GetInt64(0),
                PeerKey = r.GetString(1),
                PeerName = r.IsDBNull(2) ? "" : r.GetString(2),
                HostName = r.IsDBNull(3) ? "" : r.GetString(3),
                Body = r.IsDBNull(4) ? "" : r.GetString(4),
                CreatedAt = DateTime.TryParse(r.GetString(5), out var dt) ? dt : DateTime.Now
            });
        }
        return list;
    }

    public void Delete(long id)
    {
        using var cmd = _conn.CreateCommand();
        cmd.CommandText = "DELETE FROM outbox WHERE id = $id";
        cmd.Parameters.AddWithValue("$id", id);
        cmd.ExecuteNonQuery();
    }

    public int Count()
    {
        using var cmd = _conn.CreateCommand();
        cmd.CommandText = "SELECT COUNT(*) FROM outbox";
        var o = cmd.ExecuteScalar();
        return o is long l ? (int)l : Convert.ToInt32(o);
    }

    public void Dispose() => _conn.Dispose();
}
