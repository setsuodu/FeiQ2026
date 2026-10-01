package minifeiq.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import minifeiq.service.Peer

data class ChatRow(
    val id: Long,
    val peerKey: String,
    val peerName: String,
    val direction: String, // in / out / sys
    val body: String,
    val createdAt: Long
)

/** 最近会话摘要（用于列表保留离线联系人） */
data class PeerSession(
    val peerKey: String,
    val peerName: String,
    val lastAt: Long
)

/**
 * 聊天记录。peer_key 约定：host:{主机名小写}（协议 HostName），
 * 不用用户名|IP（都会变），也不用 MAC（协议无此字段）。
 */
class ChatStore(context: Context) : SQLiteOpenHelper(context, "chat.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                peer_key TEXT NOT NULL,
                peer_name TEXT NOT NULL,
                direction TEXT NOT NULL,
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_peer ON messages(peer_key, created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun add(peerKey: String, peerName: String, direction: String, body: String) {
        writableDatabase.insert("messages", null, ContentValues().apply {
            put("peer_key", peerKey)
            put("peer_name", peerName)
            put("direction", direction)
            put("body", body)
            put("created_at", System.currentTimeMillis())
        })
    }

    fun clear(peerKey: String) {
        writableDatabase.delete("messages", "peer_key = ?", arrayOf(peerKey))
    }

    fun clearAll() {
        writableDatabase.delete("messages", null, null)
    }

    fun recent(peerKey: String, limit: Int = 200): List<ChatRow> {
        val list = mutableListOf<ChatRow>()
        readableDatabase.rawQuery(
            """
            SELECT id, peer_key, peer_name, direction, body, created_at
            FROM messages WHERE peer_key = ? ORDER BY id DESC LIMIT ?
            """.trimIndent(),
            arrayOf(peerKey, limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    ChatRow(
                        id = c.getLong(0),
                        peerKey = c.getString(1),
                        peerName = c.getString(2),
                        direction = c.getString(3),
                        body = c.getString(4),
                        createdAt = c.getLong(5)
                    )
                )
            }
        }
        return list.asReversed()
    }

    /**
     * 按最后一条消息时间倒序，返回有过聊天记录的会话。
     * 用于离线后仍在列表中展示最近联系人。
     */
    fun listRecentSessions(limit: Int = 100): List<PeerSession> {
        val list = mutableListOf<PeerSession>()
        readableDatabase.rawQuery(
            """
            SELECT peer_key, peer_name, MAX(created_at) AS last_at
            FROM messages
            GROUP BY peer_key
            ORDER BY last_at DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    PeerSession(
                        peerKey = c.getString(0),
                        peerName = c.getString(1) ?: "",
                        lastAt = c.getLong(2)
                    )
                )
            }
        }
        return list
    }

    companion object {
        fun peerKey(p: Peer): String {
            val host = p.hostName.trim()
            return if (host.isNotEmpty()) "host:" + host.lowercase()
            else "ip:" + (p.ip.hostAddress ?: p.ip.toString())
        }

        /** 从 peer_key 还原一个可展示的 Peer（IP 可能是占位） */
        fun peerFromSession(s: PeerSession): Peer {
            val key = s.peerKey
            return when {
                key.startsWith("host:") -> {
                    val host = key.removePrefix("host:")
                    Peer(
                        name = s.peerName.ifBlank { host },
                        hostName = host,
                        ip = java.net.InetAddress.getByName("0.0.0.0")
                    )
                }
                key.startsWith("ip:") -> {
                    val ipStr = key.removePrefix("ip:")
                    val ip = try {
                        java.net.InetAddress.getByName(ipStr)
                    } catch (_: Exception) {
                        java.net.InetAddress.getByName("0.0.0.0")
                    }
                    Peer(
                        name = s.peerName.ifBlank { ipStr },
                        hostName = "",
                        ip = ip
                    )
                }
                else -> Peer(
                    name = s.peerName.ifBlank { key },
                    hostName = key,
                    ip = java.net.InetAddress.getByName("0.0.0.0")
                )
            }
        }
    }
}
