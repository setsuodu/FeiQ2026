using System.Buffers.Binary;
using System.Text;

namespace FeiQ2026.Protocol;

/// <summary>
/// WebSocket 中继模式下的文件分片帧（因无法走 TCP 2425）。
/// 布局（小端）：
///   magic[4] = 'M','F','Q',0x01
///   packetNo[8]
///   fileId[4]
///   offset[8]
///   totalSize[8]
///   nameLen[2] + nameUtf8[nameLen]
///   payload[...]
/// </summary>
public static class WsFileFrame
{
    public static readonly byte[] Magic = [(byte)'M', (byte)'F', (byte)'Q', 0x01];
    public const int HeaderFixed = 4 + 8 + 4 + 8 + 8 + 2;

    public static bool IsFileFrame(byte[] data) =>
        data.Length >= HeaderFixed &&
        data[0] == Magic[0] && data[1] == Magic[1] &&
        data[2] == Magic[2] && data[3] == Magic[3];

    public static byte[] Build(long packetNo, int fileId, long offset, long totalSize,
        string fileName, ReadOnlySpan<byte> payload)
    {
        var nameBytes = Encoding.UTF8.GetBytes(fileName);
        if (nameBytes.Length > ushort.MaxValue)
            throw new ArgumentException("文件名过长");

        var buf = new byte[HeaderFixed + nameBytes.Length + payload.Length];
        var span = buf.AsSpan();
        Magic.CopyTo(span);
        BinaryPrimitives.WriteInt64LittleEndian(span[4..], packetNo);
        BinaryPrimitives.WriteInt32LittleEndian(span[12..], fileId);
        BinaryPrimitives.WriteInt64LittleEndian(span[16..], offset);
        BinaryPrimitives.WriteInt64LittleEndian(span[24..], totalSize);
        BinaryPrimitives.WriteUInt16LittleEndian(span[32..], (ushort)nameBytes.Length);
        nameBytes.CopyTo(span[34..]);
        payload.CopyTo(span[(34 + nameBytes.Length)..]);
        return buf;
    }

    public static bool TryParse(byte[] data,
        out long packetNo, out int fileId, out long offset, out long totalSize,
        out string fileName, out ReadOnlyMemory<byte> payload)
    {
        packetNo = 0; fileId = 0; offset = 0; totalSize = 0;
        fileName = ""; payload = default;
        if (!IsFileFrame(data)) return false;

        var span = data.AsSpan();
        packetNo = BinaryPrimitives.ReadInt64LittleEndian(span[4..]);
        fileId = BinaryPrimitives.ReadInt32LittleEndian(span[12..]);
        offset = BinaryPrimitives.ReadInt64LittleEndian(span[16..]);
        totalSize = BinaryPrimitives.ReadInt64LittleEndian(span[24..]);
        var nameLen = BinaryPrimitives.ReadUInt16LittleEndian(span[32..]);
        if (34 + nameLen > data.Length) return false;
        fileName = Encoding.UTF8.GetString(span.Slice(34, nameLen));
        payload = data.AsMemory(34 + nameLen);
        return true;
    }
}
