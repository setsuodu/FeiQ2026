package minifeiq.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object WsFileFrame {
    val MAGIC = byteArrayOf('M'.code.toByte(), 'F'.code.toByte(), 'Q'.code.toByte(), 0x01)
    const val HEADER_FIXED = 4 + 8 + 4 + 8 + 8 + 2

    fun isFileFrame(data: ByteArray): Boolean =
        data.size >= HEADER_FIXED &&
            data[0] == MAGIC[0] && data[1] == MAGIC[1] &&
            data[2] == MAGIC[2] && data[3] == MAGIC[3]

    fun build(
        packetNo: Long, fileId: Int, offset: Long, totalSize: Long,
        fileName: String, payload: ByteArray, payloadOffset: Int = 0, payloadLen: Int = payload.size
    ): ByteArray {
        val nameBytes = fileName.toByteArray(StandardCharsets.UTF_8)
        require(nameBytes.size <= 0xFFFF)
        val buf = ByteBuffer.allocate(HEADER_FIXED + nameBytes.size + payloadLen)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC)
        buf.putLong(packetNo)
        buf.putInt(fileId)
        buf.putLong(offset)
        buf.putLong(totalSize)
        buf.putShort(nameBytes.size.toShort())
        buf.put(nameBytes)
        buf.put(payload, payloadOffset, payloadLen)
        return buf.array()
    }

    data class Parsed(
        val packetNo: Long,
        val fileId: Int,
        val offset: Long,
        val totalSize: Long,
        val fileName: String,
        val payload: ByteArray
    )

    fun parse(data: ByteArray): Parsed? {
        if (!isFileFrame(data)) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(4)
        val packetNo = buf.long
        val fileId = buf.int
        val offset = buf.long
        val totalSize = buf.long
        val nameLen = buf.short.toInt() and 0xFFFF
        if (buf.remaining() < nameLen) return null
        val nameBytes = ByteArray(nameLen)
        buf.get(nameBytes)
        val payload = ByteArray(buf.remaining())
        buf.get(payload)
        return Parsed(
            packetNo = packetNo,
            fileId = fileId,
            offset = offset,
            totalSize = totalSize,
            fileName = String(nameBytes, StandardCharsets.UTF_8),
            payload = payload
        )
    }
}
