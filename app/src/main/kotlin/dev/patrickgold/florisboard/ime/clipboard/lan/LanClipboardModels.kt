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

enum class LanClipboardConnectionState {
    DISABLED,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR,
}

data class LanClipboardConnectionStatus(
    val state: LanClipboardConnectionState,
    val endpoint: LanClipboardEndpoint? = null,
    val message: String? = null,
    val retryAttempt: Int = 0,
) {
    companion object {
        val Disabled = LanClipboardConnectionStatus(state = LanClipboardConnectionState.DISABLED)
    }
}

data class LanClipboardEndpoint(
    val host: String,
    val port: Int,
    val path: String = LAN_CLIPBOARD_DEFAULT_PATH,
    val serviceName: String? = null,
    val serviceId: String? = null,
    val protocolVersion: String? = null,
    val discoveredAtMs: Long = System.currentTimeMillis(),
    val isManual: Boolean = false,
) {
    fun websocketUrl(): String {
        val hostForUrl = when {
            host.contains(':') && !host.startsWith("[") -> "[$host]"
            else -> host
        }
        val normalizedPath = when {
            path.isBlank() -> LAN_CLIPBOARD_DEFAULT_PATH
            path.startsWith('/') -> path
            else -> "/$path"
        }
        return "ws://$hostForUrl:$port$normalizedPath"
    }

    fun displayAddress(): String = "$host:$port"
}

