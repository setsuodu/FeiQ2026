package com.setsuodu.feiq

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.setsuodu.feiq.service.IpMsgService
import com.setsuodu.feiq.service.Peer
import java.io.File
import java.io.FileOutputStream

/**
 * 把系统分享进来的 Uri 拷到缓存并走飞秋发文件。
 * UI 气泡更新由 [onPrepared]/[onDone]/[onError] 回调给 App。
 */
fun shareSendFile(
    scope: CoroutineScope,
    context: Context,
    service: IpMsgService?,
    peer: Peer,
    uri: Uri,
    onPrepared: (fileName: String, localPath: String) -> Unit,
    onDone: (fileName: String) -> Unit,
    onError: (String) -> Unit,
) {
    if (service == null) {
        onError("未连接，无法发送分享文件")
        return
    }
    scope.launch(Dispatchers.IO) {
        try {
            val name = queryShareDisplayName(context, uri) ?: "file"
            val tmp = File(context.cacheDir, "share_${System.currentTimeMillis()}_$name")
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                withContext(Dispatchers.Main) { onError("无法读取分享文件") }
                return@launch
            }
            input.use { inn ->
                FileOutputStream(tmp).use { out -> inn.copyTo(out) }
            }
            withContext(Dispatchers.Main) { onPrepared(name, tmp.absolutePath) }
            service.sendFile(peer.ip, tmp.absolutePath)
            withContext(Dispatchers.Main) { onDone(name) }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError(e.message ?: "发送失败") }
        }
    }
}

private fun queryShareDisplayName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
    }
    return uri.lastPathSegment
}
