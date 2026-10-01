package com.setsuodu.feiq.protocol

import java.nio.charset.Charset

data class IpMsgPacket(
    val version: String = "1",
    val packetNo: Long,
    val userName: String,
    val hostName: String,
    val command: Int,
    val extra: String = "",
    val fileExtra: String = ""
) {
    val basicCommand: Int get() = command and 0xFF
    fun hasOption(opt: Int): Boolean = (command and opt) != 0

    fun toBytes(charset: Charset): ByteArray {
        val body = if (fileExtra.isNotEmpty()) {
            "$version:$packetNo:$userName:$hostName:$command:$extra\u0000$fileExtra\u0000"
        } else {
            "$version:$packetNo:$userName:$hostName:$command:$extra\u0000"
        }
        return body.toByteArray(charset)
    }

    companion object {
        fun tryParse(text: String): IpMsgPacket? {
            val parts = text.split(":", limit = 6)
            if (parts.size < 6) return null
            val no = parts[1].toLongOrNull() ?: return null
            val cmd = parts[4].toIntOrNull() ?: return null
            val raw = parts[5]
            val nullIdx = raw.indexOf('\u0000')
            val extra: String
            val fileExtra: String
            if (nullIdx >= 0) {
                extra = raw.substring(0, nullIdx)
                fileExtra = raw.substring(nullIdx + 1).trimEnd('\u0000')
            } else {
                extra = raw.trimEnd('\u0000')
                fileExtra = ""
            }
            return IpMsgPacket(
                version = parts[0],
                packetNo = no,
                userName = parts[2],
                hostName = parts[3],
                command = cmd,
                extra = extra,
                fileExtra = fileExtra
            )
        }
    }
}

data class FileAttachInfo(
    val fileId: Int,
    val fileName: String,
    val size: Long,
    val mtime: Long = 0,
    val fileAttr: Int = IpMsgCommands.FileRegular
) {
    // 兼容旧字段名
    val attr: Int get() = fileAttr

    fun toExtraString(): String {
        val safeName = fileName.replace(":", "::")
        return "${fileId.toString(16)}:$safeName:${size.toString(16)}:${mtime.toString(16)}:${fileAttr.toString(16)}"
    }

    companion object {
        fun parseList(fileExtra: String): List<FileAttachInfo> {
            if (fileExtra.isEmpty()) return emptyList()
            return fileExtra.split('\u0007') // \a
                .filter { it.isNotBlank() }
                .mapNotNull { entry ->
                    val protected = entry.replace("::", "\u0001")
                    val parts = protected.split(":")
                    if (parts.size < 5) return@mapNotNull null
                    val fid = parts[0].toIntOrNull(16) ?: parts[0].toIntOrNull() ?: return@mapNotNull null
                    val name = parts[1].replace("\u0001", ":")
                    val size = parts[2].toLongOrNull(16) ?: 0L
                    val mtime = parts[3].toLongOrNull(16) ?: 0L
                    val attr = parts[4].toIntOrNull(16) ?: IpMsgCommands.FileRegular
                    FileAttachInfo(fid, name, size, mtime, if (attr == 0) IpMsgCommands.FileRegular else attr)
                }
        }
    }
}
