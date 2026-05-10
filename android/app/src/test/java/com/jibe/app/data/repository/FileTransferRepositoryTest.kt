package com.jibe.app.data.repository

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileTransferRepositoryTest {

    @Test
    fun `cancel active transfer sends file cancel and clears cancelled state`() =
            runTest {
                val dispatcher = StandardTestDispatcher(testScheduler)
                val scope = TestScope(dispatcher)
                val sentJson = mutableListOf<String>()
                val sentBinary = mutableListOf<ByteArray>()

                val repo =
                        FileTransferRepository(
                                scope = scope,
                                ioDispatcher = dispatcher,
                                sendJson = { json ->
                                    sentJson.add(json)
                                    true
                                },
                                sendBinary = { payload ->
                                    sentBinary.add(payload.copyOf())
                                    true
                                },
                        )

                val contentResolver = mockk<ContentResolver>()
                val uri = mockk<Uri>()
                val raw = byteArrayOf(1, 2, 3, 4, 5)

                val cursor = mockk<Cursor>()
                every { cursor.moveToFirst() } returns true
                every { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
                every { cursor.getString(0) } returns "report.txt"
                every { cursor.close() } returns Unit
                every {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                } returns cursor

                val pfd = mockk<ParcelFileDescriptor>()
                every { pfd.statSize } returns raw.size.toLong()
                every { pfd.close() } returns Unit
                every { contentResolver.openFileDescriptor(uri, "r") } returns pfd
                every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(raw)

                repo.sendFile(uri, contentResolver)
                advanceUntilIdle()

                assertTrue(sentJson.any { it.contains("file.start") })
                assertEquals(1, sentBinary.size)

                assertTrue(repo.cancelActiveTransfer())
                val cancelled = repo.progress.value
                assertTrue(cancelled != null && cancelled.isCancelled)
                assertTrue(sentJson.any { it.contains("file.cancel") })

                advanceUntilIdle()
                advanceTimeBy(2_000)
                advanceUntilIdle()
                assertEquals(null, repo.progress.value)
            }
}
