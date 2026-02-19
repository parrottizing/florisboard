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

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

private const val LAN_CLIPBOARD_PAIRING_DEEP_LINK_SCHEME = "ui"
private const val LAN_CLIPBOARD_PAIRING_DEEP_LINK_HOST = "florisboard"
private const val LAN_CLIPBOARD_PAIRING_DEEP_LINK_PATH = "/settings/clipboard"
private const val LAN_CLIPBOARD_PAIRING_QUERY_VERSION = "lan_pair_version"
private const val LAN_CLIPBOARD_PAIRING_QUERY_HOST = "lan_pair_host"
private const val LAN_CLIPBOARD_PAIRING_QUERY_PORT = "lan_pair_port"
private const val LAN_CLIPBOARD_PAIRING_QUERY_CODE = "lan_pair_code"
private const val LAN_CLIPBOARD_PAIRING_QUERY_REDEEM_PATH = "lan_pair_redeem_path"
private const val LAN_CLIPBOARD_PAIRING_SUPPORTED_VERSION = "1"
private const val LAN_CLIPBOARD_PAIRING_DEFAULT_REDEEM_PATH = "/v1/pair/redeem"

private val pairingJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private val pairingHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()
}

data class LanClipboardPairingOffer(
    val code: String,
    val host: String,
    val port: Int,
    val redeemPath: String,
)

data class LanClipboardPairingCredentials(
    val token: String,
    val host: String,
    val port: Int,
    val websocketPath: String,
)

fun parseLanClipboardPairingOffer(uri: Uri): LanClipboardPairingOffer? {
    if (uri.scheme != LAN_CLIPBOARD_PAIRING_DEEP_LINK_SCHEME || uri.host != LAN_CLIPBOARD_PAIRING_DEEP_LINK_HOST) {
        return null
    }
    val normalizedPath = uri.path?.trimEnd('/')?.ifBlank { "/" }
    if (normalizedPath != LAN_CLIPBOARD_PAIRING_DEEP_LINK_PATH) {
        return null
    }
    val version = uri.getQueryParameter(LAN_CLIPBOARD_PAIRING_QUERY_VERSION)?.trim()
    if (version != LAN_CLIPBOARD_PAIRING_SUPPORTED_VERSION) {
        return null
    }

    val code = uri.getQueryParameter(LAN_CLIPBOARD_PAIRING_QUERY_CODE)?.trim().orEmpty()
    if (code.isBlank()) {
        return null
    }

    val host = sanitizePairingHost(uri.getQueryParameter(LAN_CLIPBOARD_PAIRING_QUERY_HOST).orEmpty())
    if (host.isBlank()) {
        return null
    }

    val rawPort = uri.getQueryParameter(LAN_CLIPBOARD_PAIRING_QUERY_PORT)
    val port = rawPort?.toIntOrNull() ?: LAN_CLIPBOARD_DEFAULT_PORT
    if (port !in 1..65535) {
        return null
    }

    val redeemPath = normalizePairingPath(uri.getQueryParameter(LAN_CLIPBOARD_PAIRING_QUERY_REDEEM_PATH))

    return LanClipboardPairingOffer(
        code = code,
        host = host,
        port = port,
        redeemPath = redeemPath,
    )
}

suspend fun redeemLanClipboardPairingOffer(
    offer: LanClipboardPairingOffer,
    deviceId: String,
    httpClient: OkHttpClient = pairingHttpClient,
): Result<LanClipboardPairingCredentials> = withContext(Dispatchers.IO) {
    runCatching {
        val url = buildPairingRedeemHttpUrl(offer, deviceId)
            ?: throw IllegalArgumentException("Invalid pairing offer endpoint")

        val request = Request.Builder()
            .get()
            .url(url)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val failureMessage = when (response.code) {
                    400 -> "Pairing data is invalid. Scan a fresh QR code."
                    404 -> "Pairing endpoint is unavailable on bridge."
                    410 -> "Pairing code expired or already used. Scan a fresh QR code."
                    else -> "Pairing redeem failed (${response.code})"
                }
                throw IllegalStateException(failureMessage)
            }

            val responseBody = response.body.string()
            val payload = pairingJson.parseToJsonElement(responseBody).jsonObject

            val token = payload["token"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (token.isBlank()) {
                throw IllegalStateException("Pairing response is missing token")
            }

            val host = sanitizePairingHost(
                payload["host"]?.jsonPrimitive?.contentOrNull ?: offer.host,
            )
                .ifBlank { offer.host }
            val port = payload["port"]?.jsonPrimitive?.intOrNull
                ?.takeIf { it in 1..65535 }
                ?: offer.port
            val websocketPath = normalizeWebsocketPath(
                payload["ws_path"]?.jsonPrimitive?.contentOrNull,
            )

            LanClipboardPairingCredentials(
                token = token,
                host = host,
                port = port,
                websocketPath = websocketPath,
            )
        }
    }
}

private fun buildPairingRedeemHttpUrl(
    offer: LanClipboardPairingOffer,
    deviceId: String,
): HttpUrl? {
    val host = sanitizePairingHost(offer.host)
    if (host.isBlank() || offer.port !in 1..65535) {
        return null
    }

    val builder = runCatching {
        HttpUrl.Builder()
            .scheme("http")
            .host(host)
            .port(offer.port)
    }.getOrNull() ?: return null

    val pathSegments = offer.redeemPath
        .trim()
        .trim('/')
        .split('/')
        .filter { it.isNotBlank() }
    pathSegments.forEach(builder::addPathSegment)

    builder.addQueryParameter("code", offer.code)
    builder.addQueryParameter("source", LAN_CLIPBOARD_SOURCE_ANDROID)
    builder.addQueryParameter("device_id", deviceId)

    return runCatching { builder.build() }.getOrNull()
}

internal fun sanitizePairingHost(rawHost: String): String {
    return rawHost
        .trim()
        .removePrefix("[")
        .removeSuffix("]")
        .trim()
}

internal fun normalizePairingPath(path: String?): String {
    val normalized = path
        ?.trim()
        ?.ifBlank { null }
        ?: LAN_CLIPBOARD_PAIRING_DEFAULT_REDEEM_PATH
    return if (normalized.startsWith('/')) normalized else "/$normalized"
}

internal fun normalizeWebsocketPath(path: String?): String {
    val normalized = path?.trim().orEmpty()
    if (normalized.isBlank()) {
        return LAN_CLIPBOARD_DEFAULT_PATH
    }
    return if (normalized.startsWith('/')) normalized else "/$normalized"
}
