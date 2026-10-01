package com.setsuodu.feiq.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale

enum class MsgKind { Text, Image, Audio, Video, File, Url, Progress }

data class ChatUiMsg(
    val direction: String, // in / out / sys
    val body: String,
    val time: String,
    val kind: MsgKind = MsgKind.Text,
    val filePath: String? = null,
    /** null=不在传输；0f..1f=进度中 */
    val progress: Float? = null,
    val fileMissing: Boolean = false
)

internal val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "ico", "tiff", "tif")
internal val AUDIO_EXTS = setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "ape", "opus")
internal val VIDEO_EXTS = setOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "m4v", "ts", "mpeg", "mpg")

internal fun detectMsgKind(fileName: String): MsgKind {
    val ext = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
    return when {
        ext in IMAGE_EXTS -> MsgKind.Image
        ext in AUDIO_EXTS -> MsgKind.Audio
        ext in VIDEO_EXTS -> MsgKind.Video
        else -> MsgKind.File
    }
}

internal fun isAutoReceiveMedia(fileName: String): Boolean {
    val k = detectMsgKind(fileName)
    return k == MsgKind.Image || k == MsgKind.Audio || k == MsgKind.Video
}

internal fun kindIcon(kind: MsgKind): String = when (kind) {
    MsgKind.Image -> "🖼️"
    MsgKind.Audio -> "🎵"
    MsgKind.Video -> "🎬"
    MsgKind.File -> "📄"
    MsgKind.Url -> "🔗"
    MsgKind.Progress -> "⏳"
    else -> ""
}

internal fun kindLabel(kind: MsgKind): String = when (kind) {
    MsgKind.Image -> "图片"
    MsgKind.Audio -> "音乐"
    MsgKind.Video -> "视频"
    MsgKind.File -> "文件"
    else -> ""
}

/** 从存库文本解析富媒体： [图片] name|/path 或纯文本/URL */
internal fun parseStoredBody(direction: String, body: String, time: String): ChatUiMsg {
    if (direction == "sys") {
        return ChatUiMsg(direction, body, time, MsgKind.Text)
    }
    // [类型] name|path
    if (body.startsWith("[") && body.contains('|')) {
        val pipe = body.lastIndexOf('|')
        val head = body.substring(0, pipe)
        val path = body.substring(pipe + 1)
        val name = File(path).name.ifEmpty { head.substringAfter(']').trim() }
        val kind = when {
            head.startsWith("[图片]") -> MsgKind.Image
            head.startsWith("[音乐]") -> MsgKind.Audio
            head.startsWith("[视频]") -> MsgKind.Video
            head.startsWith("[文件]") -> MsgKind.File
            else -> detectMsgKind(name)
        }
        val missing = path.isNotEmpty() && !File(path).exists()
        return ChatUiMsg(
            direction = direction,
            body = if (missing) "⚠️ $name（已失效）" else "${kindIcon(kind)} $name",
            time = time,
            kind = kind,
            filePath = path,
            fileMissing = missing
        )
    }
    // 旧格式 [文件] name
    if (body.startsWith("[文件]") || body.startsWith("[图片]") || body.startsWith("[音乐]") || body.startsWith("[视频]")) {
        val name = body.substringAfter(']').trim()
        val kind = detectMsgKind(name)
        return ChatUiMsg(direction, "${kindIcon(kind)} $name", time, kind)
    }
    // URL
    val trimmed = body.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        return ChatUiMsg(direction, "🔗 $trimmed", time, MsgKind.Url, filePath = trimmed)
    }
    return ChatUiMsg(direction, body, time, MsgKind.Text)
}

internal fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0)
    if (bytes < 1024L * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024))
    return String.format("%.2fGB", bytes / (1024.0 * 1024 * 1024))
}

internal fun queryDisplayName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
    }
    return uri.lastPathSegment
}
