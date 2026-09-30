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

    companion object {
        fun peerKey(p: Peer): String {
            val host = p.hostName.trim()
            return if (host.isNotEmpty()) "host:" + host.lowercase()
            else "ip:" + (p.ip.hostAddress ?: p.ip.toString())
        }
    }
}
