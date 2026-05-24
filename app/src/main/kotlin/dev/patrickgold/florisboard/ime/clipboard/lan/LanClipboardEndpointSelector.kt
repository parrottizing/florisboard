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

internal fun selectLanClipboardEndpoint(
    endpointMode: LanClipboardEndpointMode,
    host: String,
    port: Int,
    preferredServiceId: String,
    discoveredEndpoints: List<LanClipboardEndpoint>,
): LanClipboardEndpoint? {
    val normalizedHost = host.trim()
    val normalizedServiceId = preferredServiceId.trim()
    val manualEndpoint = normalizedHost
        .takeIf { it.isNotBlank() }
        ?.let {
            LanClipboardEndpoint(
                host = it,
                port = port,
                path = LAN_CLIPBOARD_DEFAULT_PATH,
                isManual = true,
            )
        }

    return when (endpointMode) {
        LanClipboardEndpointMode.MANUAL -> manualEndpoint
        LanClipboardEndpointMode.AUTO_DISCOVERY -> {
            val preferredDiscovered = if (normalizedServiceId.isBlank()) {
                discoveredEndpoints.firstOrNull()
            } else {
                discoveredEndpoints.firstOrNull { it.serviceId == normalizedServiceId }
                    ?: discoveredEndpoints.firstOrNull()
            }
            preferredDiscovered ?: if (normalizedServiceId.isBlank()) manualEndpoint else null
        }
    }
}

internal fun preferredLanClipboardServiceIdUpdate(
    connectedEndpoint: LanClipboardEndpoint,
    currentPreferredServiceId: String,
): String? {
    if (connectedEndpoint.isManual) {
        return null
    }

    val normalizedConnectedServiceId = connectedEndpoint.serviceId?.trim().orEmpty()
    if (normalizedConnectedServiceId.isBlank()) {
        return null
    }

    val normalizedCurrentServiceId = currentPreferredServiceId.trim()
    return normalizedConnectedServiceId.takeUnless { it == normalizedCurrentServiceId }
}
