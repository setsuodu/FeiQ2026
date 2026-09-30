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
 * 所有收发经服务器转发。支持断线自动重连。
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
        .retryOnConnectionFailure(true)
        .build()

    private val syntheticRemote = InetSocketAddress("0.0.0.0", 0)
    private val connectLock = Any()

    override suspend fun start() {
        connectInternal(timeoutMs = 15_000)
    }

    private suspend fun connectInternal(timeoutMs: Long) {
        if (open.get() && webSocket != null) return
        synchronized(connectLock) {
            if (open.get() && webSocket != null) return
        }

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
                if (!latch.isCompleted) latch.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onDataReceived?.invoke(bytes.toByteArray(), syntheticRemote)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onDataReceived?.invoke(text.toByteArray(Charsets.UTF_8), syntheticRemote)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                open.set(false)
                try { webSocket.close(1000, null) } catch (_: Exception) { }
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

        // 关掉旧连接
        try { webSocket?.cancel() } catch (_: Exception) { }
        webSocket = client.newWebSocket(request, listener)

        withTimeout(timeoutMs) {
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
        if (!open.get() || webSocket == null) {
            try {
                runBlocking {
                    withTimeout(8_000) { connectInternal(8_000) }
                }
            } catch (e: Exception) {
                error("WebSocket not open（重连失败: ${e.message}）")
            }
        }
        val ws = webSocket ?: error("WebSocket not connected")
        if (!open.get()) error("WebSocket not open")

        // OkHttp 队列满时 send 返回 false；大文件需重试等待，勿立刻当断线
        var attempt = 0
        while (true) {
            val ok = try {
                ws.send(data.toByteString())
            } catch (e: Exception) {
                open.set(false)
                error("WebSocket send failed: ${e.message}")
            }
            if (ok) return
            attempt++
            if (attempt > 80) {
                error("WebSocket send failed（发送队列持续拥塞）")
            }
            try {
                Thread.sleep(if (attempt < 10) 30L else 80L)
            } catch (_: InterruptedException) {
                error("WebSocket send interrupted")
            }
            if (!open.get()) error("WebSocket not open")
        }
    }

    override fun close() {
        open.set(false)
        try {
            webSocket?.close(1000, "bye")
        } catch (_: Exception) {
        }
        webSocket = null
        scope.cancel()
        // 不要 shutdown 共享 client 的 dispatcher，避免重连后无法用
    }
}
