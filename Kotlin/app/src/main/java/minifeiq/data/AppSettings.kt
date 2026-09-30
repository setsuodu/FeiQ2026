package minifeiq.data

import android.content.Context
import java.io.File

/** 本地配置：用户名、头像、中继 URL、模式、下载目录 */
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
        const val DEFAULT_URL = "ws://192.168.1.101:9000/ws"
        private const val KEY_USER = "user_name"
        private const val KEY_AVATAR = "avatar_path"
        private const val KEY_URL = "server_url"
        private const val KEY_MODE = "mode_index"
        private const val KEY_DOWNLOAD = "download_dir"
    }
}
