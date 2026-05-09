package com.jibe.app.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jibe.app.data.model.JibeMessage
import com.jibe.app.data.model.MessageType

/**
 * Parses raw WebSocket text frames into typed JibeMessage objects.
 *
 * This is the Android equivalent of the daemon's parse_message() in api.py — the single entry point
 * for all incoming data. If a message passes through here, it's guaranteed to be valid JSON with a
 * known message type.
 *
 * Design: we parse into a generic JibeMessage (type + raw JsonObject) rather than directly into
 * typed classes. This lets the upper layers decide which payload class to deserialize into based on
 * the message type, without the parser needing to know about every possible payload shape.
 */
object MessageParser {

    @PublishedApi internal val gson = Gson()

    /**
     * Parse a raw JSON string into a JibeMessage.
     *
     * @param raw The raw JSON text frame from the WebSocket.
     * @return A JibeMessage with the parsed type and raw payload.
     * @throws MessageParseException if the JSON is invalid or the type is unknown.
     */
    fun parse(raw: String): JibeMessage {
        val json: JsonObject =
                try {
                    JsonParser.parseString(raw).asJsonObject
                } catch (e: Exception) {
                    throw MessageParseException(
                            "malformed_json",
                            "Failed to parse JSON: ${e.message}"
                    )
                }

        val typeStr =
                json.get("type")?.asString
                        ?: throw MessageParseException("malformed_json", "Missing 'type' field")

        val type =
                MessageType.fromValue(typeStr)
                        ?: throw MessageParseException(
                                "unknown_type",
                                "Unknown message type: $typeStr"
                        )

        return JibeMessage(type = type, payload = json)
    }

    /**
     * Deserialize a JibeMessage's payload into a typed data class.
     *
     * Usage: val authResp = MessageParser.payloadAs<AuthResponse>(message)
     */
    inline fun <reified T> payloadAs(message: JibeMessage): T {
        return gson.fromJson(message.payload, T::class.java)
    }

    /** Serialize any object to a JSON string for sending over WebSocket. */
    fun toJson(obj: Any): String = gson.toJson(obj)
}

/**
 * Thrown when an incoming message fails validation.
 *
 * Mirrors the daemon's InvalidMessageError — carries both a machine-readable code and a
 * human-readable message for debugging.
 */
class MessageParseException(val code: String, override val message: String) : Exception(message)
