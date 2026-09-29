using System.Text;

namespace MiniFeiQ.Protocol;

/// <summary>
/// IPMSG 报文：版本:包序号:用户名:主机名:命令字:附加数据
/// 附加数据中消息正文与文件列表用 \0 分隔（FILEATTACHOPT 时）。
/// </summary>
public sealed class IpMsgPacket
{
    public string Version { get; init; } = "1";
    public long PacketNo { get; init; }
    public string UserName { get; init; } = "";
    public string HostName { get; init; } = "";
    public int Command { get; init; }

    /// <summary>消息正文（\0 前半段）</summary>
    public string Extra { get; init; } = "";

    /// <summary>文件附件描述（\0 后半段，可能含 \a 分隔的多文件）</summary>
    public string FileExtra { get; init; } = "";

    public int BasicCommand => Command & 0xFF;
    public bool HasOption(int opt) => (Command & opt) != 0;

    public static IpMsgPacket? TryParse(string text)
    {
        var parts = text.Split(':', 6);
        if (parts.Length < 6) return null;
        if (!long.TryParse(parts[1], out var no)) return null;
        if (!int.TryParse(parts[4], out var cmd)) return null;

        var raw = parts[5];
        string extra;
        string fileExtra = "";
        var nullIdx = raw.IndexOf('\0');
        if (nullIdx >= 0)
        {
            extra = raw[..nullIdx];
            fileExtra = raw[(nullIdx + 1)..].TrimEnd('\0');
        }
        else
        {
            extra = raw.TrimEnd('\0');
        }

        return new IpMsgPacket
        {
            Version = parts[0],
            PacketNo = no,
            UserName = parts[2],
            HostName = parts[3],
            Command = cmd,
            Extra = extra,
            FileExtra = fileExtra
        };
    }

    public byte[] ToBytes(Encoding encoding)
    {
        string body;
        if (!string.IsNullOrEmpty(FileExtra))
            body = $"{Version}:{PacketNo}:{UserName}:{HostName}:{Command}:{Extra}\0{FileExtra}\0";
        else
            body = $"{Version}:{PacketNo}:{UserName}:{HostName}:{Command}:{Extra}\0";
        return encoding.GetBytes(body);
    }

    public override string ToString() =>
        $"{Version}:{PacketNo}:{UserName}:{HostName}:{Command}:{Extra}";
}

/// <summary>解析后的单个附件描述</summary>
public sealed class FileAttachInfo
{
    public int FileId { get; init; }
    public string FileName { get; init; } = "";
    public long Size { get; init; }
    public long Mtime { get; init; }
    public int FileAttr { get; init; } = IpMsgCommands.FileRegular;

    /// <summary>
    /// 格式：fileID:filename:size:mtime:fileattr （size/mtime/attr 为 hex）
    /// 多文件用 \a 分隔。
    /// </summary>
    public static List<FileAttachInfo> ParseList(string fileExtra)
    {
        var list = new List<FileAttachInfo>();
        if (string.IsNullOrEmpty(fileExtra)) return list;

        foreach (var entry in fileExtra.Split('\a', StringSplitOptions.RemoveEmptyEntries))
        {
            var protected_ = entry.Replace("::", "\x01");
            var parts = protected_.Split(':');
            if (parts.Length < 5) continue;

            if (!int.TryParse(parts[0], System.Globalization.NumberStyles.HexNumber, null, out var fid) &&
                !int.TryParse(parts[0], out fid))
                continue;

            var name = parts[1].Replace("\x01", ":");
            long.TryParse(parts[2], System.Globalization.NumberStyles.HexNumber, null, out var size);
            long.TryParse(parts[3], System.Globalization.NumberStyles.HexNumber, null, out var mtime);
            int.TryParse(parts[4], System.Globalization.NumberStyles.HexNumber, null, out var attr);

            list.Add(new FileAttachInfo
            {
                FileId = fid,
                FileName = name,
                Size = size,
                Mtime = mtime,
                FileAttr = attr == 0 ? IpMsgCommands.FileRegular : attr
            });
        }
        return list;
    }

    public string ToExtraString()
    {
        var safeName = FileName.Replace(":", "::");
        return $"{FileId:x}:{safeName}:{Size:x}:{Mtime:x}:{FileAttr:x}";
    }
}
