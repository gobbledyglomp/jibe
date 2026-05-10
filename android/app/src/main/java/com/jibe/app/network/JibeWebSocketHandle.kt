package com.jibe.app.network

import kotlinx.coroutines.flow.SharedFlow

/**
 * Narrow surface used by [com.jibe.app.data.repository.ConnectionRepository] so tests can supply a
 * fake transport without mocking constructors.
 */
interface JibeWebSocketHandle {
    val events: SharedFlow<WebSocketEvent>

    fun connect(host: String, port: Int)

    fun send(json: String): Boolean

    /** Send a binary WebSocket frame (file chunks use compact framing, not JSON). */
    fun sendBinary(payload: ByteArray): Boolean

    fun disconnect()
}
