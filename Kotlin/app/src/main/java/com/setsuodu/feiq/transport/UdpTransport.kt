package com.setsuodu.feiq.transport

import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException

/** 飞秋兼容 UDP，默认端口 2425。localPort 在 bind 成功后为真实端口，失败前也不会是 -1。 */
class UdpTransport(private val bindPort: Int = 2425) : Transport {
    @Volatile
    private var boundPort: Int = bindPort

    override val localPort: Int
        get() = if (boundPort in 1..65535) boundPort else 2425

    override val supportsBroadcast: Boolean = true
    override var onDataReceived: ((ByteArray, InetSocketAddress) -> Unit)? = null

    private var socket: DatagramSocket? = null
    private var recvJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override suspend fun start() {
        if (socket != null) return
        val s = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(bindPort))
        }
        // bind 后取真实本地端口；个别实现未 bind 时 localPort=-1，必须兜底
        val real = s.localPort
        boundPort = if (real in 1..65535) real else bindPort
        socket = s
        recvJob = scope.launch {
            val buf = ByteArray(64 * 1024)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    s.receive(packet)
                    val data = packet.data.copyOf(packet.length)
                    val rPort = packet.port
                    val port = if (rPort in 1..65535) rPort else boundPort
                    val remote = InetSocketAddress(packet.address, port)
                    onDataReceived?.invoke(data, remote)
                } catch (_: SocketException) {
                    break
                } catch (_: CancellationException) {
                    break
                } catch (_: Exception) {
                    // continue
                }
            }
        }
    }

    override suspend fun send(data: ByteArray, remote: InetSocketAddress) {
        val s = socket ?: error("Transport not started")
        val destPort = when {
            remote.port in 1..65535 -> remote.port
            else -> localPort
        }
        withContext(Dispatchers.IO) {
            val packet = DatagramPacket(data, data.size, remote.address, destPort)
            s.send(packet)
        }
    }

    override suspend fun broadcast(data: ByteArray, port: Int) {
        val s = socket ?: error("Transport not started")
        val destPort = if (port in 1..65535) port else localPort
        withContext(Dispatchers.IO) {
            val packet = DatagramPacket(
                data, data.size,
                InetAddress.getByName("255.255.255.255"),
                destPort
            )
            s.send(packet)
        }
    }

    override fun close() {
        recvJob?.cancel()
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        boundPort = bindPort
        scope.cancel()
    }
}
