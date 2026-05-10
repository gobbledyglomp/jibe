package com.jibe.app.data.model

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

enum class MessageType(val value: String) {
        @SerializedName("auth.request")    AUTH_REQUEST("auth.request"),
        @SerializedName("auth.response")   AUTH_RESPONSE("auth.response"),
        @SerializedName("ping")            PING("ping"),
        @SerializedName("pong")            PONG("pong"),
        @SerializedName("clipboard.sync")  CLIPBOARD_SYNC("clipboard.sync"),
        @SerializedName("notification")    NOTIFICATION("notification"),
        @SerializedName("file.start")      FILE_START("file.start"),
        @SerializedName("file.chunk")      FILE_CHUNK("file.chunk"),
        @SerializedName("file.chunk.ack")  FILE_CHUNK_ACK("file.chunk.ack"),
        @SerializedName("file.done")       FILE_DONE("file.done"),
        @SerializedName("file.ack")        FILE_ACK("file.ack"),
        @SerializedName("error")           ERROR("error");

        companion object {
                fun fromValue(value: String): MessageType? = entries.find { it.value == value }
        }
}

data class JibeMessage(val type: MessageType, val payload: JsonObject)

data class AuthRequest(
        @SerializedName("type") val type: String = MessageType.AUTH_REQUEST.value,
        @SerializedName("device_name") val deviceName: String,
        @SerializedName("pin") val pin: String? = null,
        @SerializedName("fingerprint") val fingerprint: String? = null
)

data class AuthResponse(
        @SerializedName("type") val type: String,
        @SerializedName("accepted") val accepted: Boolean,
        @SerializedName("reason") val reason: String = "",
        @SerializedName("device_id") val deviceId: String? = null,
        @SerializedName("fingerprint") val fingerprint: String? = null
)

data class PingMessage(@SerializedName("type") val type: String = MessageType.PING.value)

data class PongMessage(@SerializedName("type") val type: String = MessageType.PONG.value)

data class ErrorMessage(
        @SerializedName("type") val type: String,
        @SerializedName("code") val code: String,
        @SerializedName("message") val message: String
)

data class ClipboardSyncMessage(
        @SerializedName("type") val type: String = MessageType.CLIPBOARD_SYNC.value,
        @SerializedName("content") val content: String
)

data class NotificationMessage(
        @SerializedName("type") val type: String = MessageType.NOTIFICATION.value,
        @SerializedName("app") val app: String,
        @SerializedName("app_name") val appName: String,
        @SerializedName("title") val title: String,
        @SerializedName("body") val body: String,
        @SerializedName("timestamp") val timestamp: Long,
        @SerializedName("icon") val icon: String? = null
)

data class FileStartMessage(
        @SerializedName("type") val type: String = MessageType.FILE_START.value,
        @SerializedName("id") val id: String,
        @SerializedName("filename") val filename: String,
        @SerializedName("size") val size: Long,
        @SerializedName("total_chunks") val totalChunks: Int
)

data class FileChunkMessage(
        @SerializedName("type") val type: String = MessageType.FILE_CHUNK.value,
        @SerializedName("id") val id: String,
        @SerializedName("index") val index: Int,
        @SerializedName("data") val data: String
)

data class FileChunkAckMessage(
        @SerializedName("type") val type: String,
        @SerializedName("id") val id: String,
        @SerializedName("index") val index: Int,
        @SerializedName("ok") val ok: Boolean,
        @SerializedName("bytes_received") val bytesReceived: Long = 0,
        @SerializedName("reason") val reason: String? = null
)

data class FileDoneMessage(
        @SerializedName("type") val type: String = MessageType.FILE_DONE.value,
        @SerializedName("id") val id: String,
        @SerializedName("checksum") val checksum: String
)

data class FileAckMessage(
        @SerializedName("type") val type: String,
        @SerializedName("id") val id: String,
        @SerializedName("ok") val ok: Boolean,
        @SerializedName("reason") val reason: String? = null
)
