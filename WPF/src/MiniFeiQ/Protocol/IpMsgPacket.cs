using System.Text;

namespace MiniFeiQ.Protocol;

/// <summary>
/// IPMSG 报文：版本:包序号:用户名:主机名:命令字:附加数据
/// </summary>
public sealed class IpMsgPacket
{
    public string Version { get; init; } = "1";
    public long PacketNo { get; init; }
    public string UserName { get; init; } = "";
    public string HostName { get; init; } = "";
    public int Command { get; init; }
    public string Extra { get; init; } = "";

    public int BasicCommand => Command & 0xFF;
    public bool HasOption(int opt) => (Command & opt) != 0;

    public static IpMsgPacket? TryParse(string text)
    {
        // 最多拆成6段，附加数据里可能含冒号
        var parts = text.Split(':', 6);
        if (parts.Length < 6) return null;
        if (!long.TryParse(parts[1], out var no)) return null;
        if (!int.TryParse(parts[4], out var cmd)) return null;

        // 附加数据去掉可能的 \0 后缀
        var extra = parts[5].Split('\0')[0];

        return new IpMsgPacket
        {
            Version = parts[0],
            PacketNo = no,
            UserName = parts[2],
            HostName = parts[3],
            Command = cmd,
            Extra = extra
        };
    }

    public byte[] ToBytes(Encoding encoding)
    {
        // 附加数据末尾加 \0 是飞秋常见写法
        var body = $"{Version}:{PacketNo}:{UserName}:{HostName}:{Command}:{Extra}\0";
        return encoding.GetBytes(body);
    }

    public override string ToString() =>
        $"{Version}:{PacketNo}:{UserName}:{HostName}:{Command}:{Extra}";
}
