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
        @SerializedName("file.done")       FILE_DONE("file.done"),
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
