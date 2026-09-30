package minifeiq.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * FeiQ2026 之间头像同步缓存（与飞秋2013无关）。
 * 协议：普通文本消息，正文 = Magic + Base64(JPEG缩略图)
 */
object AvatarCache {
    const val MAGIC = "__MFQ_AVATAR__:"

    private fun dir(context: Context): File {
        val d = File(context.filesDir, "avatars")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun pathFor(context: Context, peerKey: String): File {
        val safe = peerKey.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(dir(context), "$safe.jpg")
    }

    fun save(context: Context, peerKey: String, jpeg: ByteArray) {
        if (jpeg.isEmpty() || jpeg.size > 200_000) return
        pathFor(context, peerKey).writeBytes(jpeg)
    }

    fun getPath(context: Context, peerKey: String): String? {
        val f = pathFor(context, peerKey)
        return if (f.exists()) f.absolutePath else null
    }

    fun encodeThumbnail(avatarPath: String?, maxEdge: Int = 64, quality: Int = 55): ByteArray? {
        if (avatarPath.isNullOrEmpty()) return null
        val file = File(avatarPath)
        if (!file.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(avatarPath, bounds)
            var sample = 1
            val max = maxOf(bounds.outWidth, bounds.outHeight)
            while (max / sample > maxEdge * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(avatarPath, opts) ?: return null
            val scale = maxEdge.toFloat() / maxOf(bmp.width, bmp.height)
            val w = (bmp.width * scale).toInt().coerceAtLeast(1)
            val h = (bmp.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
            if (scaled !== bmp) bmp.recycle()
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            scaled.recycle()
            baos.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    fun buildSyncMessage(avatarPath: String?): String? {
        val bytes = encodeThumbnail(avatarPath) ?: return null
        if (bytes.isEmpty()) return null
        return MAGIC + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun tryParse(text: String): ByteArray? {
        if (!text.startsWith(MAGIC)) return null
        return try {
            val raw = Base64.decode(text.substring(MAGIC.length), Base64.DEFAULT)
            if (raw.isNotEmpty()) raw else null
        } catch (_: Exception) {
            null
        }
    }
}
