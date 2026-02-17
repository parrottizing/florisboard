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
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.florisboard.lib.android.systemService

class LanClipboardMdnsDiscovery(context: Context) : AutoCloseable {
    private val nsdManager = context.systemService(NsdManager::class)
    private val endpointsByKey = linkedMapOf<String, LanClipboardEndpoint>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val guard = Any()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _lastErrorFlow = MutableStateFlow<String?>(null)
    val lastErrorFlow: StateFlow<String?> = _lastErrorFlow.asStateFlow()

    private val _endpointsFlow = MutableStateFlow<List<LanClipboardEndpoint>>(emptyList())
    val endpointsFlow: StateFlow<List<LanClipboardEndpoint>> = _endpointsFlow.asStateFlow()

    fun start(): Boolean {
        synchronized(guard) {
            _lastErrorFlow.value = null
            if (discoveryListener != null) {
                return true
            }
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    synchronized(guard) {
                        _lastErrorFlow.value = "mDNS discovery failed to start (code $errorCode)"
                    }
                    stop(resetError = false)
                }

                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    synchronized(guard) {
                        _lastErrorFlow.value = "mDNS discovery stopped unexpectedly (code $errorCode)"
                    }
                    stop(resetError = false)
                }

                override fun onDiscoveryStarted(serviceType: String?) {
                    _isDiscovering.value = true
                    _lastErrorFlow.value = null
                }

                override fun onDiscoveryStopped(serviceType: String?) {
                    _isDiscovering.value = false
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!serviceInfo.serviceType.contains(LAN_CLIPBOARD_MDNS_SERVICE_TYPE)) {
                        return
                    }
                    resolveService(serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    synchronized(guard) {
                        val endpointKey = endpointsByKey.entries.firstOrNull { (_, endpoint) ->
                            endpoint.serviceName == serviceInfo.serviceName
                        }?.key
                        if (endpointKey != null) {
                            endpointsByKey.remove(endpointKey)
                        } else {
                            endpointsByKey.remove(serviceInfo.serviceName)
                        }
                        publishLocked()
                    }
                }
            }

            val started = runCatching {
                nsdManager.discoverServices(
                    LAN_CLIPBOARD_MDNS_SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener,
                )
            }
                .onFailure {
                    _lastErrorFlow.value = "Failed to start mDNS discovery"
                }
                .isSuccess
            if (started) {
                discoveryListener = listener
            } else {
                _isDiscovering.value = false
            }
            return started
        }
    }

    fun stop(resetError: Boolean = true) {
        synchronized(guard) {
            val listener = discoveryListener
            discoveryListener = null
            if (listener != null) {
                runCatching {
                    nsdManager.stopServiceDiscovery(listener)
                }
            }
            endpointsByKey.clear()
            publishLocked()
            _isDiscovering.value = false
            if (resetError) {
                _lastErrorFlow.value = null
            }
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Resolve can fail transiently during regular LAN changes.
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val hostName = serviceInfo.host
                    ?.hostName
                    ?.trim()
                    ?.trimEnd('.')
                    ?.takeIf { it.isNotBlank() }

                val hostAddress = serviceInfo.host?.hostAddress
                    ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        serviceInfo.hostAddresses
                            .firstOrNull()
                            ?.hostAddress
                    } else {
                        null
                    }
                val host = hostName ?: hostAddress ?: return

                val attributes = serviceInfo.attributes
                    .mapValues { (_, value) ->
                        value?.let { String(it, Charsets.UTF_8) }
                    }
                val path = attributes["path"]
                    ?.takeIf { !it.isNullOrBlank() }
                    ?: LAN_CLIPBOARD_DEFAULT_PATH
                val serviceId = attributes["service_id"]
                val key = serviceId ?: serviceInfo.serviceName
                synchronized(guard) {
                    endpointsByKey[key] = LanClipboardEndpoint(
                        host = host,
                        port = serviceInfo.port,
                        path = path,
                        serviceName = serviceInfo.serviceName,
                        serviceId = serviceId,
                        protocolVersion = attributes["protocol"],
                        discoveredAtMs = System.currentTimeMillis(),
                        isManual = false,
                    )
                    publishLocked()
                }
            }
        }
        runCatching {
            nsdManager.resolveService(serviceInfo, resolveListener)
        }
    }

    private fun publishLocked() {
        _endpointsFlow.value = endpointsByKey.values
            .sortedWith(
                compareByDescending<LanClipboardEndpoint> { it.discoveredAtMs }
                    .thenBy { it.serviceName ?: "" }
            )
    }

    override fun close() {
        stop()
    }
}
