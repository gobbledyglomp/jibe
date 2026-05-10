package com.jibe.app.data.repository

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Compact binary framing for file chunk payloads on the WebSocket (magic ``JBFC``).
 *
 * Must stay byte-compatible with ``daemon/jibe/handlers/transfer.py``
 * ``FILE_CHUNK_HEADER_STRUCT``.
 */
internal object FileChunkBinaryWire {

    const val HEADER_SIZE = 32

    private val MAGIC =
            byteArrayOf(
                    'J'.code.toByte(),
                    'B'.code.toByte(),
                    'F'.code.toByte(),
                    'C'.code.toByte(),
            )

    private const val VERSION: Byte = 1

    fun encode(transferId: String, chunkIndex: Int, payload: ByteArray, payloadLen: Int): ByteArray {
        require(payloadLen <= payload.size)
        val frame = ByteArray(HEADER_SIZE + payloadLen)
        val bb = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        bb.put(MAGIC)
        bb.put(VERSION)
        bb.put(0)
        bb.putShort(0)
        bb.putInt(chunkIndex)
        bb.putInt(payloadLen)
        val u = UUID.fromString(transferId)
        bb.putLong(u.mostSignificantBits)
        bb.putLong(u.leastSignificantBits)
        bb.put(payload, 0, payloadLen)
        return frame
    }
}
