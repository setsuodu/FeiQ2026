package minifeiq.transport

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket 中继传输（OkHttp，Android 兼容）。
 * 所有收发经服务器转发。
 */
class WebSocketTransport(
    private val serverUrl: String,
    private val clientId: String = "android"
) : Transport {
    override val localPort: Int = 0
    override val supportsBroadcast: Boolean = true
    override var onDataReceived: ((ByteArray, InetSocketAddress) -> Unit)? = null

    private var webSocket: WebSocket? = null
    private val open = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    // 合成远端地址，供协议层使用
    private val syntheticRemote = InetSocketAddress("0.0.0.0", 0)

    override suspend fun start() {
        if (open.get()) return
        val url = if (serverUrl.contains("?")) {
            "$serverUrl&clientId=$clientId"
        } else {
            "$serverUrl?clientId=$clientId"
        }
        val request = Request.Builder()
            .url(url)
            .header("X-Client-Id", clientId)
            .build()

        val latch = CompletableDeferred<Unit>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open.set(true)
                latch.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onDataReceived?.invoke(bytes.toByteArray(), syntheticRemote)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onDataReceived?.invoke(text.toByteArray(Charsets.UTF_8), syntheticRemote)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                open.set(false)
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open.set(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open.set(false)
                if (!latch.isCompleted) {
                    latch.completeExceptionally(t)
                }
            }
        }

        webSocket = client.newWebSocket(request, listener)

        withTimeout(15_000) {
            latch.await()
        }
        if (!open.get()) error("WebSocket 连接超时: $serverUrl")
    }

    override suspend fun send(data: ByteArray, remote: InetSocketAddress) {
        sendRaw(data)
    }

    override suspend fun broadcast(data: ByteArray, port: Int) {
        sendRaw(data)
    }

    private fun sendRaw(data: ByteArray) {
        val ws = webSocket ?: error("WebSocket not connected")
        if (!open.get()) error("WebSocket not open")
        ws.send(data.toByteString())
    }

    override fun close() {
        open.set(false)
        try {
            webSocket?.close(1000, "bye")
        } catch (_: Exception) {
        }
        webSocket = null
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }
}
