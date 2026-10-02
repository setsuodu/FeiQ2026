using System.IO;
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
        using var cmd = _conn.CreateCommand();
        cmd.CommandText = """
            SELECT id, peer_key, peer_name, direction, body, created_at
            FROM messages
            WHERE peer_key = $k
            ORDER BY id DESC
            LIMIT $limit
            """;
        cmd.Parameters.AddWithValue("$k", peerKey);
        cmd.Parameters.AddWithValue("$limit", limit);

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

    /// <summary>清空与指定 peer 的本地聊天记录</summary>
    public void Clear(string peerKey)
    {
        using var cmd = _conn.CreateCommand();
        cmd.CommandText = "DELETE FROM messages WHERE peer_key = $k";
        cmd.Parameters.AddWithValue("$k", peerKey);
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
