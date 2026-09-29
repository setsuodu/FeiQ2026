package minifeiq.protocol

object IpMsgCommands {
    const val NoOperation = 0x00000000
    const val BrEntry = 0x00000001
    const val BrExit = 0x00000002
    const val AnsEntry = 0x00000003
    const val BrAbsence = 0x00000004

    const val SendMsg = 0x00000020
    const val RecvMsg = 0x00000021
    const val ReadMsg = 0x00000030
    const val DelMsg = 0x00000031

    const val GetFileData = 0x00000060
    const val ReleaseFiles = 0x00000061
    const val GetDirFiles = 0x00000062

    const val SendCheckOpt = 0x00000100
    const val SecretOpt = 0x00000200
    const val BroadcastOpt = 0x00000400
    const val MulticastOpt = 0x00000800
    const val FileAttachOpt = 0x00200000
    const val EncryptOpt = 0x00400000
    const val Utf8Opt = 0x00800000

    const val FileRegular = 0x00000001
    const val FileDir = 0x00000002
}
