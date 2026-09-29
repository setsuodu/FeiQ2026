namespace MiniFeiQ.Protocol;

/// <summary>
/// IPMSG / 飞秋2013 命令字（低8位为基本命令，高位为选项）
/// </summary>
public static class IpMsgCommands
{
    public const int NoOperation = 0x00000000;
    public const int BrEntry     = 0x00000001; // 上线广播
    public const int BrExit      = 0x00000002; // 下线广播
    public const int AnsEntry    = 0x00000003; // 应答上线
    public const int BrAbsence   = 0x00000004;

    public const int SendMsg     = 0x00000020; // 发送消息
    public const int RecvMsg     = 0x00000021; // 消息确认
    public const int ReadMsg     = 0x00000030;
    public const int DelMsg      = 0x00000031;

    // 选项位
    public const int SendCheckOpt   = 0x00000100; // 要求回执
    public const int SecretOpt      = 0x00000200;
    public const int BroadcastOpt   = 0x00000400;
    public const int MulticastOpt   = 0x00000800;
    public const int FileAttachOpt  = 0x00200000; // 文件附件
    public const int EncryptOpt     = 0x00400000;
    public const int Utf8Opt        = 0x00800000;
}
