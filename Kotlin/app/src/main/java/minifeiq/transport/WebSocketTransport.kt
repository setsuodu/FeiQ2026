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
 * WebSocket 中继传输（OkHttp）。
 * - 稳定 clientId 由外部传入（对应服务端离线队列）
 * - 断线自动重连，连上后由 onConnectionChanged(true) 通知上层补发/重新上线
 */
class WebSocketTransport(
    private val serverUrl: String,
    private val clientId: String = "android"
) : Transport {
    override val localPort: Int = 0
    override val supportsBroadcast: Boolean = true
    override var onDataReceived: ((ByteArray, InetSocketAddress) -> Unit)? = null
    override var onConnectionChanged: ((Boolean) -> Unit)? = null

    private var webSocket: WebSocket? = null
    private val open = AtomicBoolean(false)
    private val intentionalClose = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val syntheticRemote = InetSocketAddress("0.0.0.0", 0)
    private val connectLock = Any()

    override val isConnected: Boolean
        get() = open.get() && webSocket != null

    override suspend fun start() {
        intentionalClose.set(false)
        connectInternal(timeoutMs = 15_000)
        onConnectionChanged?.invoke(true)
        startReconnectLoop()
    }

    private fun startReconnectLoop() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var backoff = 1_000L
            while (isActive && !intentionalClose.get()) {
                delay(2_000)
                if (intentionalClose.get()) break
                if (open.get() && webSocket != null) {
                    backoff = 1_000L
                    continue
                }
                // 已断开 → 尝试重连
                try {
                    connectInternal(timeoutMs = 12_000)
                    if (open.get()) {
                        onConnectionChanged?.invoke(true)
                        backoff = 1_000L
                    }
                } catch (_: Exception) {
                    onConnectionChanged?.invoke(false)
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private suspend fun connectInternal(timeoutMs: Long) {
        if (intentionalClose.get()) return
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
                if (!intentionalClose.get()) {
                    onConnectionChanged?.invoke(false)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open.set(false)
                if (!intentionalClose.get()) {
                    onConnectionChanged?.invoke(false)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open.set(false)
                if (!latch.isCompleted) {
                    latch.completeExceptionally(t)
                }
                if (!intentionalClose.get()) {
                    onConnectionChanged?.invoke(false)
                }
            }
        }

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
        intentionalClose.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
        open.set(false)
        try {
            webSocket?.close(1000, "bye")
        } catch (_: Exception) {
        }
        webSocket = null
        scope.cancel()
    }
}
