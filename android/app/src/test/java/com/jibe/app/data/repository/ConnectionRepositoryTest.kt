package com.jibe.app.data.repository

import android.util.Log
import app.cash.turbine.test
import com.jibe.app.data.local.DeviceCredentials
import com.jibe.app.data.local.JibeDataStore
import com.jibe.app.data.model.AuthResponse
import com.jibe.app.data.model.ErrorMessage
import com.jibe.app.data.model.JibeMessage
import com.jibe.app.data.model.MessageType
import com.jibe.app.data.model.PingMessage
import com.jibe.app.network.DaemonTlsSocketFactory
import com.jibe.app.network.DiscoveredDaemon
import com.jibe.app.network.DiscoveryState
import com.jibe.app.network.JibeDiscovery
import com.jibe.app.network.JibeTrustManager
import com.jibe.app.network.JibeWebSocketHandle
import com.jibe.app.network.MessageParser
import com.jibe.app.network.WebSocketEvent
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionRepositoryTest {

        private lateinit var repository: ConnectionRepository
        private lateinit var dataStore: JibeDataStore
        private lateinit var discovery: JibeDiscovery
        private lateinit var testScope: TestScope
        private val testDispatcher = StandardTestDispatcher()
        private lateinit var repoScope: kotlinx.coroutines.CoroutineScope

        private lateinit var discoveryStateFlow: MutableStateFlow<DiscoveryState>
        private lateinit var credentialsFlow: MutableStateFlow<DeviceCredentials?>

        private lateinit var trustManager: JibeTrustManager
        private lateinit var recordingSocket: RecordingWebSocketHandle
        private lateinit var socketFactory: DaemonTlsSocketFactory

        private class RecordingWebSocketHandle(
                private val eventsMutable: MutableSharedFlow<WebSocketEvent> =
                        MutableSharedFlow(extraBufferCapacity = 64)
        ) : JibeWebSocketHandle {

                override val events: SharedFlow<WebSocketEvent> = eventsMutable.asSharedFlow()

                val connectCalls = mutableListOf<Pair<String, Int>>()

                val sentPayloads = mutableListOf<String>()

                val sentBinaries = mutableListOf<ByteArray>()

                override fun connect(host: String, port: Int) {
                        connectCalls.add(host to port)
                }

                override fun send(json: String): Boolean {
                        sentPayloads.add(json)
                        return true
                }

                override fun sendBinary(payload: ByteArray): Boolean {
                        sentBinaries.add(payload.copyOf())
                        return true
                }

                override fun disconnect() {}

                suspend fun emit(event: WebSocketEvent) = eventsMutable.emit(event)
        }

        @Before
        fun setup() {
                testScope = TestScope(testDispatcher)

                mockkStatic(Log::class)
                every { Log.v(any(), any()) } returns 0
                every { Log.d(any(), any()) } returns 0
                every { Log.i(any(), any()) } returns 0
                every { Log.w(any(), any<String>()) } returns 0
                every { Log.e(any(), any()) } returns 0
                every { Log.e(any(), any(), any()) } returns 0

                dataStore = mockk(relaxed = true)
                discovery = mockk(relaxed = true)

                discoveryStateFlow = MutableStateFlow(DiscoveryState.Idle)
                every { discovery.state } returns discoveryStateFlow

                credentialsFlow = MutableStateFlow(null)
                every { dataStore.credentials } returns credentialsFlow

                trustManager = mockk(relaxed = true)
                every { trustManager.lastSeenFingerprint } returns "test_cert_fp"

                recordingSocket = RecordingWebSocketHandle()
                socketFactory = DaemonTlsSocketFactory { recordingSocket to trustManager }

                repoScope =
                        kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
                repository =
                        ConnectionRepository(
                                dataStore = dataStore,
                                discovery = discovery,
                                scope = repoScope,
                                deviceNameProvider = DeviceNameProvider { "TestPhone" },
                                socketFactory = socketFactory,
                                clipboardWriter = ClipboardWriter {},
                                connectionDispatcher = testDispatcher,
                        )
        }

        @After
        fun tearDown() {
                repository.disconnect()
                repoScope.cancel()
                unmockkAll()
        }

        @Test
        fun `start with no credentials triggers discovery`() =
                testScope.runTest {
                        repository.state.test {
                                assertEquals(ConnectionState.Disconnected, awaitItem())

                                repository.start()
                                advanceUntilIdle()

                                assertEquals(ConnectionState.Discovering, awaitItem())
                                verify { discovery.startDiscovery() }
                                cancelAndIgnoreRemainingEvents()
                        }
                }

        @Test
        fun `start with credentials skips discovery and connects directly`() =
                testScope.runTest {
                        val creds =
                                DeviceCredentials(
                                        "dev_1",
                                        "finger_1",
                                        "192.168.1.10",
                                        8765,
                                        "cert_fp"
                                )
                        credentialsFlow.value = creds

                        repository.state.test {
                                assertEquals(ConnectionState.Disconnected, awaitItem())

                                repository.start()
                                advanceUntilIdle()

                                assertEquals(
                                        ConnectionState.Connecting("192.168.1.10", 8765),
                                        awaitItem()
                                )
                                verify(exactly = 0) { discovery.startDiscovery() }
                                assertEquals(
                                        listOf("192.168.1.10" to 8765),
                                        recordingSocket.connectCalls
                                )
                                cancelAndIgnoreRemainingEvents()
                        }
                }

        @Test
        fun `discovery finding a daemon stops discovery and connects`() =
                testScope.runTest {
                        repository.state.test {
                                assertEquals(ConnectionState.Disconnected, awaitItem())

                                repository.startDiscovery()
                                assertEquals(ConnectionState.Discovering, awaitItem())

                                val daemon = DiscoveredDaemon("JibeDaemon", "192.168.1.20", 8765)
                                discoveryStateFlow.value = DiscoveryState.Found(daemon)
                                advanceUntilIdle()

                                assertEquals(
                                        ConnectionState.Connecting("192.168.1.20", 8765),
                                        awaitItem()
                                )
                                verify { discovery.stopDiscovery() }
                                assertEquals(
                                        listOf("192.168.1.20" to 8765),
                                        recordingSocket.connectCalls
                                )
                                cancelAndIgnoreRemainingEvents()
                        }
                }

        @Test
        fun `websocket connected emits Authenticating state`() =
                testScope.runTest {
                        repository.startDiscovery()
                        val daemon = DiscoveredDaemon("Jibe", "10.0.0.5", 8765)
                        discoveryStateFlow.value = DiscoveryState.Found(daemon)
                        advanceUntilIdle()

                        repository.state.test {
                                assertEquals(
                                        ConnectionState.Connecting("10.0.0.5", 8765),
                                        awaitItem()
                                )

                                recordingSocket.emit(WebSocketEvent.Connected)
                                advanceUntilIdle()

                                assertEquals(
                                        ConnectionState.Authenticating("10.0.0.5"),
                                        awaitItem()
                                )
                                cancelAndIgnoreRemainingEvents()
                        }
                }

        @Test
        fun `auth response without pairing mode emits PairingUnavailable`() =
                testScope.runTest {
                        repository.startDiscovery()
                        discoveryStateFlow.value =
                                DiscoveryState.Found(DiscoveredDaemon("Jibe", "10.0.0.5", 8765))
                        advanceUntilIdle()
                        recordingSocket.emit(WebSocketEvent.Connected)
                        advanceUntilIdle()

                        val authMsg =
                                AuthResponse(
                                        type = MessageType.AUTH_RESPONSE.value,
                                        accepted = false,
                                        reason =
                                                "Pairing mode is not active. Start pairing on the daemon (--pair or SIGUSR1), then enter the PIN shown in the daemon logs."
                                )

                        recordingSocket.emit(
                                WebSocketEvent.MessageReceived(
                                        JibeMessage(
                                                type = MessageType.AUTH_RESPONSE,
                                                payload =
                                                        MessageParser.gson.toJsonTree(authMsg)
                                                                .asJsonObject
                                        )
                                )
                        )
                        advanceUntilIdle()

                        assertEquals(
                                ConnectionState.PairingUnavailable(
                                        reason =
                                                "Pairing mode is not active. Start pairing on the daemon (--pair or SIGUSR1), then enter the PIN shown in the daemon logs.",
                                        guidance =
                                                "Start pairing mode on the daemon (--pair or SIGUSR1), then tap Retry."
                                ),
                                repository.state.value
                        )
                }

        @Test
        fun `auth response accepted saves credentials and emits Connected`() =
                testScope.runTest {
                        repository.startDiscovery()
                        discoveryStateFlow.value =
                                DiscoveryState.Found(DiscoveredDaemon("Jibe", "10.0.0.5", 8765))
                        advanceUntilIdle()
                        recordingSocket.emit(WebSocketEvent.Connected)
                        advanceUntilIdle()

                        repository.state.test {
                                assertEquals(
                                        ConnectionState.Authenticating("10.0.0.5"),
                                        awaitItem()
                                )

                                val authMsg =
                                        AuthResponse(
                                                type = MessageType.AUTH_RESPONSE.value,
                                                accepted = true,
                                                reason = "Paired",
                                                deviceId = "dev_123",
                                                fingerprint = "fp_abc"
                                        )
                                val jibeMessage =
                                        JibeMessage(
                                                type = MessageType.AUTH_RESPONSE,
                                                payload =
                                                        MessageParser.gson.toJsonTree(authMsg)
                                                                .asJsonObject
                                        )

                                recordingSocket.emit(WebSocketEvent.MessageReceived(jibeMessage))
                                advanceUntilIdle()

                                assertEquals(
                                        ConnectionState.Connected("10.0.0.5", 8765, "dev_123"),
                                        awaitItem()
                                )

                                coVerify {
                                        dataStore.saveCredentials(
                                                DeviceCredentials(
                                                        "dev_123",
                                                        "fp_abc",
                                                        "10.0.0.5",
                                                        8765,
                                                        "test_cert_fp"
                                                )
                                        )
                                }
                                cancelAndIgnoreRemainingEvents()
                        }
                }

        @Test
        fun `auth response rejected during pairing stays Authenticating with attempt hint`() =
                testScope.runTest {
                        repository.startDiscovery()
                        discoveryStateFlow.value =
                                DiscoveryState.Found(DiscoveredDaemon("Jibe", "10.0.0.5", 8765))
                        advanceUntilIdle()
                        recordingSocket.emit(WebSocketEvent.Connected)
                        advanceUntilIdle()

                        repository.state.test {
                                assertEquals(
                                        ConnectionState.Authenticating("10.0.0.5"),
                                        awaitItem()
                                )

                                repository.pairWithPin(pin = "111111", deviceName = "TestPhone")
                                val authMsg =
                                        AuthResponse(
                                                type = MessageType.AUTH_RESPONSE.value,
                                                accepted = false,
                                                reason = "Invalid PIN"
                                        )
                                val jibeMessage =
                                        JibeMessage(
                                                type = MessageType.AUTH_RESPONSE,
                                                payload =
                                                        MessageParser.gson.toJsonTree(authMsg)
                                                                .asJsonObject
                                        )

                                recordingSocket.emit(WebSocketEvent.MessageReceived(jibeMessage))
                                advanceUntilIdle()

                                assertEquals(
                                        ConnectionState.Authenticating(
                                                "10.0.0.5",
                                                hint = "Wrong PIN — 4 attempts remaining"
                                        ),
                                        awaitItem()
                                )
                                cancelAndIgnoreRemainingEvents()
                        }
                }

        @Test
        fun `fifth rejected auth response during pairing emits PairingFailed`() =
                testScope.runTest {
                        repository.startDiscovery()
                        discoveryStateFlow.value =
                                DiscoveryState.Found(DiscoveredDaemon("Jibe", "10.0.0.5", 8765))
                        advanceUntilIdle()
                        recordingSocket.emit(WebSocketEvent.Connected)
                        advanceUntilIdle()

                        assertEquals(
                                ConnectionState.Authenticating("10.0.0.5"),
                                repository.state.value
                        )

                        repeat(4) { attempt ->
                                repository.pairWithPin(pin = "111111", deviceName = "TestPhone")
                                val authMsg =
                                        AuthResponse(
                                                type = MessageType.AUTH_RESPONSE.value,
                                                accepted = false,
                                                reason = "Invalid PIN"
                                        )
                                recordingSocket.emit(
                                        WebSocketEvent.MessageReceived(
                                                JibeMessage(
                                                        type = MessageType.AUTH_RESPONSE,
                                                        payload =
                                                                MessageParser.gson.toJsonTree(authMsg)
                                                                        .asJsonObject
                                                )
                                        )
                                )
                                advanceUntilIdle()
                                val remaining = 5 - (attempt + 1)
                                assertEquals(
                                        ConnectionState.Authenticating(
                                                "10.0.0.5",
                                                hint =
                                                        "Wrong PIN — $remaining ${if (remaining == 1) "attempt" else "attempts"} remaining"
                                        ),
                                        repository.state.value
                                )
                        }

                        repository.pairWithPin(pin = "222222", deviceName = "TestPhone")
                        recordingSocket.emit(
                                WebSocketEvent.MessageReceived(
                                        JibeMessage(
                                                type = MessageType.AUTH_RESPONSE,
                                                payload =
                                                        MessageParser.gson.toJsonTree(
                                                                        AuthResponse(
                                                                                type =
                                                                                        MessageType
                                                                                                .AUTH_RESPONSE
                                                                                                .value,
                                                                                accepted = false,
                                                                                reason =
                                                                                        "Invalid PIN (5)"
                                                                        )
                                                                )
                                                                .asJsonObject
                                        )
                                )
                        )
                        advanceUntilIdle()
                        assertEquals(
                                ConnectionState.PairingFailed(
                                        reason = "Invalid PIN (5)",
                                        guidance =
                                                "Restart the daemon to reset pairing, then tap Retry."
                                ),
                                repository.state.value
                        )
                }

        @Test
        fun `receiving error message auth_rejected after pin entry emits PairingFailed state`() =
                testScope.runTest {
                        repository.startDiscovery()
                        discoveryStateFlow.value =
                                DiscoveryState.Found(DiscoveredDaemon("Jibe", "10.0.0.5", 8765))
                        advanceUntilIdle()
                        recordingSocket.emit(WebSocketEvent.Connected)
                        advanceUntilIdle()

                        repository.state.test {
                                assertEquals(
                                        ConnectionState.Authenticating("10.0.0.5"),
                                        awaitItem()
                                )

                                repository.pairWithPin(pin = "123456", deviceName = "TestPhone")

                                val errMsg =
                                        ErrorMessage(
                                                type = MessageType.ERROR.value,
                                                code = "auth_rejected",
                                                message = "Too many failed attempts"
                                        )
                                val jibeMessage =
                                        JibeMessage(
                                                type = MessageType.ERROR,
                                                payload =
                                                        MessageParser.gson.toJsonTree(errMsg)
                                                                .asJsonObject
                                        )

                                recordingSocket.emit(WebSocketEvent.MessageReceived(jibeMessage))
                                advanceUntilIdle()

                                assertEquals(
                                        ConnectionState.PairingFailed(
                                                reason = "Too many failed attempts",
                                                guidance =
                                                        "Restart the daemon to reset pairing, then tap Retry."
                                        ),
                                        awaitItem()
                                )
                                cancelAndIgnoreRemainingEvents()
                        }
                }

        @Test
        fun `receiving ping result measures latency`() =
                testScope.runTest {
                        repository.startDiscovery()
                        discoveryStateFlow.value =
                                DiscoveryState.Found(DiscoveredDaemon("Jibe", "10.0.0.5", 8765))
                        advanceUntilIdle()
                        recordingSocket.emit(WebSocketEvent.Connected)
                        advanceUntilIdle()

                        repository.pingResults.test {
                                repository.sendPing()

                                advanceTimeBy(50)

                                val pongMessage =
                                        JibeMessage(
                                                type = MessageType.PONG,
                                                payload =
                                                        MessageParser.gson.toJsonTree(PingMessage())
                                                                .asJsonObject
                                        )
                                recordingSocket.emit(WebSocketEvent.MessageReceived(pongMessage))
                                advanceUntilIdle()

                                val result = awaitItem()
                                assertTrue(result.latencyMs >= 0L)

                                cancelAndIgnoreRemainingEvents()
                        }
                }

        @Test
        fun `forgetDevice clears datastore and restarts discovery`() =
                testScope.runTest {
                        repository.startDiscovery()
                        advanceUntilIdle()

                        repository.forgetDevice()
                        advanceUntilIdle()

                        coVerify { dataStore.clearCredentials() }
                        verify(atLeast = 1) { discovery.stopDiscovery() }
                        verify(atLeast = 2) { discovery.startDiscovery() }

                        assertEquals(ConnectionState.Discovering, repository.state.value)
                }

        @Test
        fun `pairWithPin sends auth request with correct pin`() =
                testScope.runTest {
                        repository.startDiscovery()
                        discoveryStateFlow.value =
                                DiscoveryState.Found(DiscoveredDaemon("Jibe", "10.0.0.5", 8765))
                        advanceUntilIdle()
                        recordingSocket.emit(WebSocketEvent.Connected)
                        advanceUntilIdle()

                        recordingSocket.sentPayloads.clear()

                        repository.pairWithPin(pin = "123456", deviceName = "TestPhone")
                        advanceUntilIdle()

                        assertEquals(1, recordingSocket.sentPayloads.size)
                        val json = recordingSocket.sentPayloads.single()
                        assertTrue(json.contains("\"type\":\"auth.request\""))
                        assertTrue(json.contains("\"pin\":\"123456\""))
                        assertTrue(json.contains("\"device_name\":\"TestPhone\""))
                }

        @Test
        fun `disconnection while Connecting during pairing restarts discovery after backoff`() =
                testScope.runTest {
                        credentialsFlow.value = null

                        repository.startDiscovery()
                        discoveryStateFlow.value =
                                DiscoveryState.Found(DiscoveredDaemon("Jibe", "10.0.0.5", 8765))
                        advanceUntilIdle()

                        repository.state.test {
                                assertEquals(
                                        ConnectionState.Connecting("10.0.0.5", 8765),
                                        awaitItem()
                                )

                                recordingSocket.emit(WebSocketEvent.Error(Exception("Timeout")))
                                advanceUntilIdle()

                                advanceTimeBy(1_500)
                                advanceUntilIdle()

                                assertEquals(ConnectionState.Discovering, awaitItem())

                                cancelAndIgnoreRemainingEvents()
                        }
                }

        @Test
        fun `disconnection while Authenticating reconnects directly without going through Discovering`() =
                testScope.runTest {
                        credentialsFlow.value = null

                        repository.startDiscovery()
                        discoveryStateFlow.value =
                                DiscoveryState.Found(DiscoveredDaemon("Jibe", "10.0.0.5", 8765))
                        advanceUntilIdle()
                        recordingSocket.emit(WebSocketEvent.Connected)
                        advanceUntilIdle()

                        assertEquals(
                                ConnectionState.Authenticating("10.0.0.5"),
                                repository.state.value
                        )

                        recordingSocket.emit(WebSocketEvent.Disconnected(1001, "daemon timeout"))
                        advanceUntilIdle()

                        advanceTimeBy(1_500)
                        advanceUntilIdle()

                        assertEquals(
                                ConnectionState.Connecting("10.0.0.5", 8765),
                                repository.state.value
                        )
                }

        @Test
        fun `pairing failure does not auto reconnect after disconnect`() =
                testScope.runTest {
                        repository.startDiscovery()
                        discoveryStateFlow.value =
                                DiscoveryState.Found(DiscoveredDaemon("Jibe", "10.0.0.5", 8765))
                        advanceUntilIdle()

                        repository.state.test {
                                assertEquals(
                                        ConnectionState.Connecting("10.0.0.5", 8765),
                                        awaitItem()
                                )

                                recordingSocket.emit(WebSocketEvent.Connected)
                                advanceUntilIdle()
                                assertEquals(
                                        ConnectionState.Authenticating("10.0.0.5"),
                                        awaitItem()
                                )

                                repository.pairWithPin(pin = "123456", deviceName = "TestPhone")
                                recordingSocket.emit(
                                        WebSocketEvent.MessageReceived(
                                                JibeMessage(
                                                        type = MessageType.ERROR,
                                                        payload =
                                                                MessageParser.gson.toJsonTree(
                                                                                ErrorMessage(
                                                                                        type =
                                                                                                MessageType
                                                                                                        .ERROR
                                                                                                        .value,
                                                                                        code =
                                                                                                "auth_rejected",
                                                                                        message =
                                                                                                "Too many failed attempts"
                                                                                )
                                                                        )
                                                                        .asJsonObject
                                                )
                                        )
                                )
                                advanceUntilIdle()

                                assertEquals(
                                        ConnectionState.PairingFailed(
                                                reason = "Too many failed attempts",
                                                guidance =
                                                        "Restart the daemon to reset pairing, then tap Retry."
                                        ),
                                        awaitItem()
                                )

                                recordingSocket.emit(
                                        WebSocketEvent.Disconnected(1000, "daemon closed")
                                )
                                advanceUntilIdle()

                                expectNoEvents()
                                cancelAndIgnoreRemainingEvents()
                        }
                }

        @Test
        fun `disconnection while PairingUnavailable restarts discovery`() =
                testScope.runTest {
                        repository.startDiscovery()
                        discoveryStateFlow.value =
                                DiscoveryState.Found(DiscoveredDaemon("Jibe", "10.0.0.5", 8765))
                        advanceUntilIdle()

                        recordingSocket.emit(WebSocketEvent.Connected)
                        advanceUntilIdle()

                        val authMsg =
                                AuthResponse(
                                        type = MessageType.AUTH_RESPONSE.value,
                                        accepted = false,
                                        reason =
                                                "Pairing mode is not active. Start pairing on the daemon (--pair or SIGUSR1), then enter the PIN shown in the daemon logs."
                                )

                        recordingSocket.emit(
                                WebSocketEvent.MessageReceived(
                                        JibeMessage(
                                                type = MessageType.AUTH_RESPONSE,
                                                payload =
                                                        MessageParser.gson.toJsonTree(authMsg)
                                                                .asJsonObject
                                        )
                                )
                        )
                        advanceUntilIdle()

                        assertEquals(
                                ConnectionState.PairingUnavailable(
                                        reason =
                                                "Pairing mode is not active. Start pairing on the daemon (--pair or SIGUSR1), then enter the PIN shown in the daemon logs.",
                                        guidance =
                                                "Start pairing mode on the daemon (--pair or SIGUSR1), then tap Retry."
                                ),
                                repository.state.value
                        )

                        // Daemon gone — stale Found would make discovery collector reconnect instantly.
                        discoveryStateFlow.value = DiscoveryState.Searching

                        recordingSocket.emit(WebSocketEvent.Disconnected(1000, "daemon stopped"))
                        advanceUntilIdle()

                        assertEquals(ConnectionState.Discovering, repository.state.value)
                }

        @Test
        fun `retry after pairing lockout direct reconnects on disconnect instead of snap back to pairing failed`() =
                testScope.runTest {
                        repository.startDiscovery()
                        discoveryStateFlow.value =
                                DiscoveryState.Found(DiscoveredDaemon("Jibe", "10.0.0.5", 8765))
                        advanceUntilIdle()
                        recordingSocket.emit(WebSocketEvent.Connected)
                        advanceUntilIdle()

                        repository.pairWithPin(pin = "123456", deviceName = "TestPhone")
                        recordingSocket.emit(
                                WebSocketEvent.MessageReceived(
                                        JibeMessage(
                                                type = MessageType.ERROR,
                                                payload =
                                                        MessageParser.gson.toJsonTree(
                                                                        ErrorMessage(
                                                                                type =
                                                                                        MessageType
                                                                                                .ERROR
                                                                                                .value,
                                                                                code =
                                                                                        "auth_rejected",
                                                                                message =
                                                                                        "Too many failed attempts"
                                                                        )
                                                                )
                                                                .asJsonObject
                                        )
                                )
                        )
                        advanceUntilIdle()

                        val failure =
                                ConnectionState.PairingFailed(
                                        reason = "Too many failed attempts",
                                        guidance =
                                                "Restart the daemon to reset pairing, then tap Retry."
                                )
                        assertEquals(failure, repository.state.value)

                        discoveryStateFlow.value = DiscoveryState.Idle
                        repository.retryPairing(afterPairingLockout = true)
                        advanceTimeBy(120)
                        advanceUntilIdle()

                        assertEquals(ConnectionState.Discovering, repository.state.value)

                        discoveryStateFlow.value =
                                DiscoveryState.Found(DiscoveredDaemon("Jibe", "10.0.0.5", 8765))
                        advanceUntilIdle()

                        assertEquals(
                                ConnectionState.Connecting("10.0.0.5", 8765),
                                repository.state.value
                        )
                        recordingSocket.emit(WebSocketEvent.Connected)
                        advanceUntilIdle()
                        assertEquals(
                                ConnectionState.Connecting("10.0.0.5", 8765),
                                repository.state.value
                        )

                        recordingSocket.emit(WebSocketEvent.Disconnected(1000, "daemon closed"))
                        advanceUntilIdle()

                        advanceTimeBy(1_500)
                        advanceUntilIdle()

                        assertTrue(repository.state.value !is ConnectionState.PairingFailed)
                        assertEquals(
                                ConnectionState.Connecting("10.0.0.5", 8765),
                                repository.state.value
                        )
                }

        @Test
        fun `ping timeout after daemon death reconnects instead of staying connected`() =
                testScope.runTest {
                        val creds =
                                DeviceCredentials("dev_1", "finger_1", "10.0.0.5", 8765, "cert_fp")
                        credentialsFlow.value = creds

                        repository.start()
                        advanceUntilIdle()

                        repository.state.test {
                                assertEquals(
                                        ConnectionState.Connecting("10.0.0.5", 8765),
                                        awaitItem()
                                )

                                recordingSocket.emit(WebSocketEvent.Connected)
                                advanceUntilIdle()
                                assertEquals(
                                        ConnectionState.Authenticating("10.0.0.5"),
                                        awaitItem()
                                )

                                val authMsg =
                                        AuthResponse(
                                                type = MessageType.AUTH_RESPONSE.value,
                                                accepted = true,
                                                reason = "Paired",
                                                deviceId = "dev_123",
                                                fingerprint = "fp_abc"
                                        )
                                val jibeMessage =
                                        JibeMessage(
                                                type = MessageType.AUTH_RESPONSE,
                                                payload =
                                                        MessageParser.gson.toJsonTree(authMsg)
                                                                .asJsonObject
                                        )
                                recordingSocket.emit(WebSocketEvent.MessageReceived(jibeMessage))
                                advanceUntilIdle()
                                assertEquals(
                                        ConnectionState.Connected("10.0.0.5", 8765, "dev_123"),
                                        awaitItem()
                                )

                                repository.sendPing()
                                advanceTimeBy(5_000)
                                advanceUntilIdle()

                                assertEquals(
                                        ConnectionState.Connecting("10.0.0.5", 8765),
                                        awaitItem()
                                )
                                assertEquals(2, recordingSocket.connectCalls.size)
                                cancelAndIgnoreRemainingEvents()
                        }
                }
}
