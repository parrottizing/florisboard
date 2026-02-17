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
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val LAN_CLIPBOARD_PROTOCOL_VERSION = "1.0"
const val LAN_CLIPBOARD_SOURCE_ANDROID = "android"
const val LAN_CLIPBOARD_DEFAULT_PATH = "/v1/clipboard"
const val LAN_CLIPBOARD_DEFAULT_PORT = 8765
const val LAN_CLIPBOARD_MDNS_SERVICE_TYPE = "_gumlet-clipboard._tcp"

private val compactJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    prettyPrint = false
}

object LanClipboardProtocol {
    fun buildPingEvent(deviceId: String): String {
        val payload = buildJsonObject {
            put("nonce", JsonPrimitive(UUID.randomUUID().toString()))
        }
        return buildEvent(
            deviceId = deviceId,
            eventType = "ping",
            payload = payload,
        )
    }

    fun buildPongEvent(deviceId: String, nonce: String): String {
        val payload = buildJsonObject {
            put("nonce", JsonPrimitive(nonce))
        }
        return buildEvent(
            deviceId = deviceId,
            eventType = "pong",
            payload = payload,
        )
    }

    fun buildSetTextEvent(
        deviceId: String,
        text: String,
        isSensitive: Boolean,
    ): String {
        val payload = buildJsonObject {
            put("mime_type", JsonPrimitive("text/plain"))
            put("text", JsonPrimitive(text))
            put("is_sensitive", JsonPrimitive(isSensitive))
        }
        return buildEvent(
            deviceId = deviceId,
            eventType = "set_text",
            payload = payload,
        )
    }

    fun buildEvent(
        deviceId: String,
        eventType: String,
        payload: JsonObject,
    ): String {
        val payloadHash = computePayloadHash(payload)
        val event = buildJsonObject {
            put("protocol_version", JsonPrimitive(LAN_CLIPBOARD_PROTOCOL_VERSION))
            put("device_id", JsonPrimitive(deviceId))
            put("event_id", JsonPrimitive(UUID.randomUUID().toString()))
            put("source", JsonPrimitive(LAN_CLIPBOARD_SOURCE_ANDROID))
            put("event_type", JsonPrimitive(eventType))
            put("created_at_ms", JsonPrimitive(System.currentTimeMillis()))
            put("payload_hash", JsonPrimitive(payloadHash))
            put("payload", payload)
        }
        return compactJson.encodeToString(event)
    }

    fun parseEnvelope(rawMessage: String): JsonObject? {
        return runCatching {
            compactJson.parseToJsonElement(rawMessage).jsonObject
        }.getOrNull()
    }

    fun eventTypeOrNull(message: JsonObject): String? {
        return message["event_type"]?.jsonPrimitive?.contentOrNull
    }

    fun payloadOrNull(message: JsonObject): JsonObject? {
        return message["payload"]?.jsonObject
    }

    fun computePayloadHash(payload: JsonObject): String {
        val canonicalPayload = canonicalPayloadJson(payload)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalPayload.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }
}

fun buildLanClipboardDeviceId(context: Context): String {
    val modelPart = "${Build.MANUFACTURER}.${Build.MODEL}"
        .lowercase()
        .replace(Regex("[^a-z0-9._:-]+"), "-")
        .trim('-')
        .ifBlank { "device" }
    val androidId = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull()
    val suffix = androidId?.take(12)?.ifBlank { null }
        ?: UUID.randomUUID().toString().replace("-", "").take(12)
    return "android.$modelPart.$suffix".take(128)
}

private fun canonicalPayloadJson(payload: JsonObject): String {
    val canonicalPayload = canonicalize(payload)
    return compactJson.encodeToString(canonicalPayload)
}

private fun canonicalize(element: JsonElement): JsonElement {
    return when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedBy { it.key }
                .associate { (key, value) -> key to canonicalize(value) }
        )
        is JsonArray -> JsonArray(element.map { canonicalize(it) })
        else -> element
    }
}
