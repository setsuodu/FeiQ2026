package minifeiq.transport

import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException

class UdpTransport(private val port: Int = 2425) : Transport {
    override val localPort: Int get() = port
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
            bind(InetSocketAddress(port))
        }
        socket = s
        recvJob = scope.launch {
            val buf = ByteArray(64 * 1024)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    s.receive(packet)
                    val data = packet.data.copyOf(packet.length)
                    val remote = InetSocketAddress(packet.address, packet.port)
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
        withContext(Dispatchers.IO) {
            val packet = DatagramPacket(data, data.size, remote.address, if (remote.port > 0) remote.port else port)
            s.send(packet)
        }
    }

    override suspend fun broadcast(data: ByteArray, port: Int) {
        val s = socket ?: error("Transport not started")
        withContext(Dispatchers.IO) {
            val packet = DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), port)
            s.send(packet)
        }
    }

    override fun close() {
        recvJob?.cancel()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        scope.cancel()
    }
}
