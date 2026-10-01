package minifeiq.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class OutboxRow(
    val id: Long,
    val peerKey: String,
    val peerName: String,
    val hostName: String,
    val body: String,
    val createdAt: Long
)

/**
 * 本机待发文本队列：中继断开时先落库，连上后再发出。
 * （服务端负责「对方离线」的补发；这里负责「自己当时没连上中继」。）
 */
class OutboxStore(context: Context) : SQLiteOpenHelper(context, "outbox.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE outbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                peer_key TEXT NOT NULL,
                peer_name TEXT NOT NULL,
                host_name TEXT NOT NULL,
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun enqueue(peerKey: String, peerName: String, hostName: String, body: String): Long {
        return writableDatabase.insert("outbox", null, ContentValues().apply {
            put("peer_key", peerKey)
            put("peer_name", peerName)
            put("host_name", hostName)
            put("body", body)
            put("created_at", System.currentTimeMillis())
        })
    }

    fun listAll(): List<OutboxRow> {
        val list = mutableListOf<OutboxRow>()
        readableDatabase.rawQuery(
            "SELECT id, peer_key, peer_name, host_name, body, created_at FROM outbox ORDER BY id ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    OutboxRow(
                        id = c.getLong(0),
                        peerKey = c.getString(1),
                        peerName = c.getString(2) ?: "",
                        hostName = c.getString(3) ?: "",
                        body = c.getString(4) ?: "",
                        createdAt = c.getLong(5)
                    )
                )
            }
        }
        return list
    }

    fun delete(id: Long) {
        writableDatabase.delete("outbox", "id = ?", arrayOf(id.toString()))
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }
}
