package com.jibe.app.data.repository

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.jibe.app.data.model.FileAckMessage
import com.jibe.app.data.model.FileChunkAckMessage
import com.jibe.app.data.model.FileChunkMessage
import com.jibe.app.data.model.FileDoneMessage
import com.jibe.app.data.model.FileStartMessage
import com.jibe.app.network.MessageParser
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.min
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Progress state for outbound chunked file uploads to the daemon. */
data class TransferProgress(
        val filename: String,
        val bytesSent: Long,
        val totalBytes: Long,
        val isComplete: Boolean = false,
        val error: String? = null
)

/**
 * Streams a document URI to the daemon using ``file.start`` / ``file.chunk`` / ``file.done``.
 *
 * Chunk size matches [EXPECTED_CHUNK_RAW_BYTES] on the Linux receiver.
 */
class FileTransferRepository(
        private val scope: CoroutineScope,
        private val ioDispatcher: CoroutineDispatcher,
        private val sendJson: (String) -> Boolean,
) {

    companion object {
        const val CHUNK_SIZE_BYTES = 65_536
        private const val CHUNK_ACK_TIMEOUT_MS = 30_000L
    }

    private data class PendingChunkAck(
            val transferId: String,
            val index: Int,
            val ack: CompletableDeferred<FileChunkAckMessage>,
    )

    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

    private var pendingTransferId: String? = null
    private var pendingChunkAck: PendingChunkAck? = null

    /** Clears in-flight transfer UI state when the socket tears down. */
    fun reset() {
        pendingChunkAck?.ack?.cancel()
        pendingChunkAck = null
        pendingTransferId = null
        _progress.value = null
    }

    /** Releases the sender when the daemon confirms a chunk has been written. */
    fun onFileChunkAck(ack: FileChunkAckMessage) {
        val pending = pendingChunkAck ?: return
        if (pending.transferId != ack.id || pending.index != ack.index) return
        pendingChunkAck = null
        pending.ack.complete(ack)
    }

    /** Applies a daemon ``file.ack`` to the current pending transfer, if any. */
    fun onFileAck(ack: FileAckMessage) {
        if (ack.id != pendingTransferId) return
        pendingTransferId = null
        if (!ack.ok) {
            pendingChunkAck?.ack?.completeExceptionally(
                    IOException(ack.reason ?: "Transfer rejected")
            )
            pendingChunkAck = null
        }
        _progress.update { cur ->
            cur?.copy(
                    isComplete = true,
                    error =
                            if (ack.ok) null
                            else (ack.reason ?: "Transfer rejected")
            )
                    ?: cur
        }
    }

    /** Start uploading ``uri`` over the active WebSocket (caller ensures connected). */
    fun sendFile(uri: Uri, contentResolver: ContentResolver) {
        scope.launch {
            withContext(ioDispatcher) {
                try {
                    sendFileBlocking(uri, contentResolver)
                } catch (e: Exception) {
                    pendingChunkAck = null
                    _progress.update {
                        it?.copy(isComplete = true, error = e.message ?: "Transfer failed")
                                ?: TransferProgress(
                                        filename = displayName(contentResolver, uri),
                                        bytesSent = 0,
                                        totalBytes = 0,
                                        isComplete = true,
                                        error = e.message ?: "Transfer failed"
                                )
                    }
                    pendingTransferId = null
                }
            }
        }
    }

    private suspend fun sendFileBlocking(uri: Uri, contentResolver: ContentResolver) {
        val transferId = UUID.randomUUID().toString()
        pendingTransferId = transferId

        val filename = displayName(contentResolver, uri)
        val totalBytes =
                contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                        ?: throw IOException("Could not open file")

        val totalChunks =
                if (totalBytes == 0L) 0
                else ((totalBytes + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES).toInt()

        _progress.value =
                TransferProgress(
                        filename = filename,
                        bytesSent = 0,
                        totalBytes = totalBytes,
                        isComplete = false,
                        error = null
                )

        if (
                !sendJson(
                        MessageParser.toJson(
                                FileStartMessage(
                                        id = transferId,
                                        filename = filename,
                                        size = totalBytes,
                                        totalChunks = totalChunks
                                )
                        )
                )
        ) {
            throw IOException("WebSocket rejected file.start")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_SIZE_BYTES)

        contentResolver.openInputStream(uri)?.use { input ->
            var sent = 0L
            var index = 0
            while (sent < totalBytes) {
                val toRead = min(CHUNK_SIZE_BYTES.toLong(), totalBytes - sent).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read <= 0) throw IOException("Unexpected end of file")

                digest.update(buffer, 0, read)
                val chunk = buffer.copyOf(read)
                val data = Base64.encodeToString(chunk, Base64.NO_WRAP)

                val ack = CompletableDeferred<FileChunkAckMessage>()
                pendingChunkAck = PendingChunkAck(transferId, index, ack)

                if (
                        !sendJson(
                                MessageParser.toJson(
                                        FileChunkMessage(
                                                id = transferId,
                                                index = index,
                                                data = data
                                        )
                                )
                        )
                ) {
                    pendingChunkAck = null
                    throw IOException("WebSocket rejected file.chunk")
                }

                val chunkAck = withTimeout(CHUNK_ACK_TIMEOUT_MS) { ack.await() }
                if (!chunkAck.ok) {
                    throw IOException(chunkAck.reason ?: "Daemon rejected file chunk")
                }

                sent = chunkAck.bytesReceived
                index++
                _progress.value =
                        TransferProgress(
                                filename = filename,
                                bytesSent = sent,
                                totalBytes = totalBytes,
                                isComplete = false,
                                error = null
                        )
            }
        }
                ?: throw IOException("Could not read file")

        val checksum =
                digest.digest().joinToString("") { b -> "%02x".format(b) }

        if (!sendJson(MessageParser.toJson(FileDoneMessage(id = transferId, checksum = checksum)))) {
            throw IOException("WebSocket rejected file.done")
        }
    }

    private fun displayName(cr: ContentResolver, uri: Uri): String {
        var cursor: Cursor? = null
        try {
            cursor =
                    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } finally {
            cursor?.close()
        }
        return uri.lastPathSegment ?: "shared"
    }
}
