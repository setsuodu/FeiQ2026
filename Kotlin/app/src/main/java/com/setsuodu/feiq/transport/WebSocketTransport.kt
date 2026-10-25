package com.setsuodu.feiq.transport

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.util.concurrent.atomic.AtomicReference

/**
 * WebSocket 中继传输（OkHttp）。
 * - 稳定 clientId 由外部传入（对应服务端离线队列）
 * - 断线自动重连；仅「当前 socket」的关闭回调才会把 open 置 false，避免旧连接回调把新连接打成断开
 * - 全局互斥建连，禁止并发 newWebSocket（同 clientId 会被服务端踢旧连接，旧回调误清 open 会形成 2s 翻转）
 */
class WebSocketTransport(
    private val serverUrl: String,
    private val clientId: String = "android"
) : Transport {
    override val localPort: Int = 0
    override val supportsBroadcast: Boolean = true
    override var onDataReceived: ((ByteArray, InetSocketAddress) -> Unit)? = null
    override var onConnectionChanged: ((Boolean) -> Unit)? = null

    private val currentWs = AtomicReference<WebSocket?>(null)
    private val open = AtomicBoolean(false)
    private val intentionalClose = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private val connectMutex = Mutex()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val syntheticRemote = InetSocketAddress("0.0.0.0", 0)

    override val isConnected: Boolean
        get() = open.get() && currentWs.get() != null

    override suspend fun start() {
        intentionalClose.set(false)
        connectInternal(timeoutMs = 15_000)
        // 连接成功与否由 onOpen / markClosed 通知，这里不再无脑 invoke(true)
        startReconnectLoop()
    }

    private fun startReconnectLoop() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var backoff = 1_000L
            while (isActive && !intentionalClose.get()) {
                delay(2_000)
                if (intentionalClose.get()) break
                if (open.get() && currentWs.get() != null) {
                    backoff = 1_000L
                    continue
                }
                try {
                    connectInternal(timeoutMs = 12_000)
                    if (open.get()) {
                        backoff = 1_000L
                    }
                } catch (_: Exception) {
                    if (!intentionalClose.get() && !open.get()) {
                        onConnectionChanged?.invoke(false)
                    }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    /**
     * 仅当回调来自「当前持有的 socket」时才标记断开，忽略已被替换的旧连接回调。
     */
    private fun markClosed(ws: WebSocket, notify: Boolean) {
        // CAS：只有仍是当前 ws 才清状态
        if (!currentWs.compareAndSet(ws, null)) return
        open.set(false)
        if (notify && !intentionalClose.get()) {
            onConnectionChanged?.invoke(false)
        }
    }

    private suspend fun connectInternal(timeoutMs: Long) {
        if (intentionalClose.get()) return

        connectMutex.withLock {
            if (intentionalClose.get()) return
            if (open.get() && currentWs.get() != null) return

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
                    currentWs.set(webSocket)
                    open.set(true)
                    if (!latch.isCompleted) latch.complete(Unit)
                    if (!intentionalClose.get()) {
                        onConnectionChanged?.invoke(true)
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (currentWs.get() !== webSocket) return
                    onDataReceived?.invoke(bytes.toByteArray(), syntheticRemote)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (currentWs.get() !== webSocket) return
                    onDataReceived?.invoke(text.toByteArray(Charsets.UTF_8), syntheticRemote)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    try {
                        webSocket.close(1000, null)
                    } catch (_: Exception) {
                    }
                    markClosed(webSocket, notify = true)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    markClosed(webSocket, notify = true)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!latch.isCompleted) {
                        latch.completeExceptionally(t)
                    }
                    markClosed(webSocket, notify = true)
                }
            }

            // 关掉上一根（若有），再挂新的；全程在 mutex 内，禁止并行建连
            val prev = currentWs.getAndSet(null)
            open.set(false)
            try {
                prev?.cancel()
            } catch (_: Exception) {
            }

            val ws = client.newWebSocket(request, listener)
            // 先挂上引用，onOpen 再确认 open；失败时 onFailure 会 markClosed
            currentWs.compareAndSet(null, ws)

            try {
                withTimeout(timeoutMs) {
                    latch.await()
                }
            } catch (e: Exception) {
                try {
                    ws.cancel()
                } catch (_: Exception) {
                }
                currentWs.compareAndSet(ws, null)
                open.set(false)
                throw e
            }
            if (!open.get() || currentWs.get() == null) {
                error("WebSocket 连接超时: $serverUrl")
            }
        }
    }

    override suspend fun send(data: ByteArray, remote: InetSocketAddress) {
        sendRaw(data)
    }

    override suspend fun broadcast(data: ByteArray, port: Int) {
        sendRaw(data)
    }

    private fun sendRaw(data: ByteArray) {
        // 发送路径不再自行建连，避免与重连 loop 并发 newWebSocket
        val ws = currentWs.get()
        if (ws == null || !open.get()) {
            error("WebSocket not open（等待自动重连）")
        }

        var attempt = 0
        while (true) {
            if (currentWs.get() !== ws || !open.get()) {
                error("WebSocket not open")
            }
            val ok = try {
                ws.send(data.toByteString())
            } catch (e: Exception) {
                markClosed(ws, notify = true)
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
        }
    }

    override fun close() {
        intentionalClose.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
        open.set(false)
        val ws = currentWs.getAndSet(null)
        try {
            ws?.close(1000, "bye")
        } catch (_: Exception) {
        }
        scope.cancel()
    }
}
