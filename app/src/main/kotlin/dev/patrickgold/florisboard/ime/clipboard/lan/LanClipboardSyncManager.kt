/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.clipboard.lan

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val HEARTBEAT_INTERVAL_MS = 15_000L
private const val HEARTBEAT_TIMEOUT_MS = 45_000L
private const val BACKOFF_BASE_MS = 250L
private const val BACKOFF_CAP_MS = 5_000L
private const val MAX_TEXT_EVENT_CHARS = 262_144

class LanClipboardSyncManager(
    context: Context,
) : AutoCloseable {
    private val prefs by FlorisPreferenceStore
    private val appContext = context.applicationContext
    private val discovery = LanClipboardMdnsDiscovery(appContext)
    private val deviceId = buildLanClipboardDeviceId(appContext)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lifecycleJob: Job? = null
    private val wsClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    @Volatile
    private var activeWebSocket: WebSocket? = null

    private val _connectionStatusFlow = MutableStateFlow(LanClipboardConnectionStatus.Disabled)
    val connectionStatusFlow: StateFlow<LanClipboardConnectionStatus> = _connectionStatusFlow.asStateFlow()

    private val _activeEndpointFlow = MutableStateFlow<LanClipboardEndpoint?>(null)
    val activeEndpointFlow: StateFlow<LanClipboardEndpoint?> = _activeEndpointFlow.asStateFlow()

    val discoveredEndpointsFlow: StateFlow<List<LanClipboardEndpoint>> = discovery.endpointsFlow

    fun submitOutboundPrimaryClip(item: ClipboardItem?) {
        if (!prefs.clipboard.lanSyncEnabled.get() || item == null) {
            return
        }
        if (item.type != ItemType.TEXT || item.isRemoteDevice) {
            return
        }
        val text = item.text ?: return
        if (text.isBlank() || text.length > MAX_TEXT_EVENT_CHARS) {
            return
        }

        val socket = activeWebSocket ?: return
        val outboundEvent = LanClipboardProtocol.buildSetTextEvent(
            deviceId = deviceId,
            text = text,
            isSensitive = item.isSensitive,
        )
        val isSent = runCatching {
            socket.send(outboundEvent)
        }.getOrElse { false }
        if (!isSent) {
            detachActiveSocket(socket)
        }
    }

    fun initialize() {
        if (lifecycleJob != null) {
            return
        }
        lifecycleJob = scope.launch {
            combine(
                prefs.clipboard.lanSyncEnabled.asFlow(),
                prefs.clipboard.lanSyncEndpointMode.asFlow(),
                prefs.clipboard.lanSyncHost.asFlow(),
                prefs.clipboard.lanSyncPort.asFlow(),
                prefs.clipboard.lanSyncToken.asFlow(),
            ) { isEnabled, endpointMode, host, port, token ->
                LanRuntimeConfig(
                    isEnabled = isEnabled,
                    endpointMode = endpointMode,
                    host = host.trim(),
                    port = port.coerceIn(1, 65535),
                    token = token.trim(),
                    autoReconnect = true,
                )
            }
                .combine(prefs.clipboard.lanSyncAutoReconnect.asFlow()) { partialConfig, autoReconnect ->
                    partialConfig.copy(autoReconnect = autoReconnect)
                }
                .distinctUntilChanged()
                .collectLatest { config ->
                    runSessionLifecycle(config)
                }
        }
    }

    private suspend fun runSessionLifecycle(config: LanRuntimeConfig) {
        if (!config.isEnabled) {
            discovery.stop(resetError = true)
            activeWebSocket?.cancel()
            activeWebSocket = null
            _activeEndpointFlow.value = null
            _connectionStatusFlow.value = LanClipboardConnectionStatus.Disabled
            return
        }

        if (config.token.isBlank()) {
            discovery.stop(resetError = true)
            activeWebSocket?.cancel()
            activeWebSocket = null
            _activeEndpointFlow.value = null
            _connectionStatusFlow.value = LanClipboardConnectionStatus(
                state = LanClipboardConnectionState.ERROR,
                message = "Pairing token is missing",
            )
            return
        }

        if (config.endpointMode == LanClipboardEndpointMode.AUTO_DISCOVERY) {
            val discoveryStarted = discovery.start()
            if (!discoveryStarted && config.host.isBlank()) {
                activeWebSocket?.cancel()
                activeWebSocket = null
                _activeEndpointFlow.value = null
                _connectionStatusFlow.value = LanClipboardConnectionStatus(
                    state = LanClipboardConnectionState.ERROR,
                    message = discovery.lastErrorFlow.value ?: "Failed to start mDNS discovery",
                )
                return
            }
        } else {
            discovery.stop(resetError = true)
        }

        var retryAttempt = 0
        while (currentCoroutineContext().isActive) {
            val endpoint = selectEndpoint(config)
            if (endpoint == null) {
                val discoveryError = if (config.endpointMode == LanClipboardEndpointMode.AUTO_DISCOVERY) {
                    discovery.lastErrorFlow.value
                } else {
                    null
                }
                if (discoveryError != null && config.host.isBlank()) {
                    _activeEndpointFlow.value = null
                    _connectionStatusFlow.value = LanClipboardConnectionStatus(
                        state = LanClipboardConnectionState.ERROR,
                        message = discoveryError,
                    )
                    return
                }
                _activeEndpointFlow.value = null
                _connectionStatusFlow.value = LanClipboardConnectionStatus(
                    state = LanClipboardConnectionState.DISCOVERING,
                    message = "Waiting for LAN bridge discovery",
                )
                delay(1_000)
                continue
            }

            _activeEndpointFlow.value = endpoint
            _connectionStatusFlow.value = LanClipboardConnectionStatus(
                state = LanClipboardConnectionState.CONNECTING,
                endpoint = endpoint,
            )

            val sessionResult = connectAndRun(endpoint, config.token)
            if (!config.autoReconnect || !sessionResult.retryable) {
                _connectionStatusFlow.value = LanClipboardConnectionStatus(
                    state = LanClipboardConnectionState.ERROR,
                    endpoint = endpoint,
                    message = sessionResult.message,
                )
                return
            }

            retryAttempt += 1
            val backoffDelay = nextBackoffDelayMs(retryAttempt)
            _connectionStatusFlow.value = LanClipboardConnectionStatus(
                state = LanClipboardConnectionState.RECONNECTING,
                endpoint = endpoint,
                message = sessionResult.message,
                retryAttempt = retryAttempt,
            )
            delay(backoffDelay)
        }
    }

    private fun selectEndpoint(config: LanRuntimeConfig): LanClipboardEndpoint? {
        val manualEndpoint = config.host
            .takeIf { it.isNotBlank() }
            ?.let { host ->
                LanClipboardEndpoint(
                    host = host,
                    port = config.port,
                    path = LAN_CLIPBOARD_DEFAULT_PATH,
                    isManual = true,
                )
            }
        return when (config.endpointMode) {
            LanClipboardEndpointMode.MANUAL -> manualEndpoint
            LanClipboardEndpointMode.AUTO_DISCOVERY -> {
                discovery.endpointsFlow.value.firstOrNull() ?: manualEndpoint
            }
        }
    }

    private suspend fun connectAndRun(endpoint: LanClipboardEndpoint, token: String): SessionResult {
        return suspendCancellableCoroutine { continuation ->
            val isCompleted = AtomicBoolean(false)
            val lastPongAt = AtomicLong(System.currentTimeMillis())
            var heartbeatJob: Job? = null

            fun complete(result: SessionResult) {
                if (isCompleted.compareAndSet(false, true)) {
                    heartbeatJob?.cancel()
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }
            }

            val request = runCatching {
                Request.Builder()
                    .url(endpoint.websocketUrl())
                    .header("Authorization", "Bearer $token")
                    .header("X-Clipboard-Protocol-Version", LAN_CLIPBOARD_PROTOCOL_VERSION)
                    .header("X-Clipboard-Device-Id", deviceId)
                    .header("X-Clipboard-Source", LAN_CLIPBOARD_SOURCE_ANDROID)
                    .build()
            }.getOrElse {
                complete(SessionResult.fatal("Invalid LAN endpoint URL"))
                return@suspendCancellableCoroutine
            }

            lateinit var ws: WebSocket
            ws = wsClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    activeWebSocket = webSocket
                    lastPongAt.set(System.currentTimeMillis())
                    _connectionStatusFlow.value = LanClipboardConnectionStatus(
                        state = LanClipboardConnectionState.CONNECTED,
                        endpoint = endpoint,
                    )
                    heartbeatJob = scope.launch {
                        while (isActive) {
                            delay(HEARTBEAT_INTERVAL_MS)
                            val pingSent = webSocket.send(
                                LanClipboardProtocol.buildPingEvent(deviceId = deviceId),
                            )
                            if (!pingSent) {
                                complete(SessionResult.retryable("Failed to send heartbeat ping"))
                                webSocket.cancel()
                                return@launch
                            }
                            val pongDelay = System.currentTimeMillis() - lastPongAt.get()
                            if (pongDelay > HEARTBEAT_TIMEOUT_MS) {
                                complete(SessionResult.retryable("Heartbeat timeout"))
                                webSocket.cancel()
                                return@launch
                            }
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val envelope = LanClipboardProtocol.parseEnvelope(text) ?: return
                    when (LanClipboardProtocol.eventTypeOrNull(envelope)) {
                        "pong" -> {
                            lastPongAt.set(System.currentTimeMillis())
                        }
                        "ping" -> {
                            val nonce = LanClipboardProtocol.payloadOrNull(envelope)
                                ?.get("nonce")
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?: envelope["event_id"]?.jsonPrimitive?.contentOrNull
                                ?: ""
                            webSocket.send(LanClipboardProtocol.buildPongEvent(deviceId, nonce))
                        }
                        "error" -> {
                            val errorCode = LanClipboardProtocol.payloadOrNull(envelope)
                                ?.get("code")
                                ?.jsonPrimitive
                                ?.contentOrNull
                            val errorMessage = LanClipboardProtocol.payloadOrNull(envelope)
                                ?.get("message")
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?: "Received remote error event"
                            if (errorCode == "AUTH_FAILURE" || errorCode == "VERSION_MISMATCH") {
                                complete(SessionResult.fatal(errorMessage))
                                webSocket.cancel()
                            }
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    detachActiveSocket(webSocket)
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    detachActiveSocket(webSocket)
                    complete(SessionResult.retryable("Socket closed ($code): $reason"))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    detachActiveSocket(webSocket)
                    val message = when {
                        response != null -> "WebSocket handshake failed (${response.code})"
                        else -> (t.message ?: "WebSocket failure")
                    }
                    val isFatal = response?.code?.let { it in 400..499 } == true
                    complete(
                        if (isFatal) {
                            SessionResult.fatal(message)
                        } else {
                            SessionResult.retryable(message)
                        }
                    )
                }
            })

            continuation.invokeOnCancellation {
                heartbeatJob?.cancel()
                detachActiveSocket(ws)
                ws.cancel()
            }
        }
    }

    private fun detachActiveSocket(socket: WebSocket) {
        if (activeWebSocket === socket) {
            activeWebSocket = null
        }
    }

    private fun nextBackoffDelayMs(retryAttempt: Int): Long {
        val exponential = BACKOFF_BASE_MS * (1L shl retryAttempt.coerceAtMost(8))
        val bounded = exponential.coerceAtMost(BACKOFF_CAP_MS)
        return Random.nextLong(until = bounded + 1)
    }

    override fun close() {
        lifecycleJob?.cancel()
        lifecycleJob = null
        activeWebSocket?.cancel()
        activeWebSocket = null
        discovery.close()
        scope.coroutineContext.cancelChildren()
    }
}

private data class LanRuntimeConfig(
    val isEnabled: Boolean,
    val endpointMode: LanClipboardEndpointMode,
    val host: String,
    val port: Int,
    val token: String,
    val autoReconnect: Boolean,
)

private data class SessionResult(
    val retryable: Boolean,
    val message: String,
) {
    companion object {
        fun retryable(message: String) = SessionResult(retryable = true, message = message)
        fun fatal(message: String) = SessionResult(retryable = false, message = message)
    }
}
