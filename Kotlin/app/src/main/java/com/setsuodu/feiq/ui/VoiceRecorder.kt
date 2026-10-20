package com.setsuodu.feiq.ui

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * 长按发语音：MediaRecorder → AAC/M4A。
 * 调用方负责权限与 UI；本类只管 start/stop/cancel。
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    /** 开始录音，返回输出文件（尚未落盘完整数据）。失败返回 null。 */
    fun start(): File? {
        stopInternal(delete = true)
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val out = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        return try {
            val r = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(16_000)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            outputFile = out
            startedAt = System.currentTimeMillis()
            out
        } catch (e: Exception) {
            try {
                out.delete()
            } catch (_: Exception) {
            }
            outputFile = null
            recorder = null
            null
        }
    }

    /**
     * 停止并返回文件。
     * @param minMs 短于此毫秒视为无效（取消并删除）
     * @return 有效录音文件，或 null（太短 / 失败）
     */
    fun stop(minMs: Long = 500L): File? {
        val file = outputFile
        val elapsed = System.currentTimeMillis() - startedAt
        stopInternal(delete = false)
        if (file == null || !file.exists() || file.length() < 100 || elapsed < minMs) {
            try {
                file?.delete()
            } catch (_: Exception) {
            }
            return null
        }
        return file
    }

    /** 取消并删除临时文件 */
    fun cancel() {
        stopInternal(delete = true)
    }

    private fun stopInternal(delete: Boolean) {
        val r = recorder
        val f = outputFile
        recorder = null
        outputFile = null
        startedAt = 0L
        if (r != null) {
            try {
                r.stop()
            } catch (_: Exception) {
            }
            try {
                r.reset()
            } catch (_: Exception) {
            }
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
        if (delete && f != null) {
            try {
                f.delete()
            } catch (_: Exception) {
            }
        }
    }
}
