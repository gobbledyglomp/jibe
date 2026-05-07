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
import com.jibe.app.network.DiscoveredDaemon
import com.jibe.app.network.DiscoveryState
import com.jibe.app.network.JibeDiscovery
import com.jibe.app.network.JibeTrustManager
import com.jibe.app.network.JibeWebSocketClient
import com.jibe.app.network.MessageParser
import com.jibe.app.network.OkHttpFactory
import com.jibe.app.network.WebSocketEvent
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
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
        private lateinit var wsEventsFlow: MutableSharedFlow<WebSocketEvent>

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

                wsEventsFlow = MutableSharedFlow(replay = 1)

                mockkObject(OkHttpFactory)
                val mockClient = mockk<OkHttpClient>(relaxed = true)
                val mockTrustManager = mockk<JibeTrustManager>(relaxed = true)
                every { mockTrustManager.lastSeenFingerprint } returns "test_cert_fp"
                every { OkHttpFactory.create(any()) } returns Pair(mockClient, mockTrustManager)

                mockkConstructor(JibeWebSocketClient::class)
                every { anyConstructed<JibeWebSocketClient>().events } returns wsEventsFlow
                every { anyConstructed<JibeWebSocketClient>().connect(any(), any()) } answers {}
                every { anyConstructed<JibeWebSocketClient>().send(any()) } returns true
                every { anyConstructed<JibeWebSocketClient>().disconnect() } returns Unit

                repoScope =
                        kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
                repository = ConnectionRepository(dataStore, discovery, repoScope)
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
                                verify {
                                        anyConstructed<JibeWebSocketClient>()
                                                .connect("192.168.1.10", 8765)
                                }
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
                                verify {
                                        anyConstructed<JibeWebSocketClient>()
                                                .connect("192.168.1.20", 8765)
                                }
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

                                wsEventsFlow.emit(WebSocketEvent.Connected)
                                advanceUntilIdle()

                                assertEquals(
                                        ConnectionState.Authenticating("10.0.0.5"),
                                        awaitItem()
                                )
                                cancelAndIgnoreRemainingEvents()
                        }
                }

        @Test
        fun `auth response accepted saves credentials and emits Connected`() =
                testScope.runTest {
                        repository.startDiscovery()
                        discoveryStateFlow.value =
                                DiscoveryState.Found(DiscoveredDaemon("Jibe", "10.0.0.5", 8765))
                        advanceUntilIdle()
                        wsEventsFlow.emit(WebSocketEvent.Connected)
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

                                wsEventsFlow.emit(WebSocketEvent.MessageReceived(jibeMessage))
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
        fun `auth response rejected emits Failed state`() =
                testScope.runTest {
                        repository.startDiscovery()
                        discoveryStateFlow.value =
                                DiscoveryState.Found(DiscoveredDaemon("Jibe", "10.0.0.5", 8765))
                        advanceUntilIdle()
                        wsEventsFlow.emit(WebSocketEvent.Connected)
                        advanceUntilIdle()

                        repository.state.test {
                                assertEquals(
                                        ConnectionState.Authenticating("10.0.0.5"),
                                        awaitItem()
                                )

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

                                wsEventsFlow.emit(WebSocketEvent.MessageReceived(jibeMessage))
                                advanceUntilIdle()

                                assertEquals(ConnectionState.Failed("Invalid PIN"), awaitItem())
                                cancelAndIgnoreRemainingEvents()
                        }
                }

        @Test
        fun `receiving error message auth_rejected emits Failed state`() =
                testScope.runTest {
                        repository.startDiscovery()
                        discoveryStateFlow.value =
                                DiscoveryState.Found(DiscoveredDaemon("Jibe", "10.0.0.5", 8765))
                        advanceUntilIdle()
                        wsEventsFlow.emit(WebSocketEvent.Connected)
                        advanceUntilIdle()

                        repository.state.test {
                                assertEquals(
                                        ConnectionState.Authenticating("10.0.0.5"),
                                        awaitItem()
                                )

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

                                wsEventsFlow.emit(WebSocketEvent.MessageReceived(jibeMessage))
                                advanceUntilIdle()

                                assertEquals(
                                        ConnectionState.Failed("Too many failed attempts"),
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
                        wsEventsFlow.emit(WebSocketEvent.Connected)
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
                                wsEventsFlow.emit(WebSocketEvent.MessageReceived(pongMessage))
                                advanceUntilIdle()

                                val result = awaitItem()
                                org.junit.Assert.assertTrue(result.latencyMs >= 0L)

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
                        // stopDiscovery called by disconnect(), startDiscovery called again after
                        verify(atLeast = 1) { discovery.stopDiscovery() }
                        verify(atLeast = 2) { discovery.startDiscovery() }

                        // After forgetDevice, app should be in Discovering mode, not Disconnected,
                        // so it's ready to re-pair without user interaction.
                        assertEquals(ConnectionState.Discovering, repository.state.value)

                }

        @Test
        fun `pairWithPin sends auth request with correct pin`() =
                testScope.runTest {
                        repository.startDiscovery()
                        discoveryStateFlow.value =
                                DiscoveryState.Found(DiscoveredDaemon("Jibe", "10.0.0.5", 8765))
                        advanceUntilIdle()
                        wsEventsFlow.emit(WebSocketEvent.Connected)
                        advanceUntilIdle()

                        repository.pairWithPin(pin = "123456", deviceName = "TestPhone")
                        advanceUntilIdle()

                        verify {
                                anyConstructed<JibeWebSocketClient>()
                                        .send(
                                                match { json ->
                                                        json.contains(
                                                                "\"type\":\"auth.request\""
                                                        ) &&
                                                                json.contains(
                                                                        "\"pin\":\"123456\""
                                                                ) &&
                                                                json.contains(
                                                                        "\"device_name\":\"TestPhone\""
                                                                )
                                                }
                                        )
                        }
                }

        @Test
        fun `disconnection without credentials does not auto-reconnect`() =
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

                                wsEventsFlow.emit(WebSocketEvent.Error(Exception("Timeout")))
                                advanceUntilIdle()

                                assertEquals(ConnectionState.Disconnected, awaitItem())

                                advanceTimeBy(5000)
                                expectNoEvents()

                                cancelAndIgnoreRemainingEvents()
                        }
                }
}
