package minifeiq.transport

import java.net.InetSocketAddress

interface Transport : AutoCloseable {
    val localPort: Int
    val supportsBroadcast: Boolean

    /** payload + remote endpoint (may be synthetic for WebSocket) */
    var onDataReceived: ((ByteArray, InetSocketAddress) -> Unit)?

    suspend fun start()
    suspend fun send(data: ByteArray, remote: InetSocketAddress)
    suspend fun broadcast(data: ByteArray, port: Int)
}
