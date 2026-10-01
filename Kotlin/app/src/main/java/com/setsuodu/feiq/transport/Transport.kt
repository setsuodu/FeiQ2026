package com.setsuodu.feiq.transport

import java.net.InetSocketAddress

/**
 * 传输层抽象。UDP / WebSocket 等实现此接口。
 */
interface Transport {
    val localPort: Int
    val supportsBroadcast: Boolean
    var onDataReceived: ((ByteArray, InetSocketAddress) -> Unit)?

    /** 连接状态变化（WS 重连会多次回调；UDP start 后为 true） */
    var onConnectionChanged: ((Boolean) -> Unit)?
        get() = null
        set(_) {}

    val isConnected: Boolean
        get() = true

    suspend fun start()
    suspend fun send(data: ByteArray, remote: InetSocketAddress)
    suspend fun broadcast(data: ByteArray, port: Int)
    fun close()
}
