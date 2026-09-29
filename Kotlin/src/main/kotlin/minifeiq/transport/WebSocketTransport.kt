package minifeiq.transport

import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket 中继传输。所有收发经服务器转发。
 * 握手带 X-Client-Id 头（Java HttpClient 不支持自定义头在 WS 握手时，改用 query）。
 */
class WebSocketTransport(
    private val serverUrl: String,
    private val clientId: String = System.getProperty("user.name", "kotlin")
) : Transport {
    override val localPort: Int = 0
    override val supportsBroadcast: Boolean = true
    override var onDataReceived: ((ByteArray, InetSocketAddress) -> Unit)? = null

    private var webSocket: WebSocket? = null
    private val open = AtomicBoolean(false)
    private val sendLock = Any()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fakeEp = InetSocketAddress("127.0.0.1", 0)

    private val assemble = ByteArrayOutputStream()
    private var lastType: WebSocket.MessageType? = null

    override suspend fun start() {
        if (webSocket != null) return

        // Java HttpClient WebSocket 不方便加自定义头，把 clientId 放 query
        val base = serverUrl.trimEnd('/')
        val uri = if (base.contains("?")) {
            URI.create("$base&clientId=$clientId")
        } else {
            URI.create("$base?clientId=$clientId")
        }

        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

        val listener = object : WebSocket.Listener {
            override fun onOpen(webSocket: WebSocket) {
                open.set(true)
                webSocket.request(1)
            }

            override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*> {
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                assemble.write(bytes)
                if (last) {
                    val frame = assemble.toByteArray()
                    assemble.reset()
                    if (frame.isNotEmpty()) {
                        onDataReceived?.invoke(frame, fakeEp)
                    }
                }
                webSocket.request(1)
                return CompletableFuture.completedFuture(null)
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                // 中继应发 binary；兼容 text
                if (last) {
                    val frame = data.toString().toByteArray(Charsets.ISO_8859_1)
                    if (frame.isNotEmpty()) onDataReceived?.invoke(frame, fakeEp)
                }
                webSocket.request(1)
                return CompletableFuture.completedFuture(null)
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*> {
                open.set(false)
                return CompletableFuture.completedFuture(null)
            }

            override fun onError(webSocket: WebSocket, error: Throwable?) {
                open.set(false)
            }
        }

        val ws = client.newWebSocketBuilder()
            .header("X-Client-Id", clientId)
            .buildAsync(uri, listener)
            .join()
        webSocket = ws
        // 等 onOpen
        var waits = 0
        while (!open.get() && waits < 50) {
            delay(100)
            waits++
        }
        if (!open.get()) error("WebSocket 连接超时: $serverUrl")
    }

    override suspend fun send(data: ByteArray, remote: InetSocketAddress) {
        sendRaw(data)
    }

    override suspend fun broadcast(data: ByteArray, port: Int) {
        sendRaw(data)
    }

    private suspend fun sendRaw(data: ByteArray) {
        val ws = webSocket ?: error("WebSocket not connected")
        if (!open.get()) error("WebSocket not open")
        withContext(Dispatchers.IO) {
            synchronized(sendLock) {
                ws.sendBinary(ByteBuffer.wrap(data), true).join()
            }
        }
    }

    override fun close() {
        open.set(false)
        try {
            webSocket?.sendClose(WebSocket.NORMAL_CLOSURE, "bye")?.join()
        } catch (_: Exception) {
        }
        webSocket = null
        scope.cancel()
    }
}
