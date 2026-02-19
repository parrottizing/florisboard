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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.florisboard.lib.android.AndroidKeyguardManager
import org.florisboard.lib.android.systemService
import org.florisboard.lib.android.systemServiceOrNull

private const val HEARTBEAT_INTERVAL_MS = 15_000L
private const val HEARTBEAT_TIMEOUT_MS = 45_000L
private const val BACKOFF_BASE_MS = 250L
private const val BACKOFF_CAP_MS = 5_000L
private const val MAX_TEXT_EVENT_CHARS = 262_144
private const val STALE_EVENT_WINDOW_MS = 120_000L
private const val DISCONNECTED_REASON_IME_HIDDEN = "Open FlorisBoard keyboard to reconnect"
private const val DISCONNECTED_REASON_NETWORK_UNAVAILABLE = "No active network available"
private const val DISCONNECTED_REASON_DEVICE_LOCKED = "Device is locked"
private const val DISCONNECTED_REASON_MANUAL_HOST_MISSING = "Manual host is missing"

class LanClipboardSyncManager(
    context: Context,
) : AutoCloseable {
    private val prefs by FlorisPreferenceStore
    private val appContext = context.applicationContext
    private val discovery = LanClipboardMdnsDiscovery(appContext)
    private val deviceId = buildLanClipboardDeviceId(appContext)
    private val connectivityManager = appContext.systemService(ConnectivityManager::class)
    private val keyguardManager = appContext.systemServiceOrNull(AndroidKeyguardManager::class)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lifecycleJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    private val wsClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    @Volatile
    private var activeWebSocket: WebSocket? = null
    private val runtimeStateFlow = MutableStateFlow(
        LanRuntimeState(
            imeWindowVisible = false,
            networkAvailable = hasActiveNetworkConnection(),
            deviceUnlocked = isDeviceUnlocked(),
        ),
    )
    private val manualReconnectSignal = MutableStateFlow(0L)
    @Volatile
    private var lastProcessedManualReconnectGeneration = 0L
    @Volatile
    private var lastObservedLanEnabledState = prefs.clipboard.lanSyncEnabled.get()

    private val _connectionStatusFlow = MutableStateFlow(LanClipboardConnectionStatus.Disabled)
    val connectionStatusFlow: StateFlow<LanClipboardConnectionStatus> = _connectionStatusFlow.asStateFlow()

    private val _activeEndpointFlow = MutableStateFlow<LanClipboardEndpoint?>(null)
    val activeEndpointFlow: StateFlow<LanClipboardEndpoint?> = _activeEndpointFlow.asStateFlow()

    val discoveredEndpointsFlow: StateFlow<List<LanClipboardEndpoint>> = discovery.endpointsFlow
    private val inboundDedupeCache = LanClipboardInboundDedupeCache()
    @Volatile
    private var inboundTextHandler: ((text: String, isSensitive: Boolean) -> Boolean)? = null

    fun setInboundTextHandler(handler: ((text: String, isSensitive: Boolean) -> Boolean)?) {
        inboundTextHandler = handler
    }

    fun updateImeWindowVisibility(isVisible: Boolean) {
        runtimeStateFlow.update { state ->
            if (state.imeWindowVisible == isVisible) {
                state
            } else {
                state.copy(imeWindowVisible = isVisible)
            }
        }
        if (isVisible) {
            refreshDeviceUnlockedState()
            refreshNetworkAvailability()
        }
    }

    fun requestManualReconnect() {
        refreshDeviceUnlockedState()
        refreshNetworkAvailability()
        manualReconnectSignal.update { it + 1L }
    }

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
        val outboundPayloadHash = LanClipboardProtocol.computePayloadHash(
            buildTextPayload(text = text, isSensitive = item.isSensitive),
        )
        if (inboundDedupeCache.hasRecentPayloadHash(LAN_CLIPBOARD_SOURCE_MAC, outboundPayloadHash)) {
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
        registerRuntimeObservers()
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
                    imeWindowVisible = false,
                    networkAvailable = true,
                    deviceUnlocked = true,
                    reconnectGeneration = 0L,
                )
            }
                .combine(prefs.clipboard.lanSyncAutoReconnect.asFlow()) { partialConfig, autoReconnect ->
                    partialConfig.copy(autoReconnect = autoReconnect)
                }
                .combine(runtimeStateFlow) { partialConfig, runtimeState ->
                    partialConfig.copy(
                        imeWindowVisible = runtimeState.imeWindowVisible,
                        networkAvailable = runtimeState.networkAvailable,
                        deviceUnlocked = runtimeState.deviceUnlocked,
                    )
                }
                .combine(manualReconnectSignal) { partialConfig, reconnectGeneration ->
                    partialConfig.copy(reconnectGeneration = reconnectGeneration)
                }
                .distinctUntilChanged()
                .collectLatest { config ->
                    runSessionLifecycle(config)
                }
        }
    }

    private suspend fun runSessionLifecycle(config: LanRuntimeConfig) {
        val isEnableTransition = consumeEnableTransition(config.isEnabled)
        if (!config.isEnabled) {
            stopSession(resetDiscovery = true)
            _connectionStatusFlow.value = LanClipboardConnectionStatus.Disabled
            return
        }

        if (config.token.isBlank()) {
            stopSession(resetDiscovery = true)
            _connectionStatusFlow.value = LanClipboardConnectionStatus(
                state = LanClipboardConnectionState.ERROR,
                message = "Pairing token is missing",
            )
            return
        }

        val allowImeHiddenBypass = shouldBypassImeVisibilityGate(
            config = config,
            isEnableTransition = isEnableTransition,
        )
        val disconnectedReason = disconnectedReason(
            config = config,
            allowImeHiddenBypass = allowImeHiddenBypass,
        )
        if (disconnectedReason != null) {
            stopSession(resetDiscovery = true)
            _connectionStatusFlow.value = LanClipboardConnectionStatus(
                state = LanClipboardConnectionState.DISCONNECTED,
                message = disconnectedReason,
            )
            return
        }

        if (config.endpointMode == LanClipboardEndpointMode.AUTO_DISCOVERY) {
            val discoveryStarted = discovery.start()
            if (!discoveryStarted && config.host.isBlank()) {
                stopSession(resetDiscovery = true)
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
                if (config.endpointMode == LanClipboardEndpointMode.MANUAL) {
                    _activeEndpointFlow.value = null
                    _connectionStatusFlow.value = LanClipboardConnectionStatus(
                        state = LanClipboardConnectionState.DISCONNECTED,
                        message = DISCONNECTED_REASON_MANUAL_HOST_MISSING,
                    )
                    return
                }
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
                        "set_text" -> {
                            handleInboundSetText(webSocket, envelope)
                        }
                        "set_image" -> {
                            val eventId = envelope["event_id"]?.jsonPrimitive?.contentOrNull
                            if (!eventId.isNullOrBlank()) {
                                sendRejectedWithError(
                                    webSocket = webSocket,
                                    eventId = eventId,
                                    code = "UNSUPPORTED_TYPE",
                                    message = "set_image is disabled in v1 mode",
                                )
                            } else {
                                webSocket.send(
                                    LanClipboardProtocol.buildErrorEvent(
                                        deviceId = deviceId,
                                        code = "UNSUPPORTED_TYPE",
                                        message = "set_image is disabled in v1 mode",
                                    ),
                                )
                            }
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

    private fun handleInboundSetText(webSocket: WebSocket, envelope: JsonObject) {
        val nowMs = System.currentTimeMillis()
        val eventId = envelope["event_id"]?.jsonPrimitive?.contentOrNull
        if (eventId.isNullOrBlank()) {
            webSocket.send(
                LanClipboardProtocol.buildErrorEvent(
                    deviceId = deviceId,
                    code = "BAD_MESSAGE",
                    message = "set_text event is missing event_id",
                ),
            )
            return
        }

        val protocolVersion = envelope["protocol_version"]?.jsonPrimitive?.contentOrNull
        if (!LanClipboardProtocol.isSupportedProtocolVersion(protocolVersion)) {
            sendRejectedWithError(
                webSocket = webSocket,
                eventId = eventId,
                code = "VERSION_MISMATCH",
                message = "Unsupported protocol_version for set_text event",
            )
            return
        }

        val source = envelope["source"]?.jsonPrimitive?.contentOrNull
        if (!LanClipboardProtocol.isSupportedSource(source)) {
            sendRejectedWithError(
                webSocket = webSocket,
                eventId = eventId,
                code = "BAD_MESSAGE",
                message = "set_text event source must be android or mac",
            )
            return
        }
        val normalizedSource = source ?: return

        val payloadHash = envelope["payload_hash"]?.jsonPrimitive?.contentOrNull
        if (payloadHash.isNullOrBlank()) {
            sendRejectedWithError(
                webSocket = webSocket,
                eventId = eventId,
                code = "BAD_MESSAGE",
                message = "set_text event is missing payload_hash",
            )
            return
        }

        if (inboundDedupeCache.hasSeenEventId(normalizedSource, eventId, nowMs)) {
            sendAck(webSocket, eventId = eventId, status = "duplicate")
            return
        }

        val createdAtMs = envelope["created_at_ms"]?.jsonPrimitive?.longOrNull
        if (createdAtMs == null) {
            sendRejectedWithError(
                webSocket = webSocket,
                eventId = eventId,
                code = "BAD_MESSAGE",
                message = "set_text event has invalid created_at_ms",
            )
            return
        }
        if (abs(nowMs - createdAtMs) > STALE_EVENT_WINDOW_MS) {
            sendRejectedWithError(
                webSocket = webSocket,
                eventId = eventId,
                code = "STALE_EVENT",
                message = "set_text event is outside the stale window",
            )
            return
        }

        val payload = LanClipboardProtocol.payloadOrNull(envelope)
        if (payload == null) {
            sendRejectedWithError(
                webSocket = webSocket,
                eventId = eventId,
                code = "BAD_MESSAGE",
                message = "set_text event is missing payload",
            )
            return
        }

        val computedPayloadHash = LanClipboardProtocol.computePayloadHash(payload)
        if (!payloadHash.equals(computedPayloadHash, ignoreCase = false)) {
            sendRejectedWithError(
                webSocket = webSocket,
                eventId = eventId,
                code = "HASH_MISMATCH",
                message = "payload_hash does not match payload",
            )
            return
        }

        val mimeType = payload["mime_type"]?.jsonPrimitive?.contentOrNull
        if (mimeType != "text/plain") {
            sendRejectedWithError(
                webSocket = webSocket,
                eventId = eventId,
                code = "UNSUPPORTED_TYPE",
                message = "set_text payload mime_type must be text/plain",
            )
            return
        }

        val text = payload["text"]?.jsonPrimitive?.contentOrNull
        if (text == null) {
            sendRejectedWithError(
                webSocket = webSocket,
                eventId = eventId,
                code = "BAD_MESSAGE",
                message = "set_text payload text must be a string",
            )
            return
        }
        if (text.length > MAX_TEXT_EVENT_CHARS) {
            sendRejectedWithError(
                webSocket = webSocket,
                eventId = eventId,
                code = "PAYLOAD_TOO_LARGE",
                message = "set_text payload exceeds maximum supported size",
            )
            return
        }

        val isSensitiveElement = payload["is_sensitive"]?.jsonPrimitive
        val isSensitive = if (isSensitiveElement == null) {
            false
        } else {
            isSensitiveElement.booleanOrNull ?: run {
                sendRejectedWithError(
                    webSocket = webSocket,
                    eventId = eventId,
                    code = "BAD_MESSAGE",
                    message = "set_text payload is_sensitive must be boolean",
                )
                return
            }
        }

        if (inboundDedupeCache.hasRecentPayloadHash(normalizedSource, payloadHash, nowMs)) {
            inboundDedupeCache.record(normalizedSource, eventId, payloadHash, nowMs)
            sendAck(webSocket, eventId = eventId, status = "duplicate")
            return
        }

        val applied = runCatching {
            inboundTextHandler?.invoke(text, isSensitive) ?: false
        }.getOrDefault(false)
        if (!applied) {
            sendRejectedWithError(
                webSocket = webSocket,
                eventId = eventId,
                code = "TEMPORARY_UNAVAILABLE",
                message = "Failed applying inbound clipboard text",
                retryable = true,
            )
            return
        }

        inboundDedupeCache.record(normalizedSource, eventId, payloadHash, nowMs)
        sendAck(webSocket, eventId = eventId, status = "accepted")
    }

    private fun sendAck(webSocket: WebSocket, eventId: String, status: String, errorCode: String? = null) {
        webSocket.send(
            LanClipboardProtocol.buildAckEvent(
                deviceId = deviceId,
                ackedEventId = eventId,
                status = status,
                errorCode = errorCode,
            ),
        )
    }

    private fun sendRejectedWithError(
        webSocket: WebSocket,
        eventId: String,
        code: String,
        message: String,
        retryable: Boolean = false,
    ) {
        sendAck(webSocket, eventId = eventId, status = "rejected", errorCode = code)
        webSocket.send(
            LanClipboardProtocol.buildErrorEvent(
                deviceId = deviceId,
                code = code,
                message = message,
                retryable = retryable,
            ),
        )
    }

    private fun registerRuntimeObservers() {
        refreshDeviceUnlockedState()
        refreshNetworkAvailability()
        registerScreenStateReceiver()
        registerNetworkCallback()
    }

    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) {
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF,
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT,
                    Intent.ACTION_USER_UNLOCKED,
                    -> {
                        refreshDeviceUnlockedState()
                        refreshNetworkAvailability()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_USER_UNLOCKED)
        }
        val didRegister = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
        }.isSuccess
        if (didRegister) {
            screenStateReceiver = receiver
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) {
            return
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshNetworkAvailability()
            }

            override fun onLost(network: Network) {
                refreshNetworkAvailability()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                refreshNetworkAvailability()
            }

            override fun onUnavailable() {
                refreshNetworkAvailability()
            }
        }
        val didRegister = runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
        }.isSuccess
        if (didRegister) {
            networkCallback = callback
        }
    }

    private fun unregisterRuntimeObservers() {
        networkCallback?.let { callback ->
            runCatching {
                connectivityManager.unregisterNetworkCallback(callback)
            }
        }
        networkCallback = null
        screenStateReceiver?.let { receiver ->
            runCatching {
                appContext.unregisterReceiver(receiver)
            }
        }
        screenStateReceiver = null
    }

    private fun refreshNetworkAvailability() {
        val isAvailable = hasActiveNetworkConnection()
        runtimeStateFlow.update { state ->
            if (state.networkAvailable == isAvailable) {
                state
            } else {
                state.copy(networkAvailable = isAvailable)
            }
        }
    }

    private fun refreshDeviceUnlockedState() {
        val isUnlocked = isDeviceUnlocked()
        runtimeStateFlow.update { state ->
            if (state.deviceUnlocked == isUnlocked) {
                state
            } else {
                state.copy(deviceUnlocked = isUnlocked)
            }
        }
    }

    private fun hasActiveNetworkConnection(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        return connectivityManager.getNetworkCapabilities(activeNetwork) != null
    }

    private fun isDeviceUnlocked(): Boolean {
        val manager = keyguardManager ?: return true
        return !manager.isDeviceLocked && !manager.isKeyguardLocked
    }

    private fun consumeEnableTransition(isEnabled: Boolean): Boolean {
        val isEnableTransition = isEnabled && !lastObservedLanEnabledState
        lastObservedLanEnabledState = isEnabled
        return isEnableTransition
    }

    private fun shouldBypassImeVisibilityGate(
        config: LanRuntimeConfig,
        isEnableTransition: Boolean,
    ): Boolean {
        val reconnectGeneration = config.reconnectGeneration
        val isManualReconnect = if (reconnectGeneration > lastProcessedManualReconnectGeneration) {
            lastProcessedManualReconnectGeneration = reconnectGeneration
            true
        } else {
            false
        }
        return isManualReconnect || isEnableTransition
    }

    private fun disconnectedReason(
        config: LanRuntimeConfig,
        allowImeHiddenBypass: Boolean,
    ): String? {
        return when {
            !config.deviceUnlocked -> DISCONNECTED_REASON_DEVICE_LOCKED
            !config.networkAvailable -> DISCONNECTED_REASON_NETWORK_UNAVAILABLE
            !config.imeWindowVisible && !allowImeHiddenBypass -> DISCONNECTED_REASON_IME_HIDDEN
            else -> null
        }
    }

    private fun stopSession(resetDiscovery: Boolean) {
        if (resetDiscovery) {
            discovery.stop(resetError = true)
        }
        activeWebSocket?.cancel()
        activeWebSocket = null
        _activeEndpointFlow.value = null
    }

    private fun buildTextPayload(text: String, isSensitive: Boolean): JsonObject {
        return buildJsonObject {
            put("mime_type", JsonPrimitive("text/plain"))
            put("text", JsonPrimitive(text))
            put("is_sensitive", JsonPrimitive(isSensitive))
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
        stopSession(resetDiscovery = false)
        unregisterRuntimeObservers()
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
    val imeWindowVisible: Boolean,
    val networkAvailable: Boolean,
    val deviceUnlocked: Boolean,
    val reconnectGeneration: Long,
)

private data class LanRuntimeState(
    val imeWindowVisible: Boolean,
    val networkAvailable: Boolean,
    val deviceUnlocked: Boolean,
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
