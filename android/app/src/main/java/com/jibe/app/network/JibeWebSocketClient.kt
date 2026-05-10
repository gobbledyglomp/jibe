package com.jibe.app.network

import android.util.Log
import com.jibe.app.data.model.JibeMessage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

/** Events emitted by the WebSocket connection to the upper layers. */
sealed class WebSocketEvent {
    data object Connected : WebSocketEvent()

    data class MessageReceived(val message: JibeMessage) : WebSocketEvent()

    data class Disconnected(val code: Int, val reason: String) : WebSocketEvent()

    data class Error(val throwable: Throwable) : WebSocketEvent()
}

/**
 * WebSocket client wrapping OkHttp's WebSocket with Kotlin coroutines.
 *
 * Instead of callbacks, this class exposes incoming events as a SharedFlow (the Kotlin equivalent
 * of an RxJS Observable or Python's asyncio.Queue). The Repository layer collects this flow to
 * drive the state machine.
 *
 * Lifecycle:
 * 1. Create with an OkHttpClient (already configured for TLS)
 * 2. Call connect(host, port) to open the WebSocket
 * 3. Collect events from the events flow
 * 4. Call send() to send messages
 * 5. Call disconnect() to close cleanly
 *
 * @param client The OkHttpClient instance (with custom TLS trust manager).
 */
class JibeWebSocketClient(private val client: OkHttpClient) : JibeWebSocketHandle {

    companion object {
        private const val TAG = "JibeWebSocket"
        private const val WS_PATH = "/ws"
        private const val NORMAL_CLOSURE = 1000
    }

    private var webSocket: WebSocket? = null

    private val _events =
            MutableSharedFlow<WebSocketEvent>(
                    replay = 0,
                    extraBufferCapacity = 64,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
    override val events: SharedFlow<WebSocketEvent> = _events.asSharedFlow()

    /**
     * Open a WebSocket connection to the daemon.
     *
     * Uses wss:// (TLS) since the daemon serves self-signed certs. The OkHttpClient's custom
     * TrustManager handles cert validation.
     *
     * @param host The daemon's IP address (from mDNS discovery).
     * @param port The daemon's port (from mDNS discovery, default 8765).
     */
    override fun connect(host: String, port: Int) {
        disconnect()

        val url = "wss://$host:$port$WS_PATH"
        Log.d(TAG, "Connecting to $url")

        val request = Request.Builder().url(url).build()

        webSocket =
                client.newWebSocket(
                        request,
                        object : WebSocketListener() {

                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                Log.i(TAG, "WebSocket connected to $url")
                                _events.tryEmit(WebSocketEvent.Connected)
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                try {
                                    val message = MessageParser.parse(text)
                                    _events.tryEmit(WebSocketEvent.MessageReceived(message))
                                } catch (e: MessageParseException) {
                                    Log.w(TAG, "Failed to parse message: ${e.message}")
                                }
                            }

                            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                                Log.w(TAG, "Ignoring unexpected binary frame (${bytes.size} bytes)")
                            }

                            override fun onClosing(
                                    webSocket: WebSocket,
                                    code: Int,
                                    reason: String
                            ) {
                                Log.i(TAG, "WebSocket closing: $code $reason")
                                // Acknowledge close; emit [WebSocketEvent.Disconnected] only from
                                // onClosed so we do not double-invoke repository reconnect logic.
                                webSocket.close(NORMAL_CLOSURE, null)
                            }

                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                                Log.i(TAG, "WebSocket closed: $code $reason")
                                _events.tryEmit(WebSocketEvent.Disconnected(code, reason))
                            }

                            override fun onFailure(
                                    webSocket: WebSocket,
                                    t: Throwable,
                                    response: Response?
                            ) {
                                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                                _events.tryEmit(WebSocketEvent.Error(t))
                            }
                        }
                )
    }

    /**
     * Send a JSON string to the daemon.
     *
     * @param json The serialized JSON message (use MessageParser.toJson()).
     * @return true if the message was enqueued, false if the socket is closed.
     */
    override fun send(json: String): Boolean {
        val ws =
                webSocket
                        ?: run {
                            Log.w(TAG, "Attempted to send on a closed WebSocket")
                            return false
                        }
        return ws.send(json)
    }

    override fun sendBinary(payload: ByteArray): Boolean {
        val ws =
                webSocket
                        ?: run {
                            Log.w(TAG, "Attempted to send binary on a closed WebSocket")
                            return false
                        }
        return ws.send(payload.toByteString())
    }

    /** Close the WebSocket connection cleanly. */
    override fun disconnect() {
        webSocket?.close(NORMAL_CLOSURE, "Client disconnecting")
        webSocket = null
    }
}
