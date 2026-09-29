package minifeiq.service

import kotlinx.coroutines.*
import minifeiq.protocol.*
import minifeiq.transport.Transport
import minifeiq.transport.UdpTransport
import minifeiq.transport.WebSocketTransport
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class Peer(
    val name: String,
    val hostName: String,
    val ip: InetAddress,
    var lastSeen: Long = System.currentTimeMillis()
) {
    override fun toString(): String = "$name (${ip.hostAddress})"
}

data class IncomingFileOffer(
    val from: Peer,
    val packetNo: Long,
    val info: FileAttachInfo,
    val message: String = ""
)

data class FileTransferProgress(
    val fileName: String,
    val received: Long,
    val total: Long,
    val done: Boolean = false,
    val savedPath: String? = null,
    val error: String? = null
)

data class SharedFile(
    val packetNo: Long,
    val fileId: Int,
    val path: String,
    val fileName: String,
    val size: Long
)

/**
 * IPMSG 服务：消息 + 文件。
 * UDP 模式 TCP:2425 拉文件；WS 模式 MFQ 分片。
 */
class IpMsgService(
    private val transport: Transport,
    userName: String? = null,
    hostName: String? = null
) {
    private val userName = userName ?: System.getProperty("user.name", "user")
    private val hostName = hostName ?: InetAddress.getLocalHost().hostName
    private val charset: Charset = Charset.forName("GBK")
    private val packetNo = AtomicLong(System.currentTimeMillis() / 1000)
    private val nextFileId = AtomicInteger(1)
    private val isWebSocket = transport is WebSocketTransport

    private val peers = ConcurrentHashMap<String, Peer>()
    private val shared = ConcurrentHashMap<String, SharedFile>()
    private val wsReceiving = ConcurrentHashMap<String, IncomingWsReceive>()

    private var tcpServer: ServerSocket? = null
    private var tcpJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var downloadDir: File = File(
        System.getProperty("user.home"),
        "Documents/MiniFeiQ"
    )

    var onPeerOnline: ((Peer) -> Unit)? = null
    var onPeerOffline: ((Peer) -> Unit)? = null
    var onMessage: ((Peer, String) -> Unit)? = null
    var onFileOffered: ((IncomingFileOffer) -> Unit)? = null
    var onFileProgress: ((FileTransferProgress) -> Unit)? = null

    suspend fun start() {
        transport.onDataReceived = { data, remote -> onDataReceived(data, remote) }
        transport.start()

        if (!isWebSocket && transport.localPort > 0) {
            startTcpServer(transport.localPort)
        }
        announceOnline()
    }

    private fun startTcpServer(port: Int) {
        val server = ServerSocket(port)
        tcpServer = server
        tcpJob = scope.launch {
            while (isActive) {
                try {
                    val client = server.accept()
                    launch { handleTcpClient(client) }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun handleTcpClient(client: Socket) {
        try {
            client.soTimeout = 30_000
            val input = client.getInputStream()
            val buf = ByteArray(4096)
            val ms = java.io.ByteArrayOutputStream()
            while (ms.size() < 8192) {
                val n = input.read(buf)
                if (n <= 0) break
                ms.write(buf, 0, n)
                if (ms.toByteArray().contains(0)) break
            }
            val text = String(ms.toByteArray(), charset)
            val pkt = IpMsgPacket.tryParse(text) ?: return
            if (pkt.basicCommand != IpMsgCommands.GetFileData) return

            val parts = pkt.extra.split(":")
            if (parts.size < 3) return
            val pNo = parts[0].toLongOrNull(16) ?: return
            val fId = parts[1].toIntOrNull(16) ?: return
            val offset = parts[2].toLongOrNull(16) ?: 0L

            val sf = shared["$pNo:$fId"] ?: return
            val file = File(sf.path)
            if (!file.exists()) return

            FileInputStream(file).use { fis ->
                if (offset > 0 && offset < file.length()) fis.skip(offset)
                val out = client.getOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = fis.read(chunk)
                    if (n <= 0) break
                    out.write(chunk, 0, n)
                }
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    suspend fun announceOnline() {
        val pkt = buildPacket(IpMsgCommands.BrEntry, userName)
        if (transport.supportsBroadcast) {
            transport.broadcast(pkt.toBytes(charset), transport.localPort)
        }
    }

    suspend fun announceOffline() {
        val pkt = buildPacket(IpMsgCommands.BrExit, userName)
        if (transport.supportsBroadcast) {
            transport.broadcast(pkt.toBytes(charset), transport.localPort)
        }
    }

    suspend fun sendText(targetIp: InetAddress, text: String, requireAck: Boolean = true) {
        val cmd = IpMsgCommands.SendMsg or (if (requireAck) IpMsgCommands.SendCheckOpt else 0)
        val pkt = buildPacket(cmd, text)
        transport.send(pkt.toBytes(charset), InetSocketAddress(targetIp, transport.localPort))
    }

    suspend fun sendFile(targetIp: InetAddress, filePath: String, message: String? = null) {
        val file = File(filePath)
        if (!file.exists()) error("文件不存在: $filePath")

        val fileId = nextFileId.incrementAndGet()
        val pNo = packetNo.incrementAndGet()
        val mtime = file.lastModified() / 1000

        val attach = FileAttachInfo(
            fileId = fileId,
            fileName = file.name,
            size = file.length(),
            mtime = mtime,
            fileAttr = IpMsgCommands.FileRegular
        )
        shared["$pNo:$fileId"] = SharedFile(pNo, fileId, file.absolutePath, file.name, file.length())

        val cmd = IpMsgCommands.SendMsg or IpMsgCommands.SendCheckOpt or IpMsgCommands.FileAttachOpt
        val pkt = IpMsgPacket(
            packetNo = pNo,
            userName = userName,
            hostName = hostName,
            command = cmd,
            extra = message ?: file.name,
            fileExtra = attach.toExtraString()
        )
        transport.send(pkt.toBytes(charset), InetSocketAddress(targetIp, transport.localPort))
        // WS：等对方 GETFILEDATA 再推
    }

    suspend fun acceptFile(offer: IncomingFileOffer, savePath: String? = null) {
        downloadDir.mkdirs()
        var path = savePath ?: File(downloadDir, sanitize(offer.info.fileName)).absolutePath
        path = ensureUnique(path)

        if (isWebSocket) {
            val key = "${offer.packetNo}:${offer.info.fileId}"
            wsReceiving[key] = IncomingWsReceive(
                fileName = offer.info.fileName,
                total = offer.info.size,
                savePath = path,
                stream = FileOutputStream(path)
            )
            val extra = "${offer.packetNo.toString(16)}:${offer.info.fileId.toString(16)}:0"
            val pkt = buildPacket(IpMsgCommands.GetFileData, extra)
            transport.send(pkt.toBytes(charset), InetSocketAddress("127.0.0.1", 0))
            return
        }

        downloadViaTcp(offer.from.ip, offer.packetNo, offer.info, path)
    }

    private suspend fun downloadViaTcp(ip: InetAddress, pNo: Long, info: FileAttachInfo, savePath: String) {
        withContext(Dispatchers.IO) {
            try {
                Socket(ip, transport.localPort).use { sock ->
                    sock.soTimeout = 60_000
                    val extra = "${pNo.toString(16)}:${info.fileId.toString(16)}:0"
                    val pkt = buildPacket(IpMsgCommands.GetFileData, extra)
                    val req = pkt.toBytes(charset)
                    sock.getOutputStream().write(req)
                    sock.getOutputStream().flush()

                    FileOutputStream(savePath).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        var received = 0L
                        val input = sock.getInputStream()
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            fos.write(buf, 0, n)
                            received += n
                            onFileProgress?.invoke(
                                FileTransferProgress(info.fileName, received, info.size, false)
                            )
                        }
                        onFileProgress?.invoke(
                            FileTransferProgress(info.fileName, received, info.size, true, savePath)
                        )
                    }
                }
            } catch (e: Exception) {
                onFileProgress?.invoke(
                    FileTransferProgress(info.fileName, 0, info.size, true, error = e.message)
                )
            }
        }
    }

    suspend fun refresh() {
        peers.clear()
        announceOnline()
    }

    private fun onDataReceived(buffer: ByteArray, remote: InetSocketAddress) {
        if (WsFileFrame.isFileFrame(buffer)) {
            handleWsFileFrame(buffer)
            return
        }

        val text = try {
            String(buffer, charset)
        } catch (_: Exception) {
            return
        }
        val pkt = IpMsgPacket.tryParse(text) ?: return

        if (!isWebSocket && isLocal(remote.address)) return

        when (pkt.basicCommand) {
            IpMsgCommands.BrEntry, IpMsgCommands.AnsEntry -> {
                handlePresence(pkt, remote.address, true)
                if (pkt.basicCommand == IpMsgCommands.BrEntry) {
                    scope.launch { replyAnsEntry(remote.address) }
                }
            }
            IpMsgCommands.BrExit -> handlePresence(pkt, remote.address, false)
            IpMsgCommands.SendMsg -> handleSendMsg(pkt, remote.address)
            IpMsgCommands.GetFileData -> {
                if (isWebSocket) {
                    scope.launch { handleWsGetFile(pkt) }
                }
            }
        }
    }

    private fun handleWsFileFrame(buffer: ByteArray) {
        val parsed = WsFileFrame.tryParse(buffer) ?: return
        val key = "${parsed.packetNo}:${parsed.fileId}"
        val recv = wsReceiving[key] ?: return // 未接受则丢弃

        try {
            if (parsed.payload.isNotEmpty()) {
                recv.stream.write(parsed.payload)
                recv.received += parsed.payload.size
            }
            val done = recv.received >= parsed.totalSize ||
                (parsed.totalSize > 0 && parsed.offset + parsed.payload.size >= parsed.totalSize)
            if (done) {
                recv.stream.close()
                wsReceiving.remove(key)
                onFileProgress?.invoke(
                    FileTransferProgress(recv.fileName, recv.received, parsed.totalSize, true, recv.savePath)
                )
            } else {
                onFileProgress?.invoke(
                    FileTransferProgress(recv.fileName, recv.received, parsed.totalSize, false)
                )
            }
        } catch (e: Exception) {
            try { recv.stream.close() } catch (_: Exception) {}
            wsReceiving.remove(key)
            onFileProgress?.invoke(
                FileTransferProgress(parsed.fileName, recv.received, parsed.totalSize, true, error = e.message)
            )
        }
    }

    private suspend fun handleWsGetFile(pkt: IpMsgPacket) {
        val parts = pkt.extra.split(":")
        if (parts.size < 2) return
        val pNo = parts[0].toLongOrNull(16) ?: return
        val fId = parts[1].toIntOrNull(16) ?: return
        val sf = shared["$pNo:$fId"] ?: return
        pushFileOverWs(sf)
    }

    private suspend fun pushFileOverWs(sf: SharedFile) {
        withContext(Dispatchers.IO) {
            try {
                val chunkSize = 48 * 1024
                FileInputStream(sf.path).use { fis ->
                    val buf = ByteArray(chunkSize)
                    var offset = 0L
                    while (true) {
                        val n = fis.read(buf)
                        if (n <= 0) break
                        val frame = WsFileFrame.build(
                            sf.packetNo, sf.fileId, offset, sf.size, sf.fileName, buf, 0, n
                        )
                        transport.send(frame, InetSocketAddress("127.0.0.1", 0))
                        offset += n
                        onFileProgress?.invoke(
                            FileTransferProgress(sf.fileName, offset, sf.size, offset >= sf.size)
                        )
                    }
                }
            } catch (e: Exception) {
                onFileProgress?.invoke(
                    FileTransferProgress(sf.fileName, 0, sf.size, true, error = e.message)
                )
            }
        }
    }

    private fun handlePresence(pkt: IpMsgPacket, ip: InetAddress, isEntry: Boolean) {
        val key = if (isWebSocket) "ws:${pkt.userName}@${pkt.hostName}" else ip.hostAddress
        if (isEntry) {
            val name = pkt.extra.ifBlank { pkt.userName }
            val existing = peers[key]
            if (existing != null) {
                existing.lastSeen = System.currentTimeMillis()
                return
            }
            val peer = Peer(name, pkt.hostName, ip)
            peers[key] = peer
            onPeerOnline?.invoke(peer)
        } else {
            val peer = peers.remove(key) ?: return
            onPeerOffline?.invoke(peer)
        }
    }

    private fun handleSendMsg(pkt: IpMsgPacket, ip: InetAddress) {
        if (pkt.hasOption(IpMsgCommands.SendCheckOpt)) {
            scope.launch { replyRecvMsg(ip, pkt.packetNo) }
        }
        val peer = ensurePeer(pkt, ip)

        if (pkt.hasOption(IpMsgCommands.FileAttachOpt) && pkt.fileExtra.isNotEmpty()) {
            val files = FileAttachInfo.parseList(pkt.fileExtra)
            for (f in files) {
                onFileOffered?.invoke(IncomingFileOffer(peer, pkt.packetNo, f, pkt.extra))
            }
            if (pkt.extra.isNotBlank() && pkt.extra != files.firstOrNull()?.fileName) {
                onMessage?.invoke(peer, pkt.extra)
            }
            return
        }
        onMessage?.invoke(peer, pkt.extra)
    }

    private fun ensurePeer(pkt: IpMsgPacket, ip: InetAddress): Peer {
        val key = if (isWebSocket) "ws:${pkt.userName}@${pkt.hostName}" else ip.hostAddress
        return peers.getOrPut(key) {
            Peer(pkt.userName, pkt.hostName, ip).also { onPeerOnline?.invoke(it) }
        }
    }

    private suspend fun replyAnsEntry(ip: InetAddress) {
        try {
            val pkt = buildPacket(IpMsgCommands.AnsEntry, userName)
            transport.send(pkt.toBytes(charset), InetSocketAddress(ip, transport.localPort))
        } catch (_: Exception) {
        }
    }

    private suspend fun replyRecvMsg(ip: InetAddress, no: Long) {
        try {
            val pkt = buildPacket(IpMsgCommands.RecvMsg, no.toString())
            transport.send(pkt.toBytes(charset), InetSocketAddress(ip, transport.localPort))
        } catch (_: Exception) {
        }
    }

    private fun buildPacket(command: Int, extra: String) = IpMsgPacket(
        packetNo = packetNo.incrementAndGet(),
        userName = userName,
        hostName = hostName,
        command = command,
        extra = extra
    )

    private fun isLocal(ip: InetAddress): Boolean {
        if (ip.isLoopbackAddress) return true
        return try {
            InetAddress.getAllByName(InetAddress.getLocalHost().hostName).any { it == ip }
        } catch (_: Exception) {
            false
        }
    }

    private fun sanitize(name: String): String {
        val bad = Regex("""[\\/:*?"<>|]""")
        val n = name.replace(bad, "_")
        return n.ifBlank { "file.bin" }
    }

    private fun ensureUnique(path: String): String {
        val f = File(path)
        if (!f.exists()) return path
        val dir = f.parentFile
        val base = f.nameWithoutExtension
        val ext = f.extension.let { if (it.isEmpty()) "" else ".$it" }
        for (i in 1..999) {
            val c = File(dir, "$base($i)$ext")
            if (!c.exists()) return c.absolutePath
        }
        return File(dir, "${base}_${System.nanoTime()}$ext").absolutePath
    }

    fun stop() {
        scope.launch {
            try { announceOffline() } catch (_: Exception) {}
        }
        try { tcpServer?.close() } catch (_: Exception) {}
        tcpJob?.cancel()
        wsReceiving.values.forEach { try { it.stream.close() } catch (_: Exception) {} }
        wsReceiving.clear()
        transport.onDataReceived = null
        transport.close()
        scope.cancel()
    }

    private class IncomingWsReceive(
        val fileName: String,
        val total: Long,
        val savePath: String,
        val stream: FileOutputStream,
        var received: Long = 0
    )
}
