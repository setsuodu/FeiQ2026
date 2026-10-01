package com.setsuodu.feiq.data

import android.content.Context
import java.io.File
import java.util.UUID

/** 本地配置：用户名、头像、中继 URL、模式、下载目录、稳定中继 clientId */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("feiq2026", Context.MODE_PRIVATE)
    private val filesDir = context.filesDir

    var userName: String
        get() = prefs.getString(KEY_USER, null) ?: (android.os.Build.MODEL ?: "Android")
        set(v) = prefs.edit().putString(KEY_USER, v.trim().ifEmpty { "Android" }).apply()

    /** 头像本地文件路径，可能不存在 */
    var avatarPath: String?
        get() = prefs.getString(KEY_AVATAR, null)
        set(v) = prefs.edit().putString(KEY_AVATAR, v).apply()

    var serverUrl: String
        get() = prefs.getString(KEY_URL, null) ?: DEFAULT_URL
        set(v) = prefs.edit().putString(KEY_URL, v.trim().ifEmpty { DEFAULT_URL }).apply()

    /** 0=UDP 1=WebSocket */
    var modeIndex: Int
        get() = prefs.getInt(KEY_MODE, 0)
        set(v) = prefs.edit().putInt(KEY_MODE, v).apply()

    /**
     * 中继稳定身份，用于服务端离线消息队列命中。
     * 首次生成后持久化；勿用纯 MODEL（同型号会冲突）。
     */
    val relayClientId: String
        get() {
            val existing = prefs.getString(KEY_CLIENT_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val id = "android-" + UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit().putString(KEY_CLIENT_ID, id).apply()
            return id
        }

    val downloadDir: File
        get() {
            val custom = prefs.getString(KEY_DOWNLOAD, null)
            val dir = if (!custom.isNullOrBlank()) File(custom) else File(filesDir, "downloads")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun setDownloadDir(path: String) {
        prefs.edit().putString(KEY_DOWNLOAD, path).apply()
    }

    fun avatarFile(): File = File(filesDir, "avatar.jpg")

    companion object {
        const val DEFAULT_URL = "wss://s0.v100.vip:27658/ws"
        private const val KEY_USER = "user_name"
        private const val KEY_AVATAR = "avatar_path"
        private const val KEY_URL = "server_url"
        private const val KEY_MODE = "mode_index"
        private const val KEY_DOWNLOAD = "download_dir"
        private const val KEY_CLIENT_ID = "relay_client_id"
    }
}
