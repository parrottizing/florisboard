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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class LanClipboardProtocolTest : FunSpec({
    test("buildSetTextEvent creates protocol-compliant envelope") {
        val eventJson = LanClipboardProtocol.buildSetTextEvent(
            deviceId = "android.pixel.test",
            text = "phase-4 clipboard text",
            isSensitive = true,
        )
        val envelope = LanClipboardProtocol.parseEnvelope(eventJson)

        envelope shouldNotBe null
        val nonNullEnvelope = envelope!!
        nonNullEnvelope["protocol_version"]?.jsonPrimitive?.contentOrNull shouldBe LAN_CLIPBOARD_PROTOCOL_VERSION
        nonNullEnvelope["device_id"]?.jsonPrimitive?.contentOrNull shouldBe "android.pixel.test"
        nonNullEnvelope["source"]?.jsonPrimitive?.contentOrNull shouldBe LAN_CLIPBOARD_SOURCE_ANDROID
        nonNullEnvelope["event_type"]?.jsonPrimitive?.contentOrNull shouldBe "set_text"
        nonNullEnvelope["created_at_ms"]?.jsonPrimitive?.contentOrNull shouldNotBe null
        nonNullEnvelope["event_id"]?.jsonPrimitive?.contentOrNull shouldNotBe null

        val payload = LanClipboardProtocol.payloadOrNull(nonNullEnvelope)
        payload shouldNotBe null
        val nonNullPayload = payload!!
        nonNullPayload["mime_type"]?.jsonPrimitive?.contentOrNull shouldBe "text/plain"
        nonNullPayload["text"]?.jsonPrimitive?.contentOrNull shouldBe "phase-4 clipboard text"
        nonNullPayload["is_sensitive"]?.jsonPrimitive?.contentOrNull shouldBe "true"

        val payloadHash = nonNullEnvelope["payload_hash"]?.jsonPrimitive?.contentOrNull
        payloadHash shouldBe LanClipboardProtocol.computePayloadHash(nonNullPayload)
    }

    test("buildSetImageEvent creates protocol-compliant envelope") {
        val imageBytes = "phase-7-image".toByteArray(Charsets.UTF_8)
        val imageBase64 = Base64.getEncoder().encodeToString(imageBytes)
        val eventJson = LanClipboardProtocol.buildSetImageEvent(
            deviceId = "android.pixel.test",
            mimeType = "image/png",
            byteSize = imageBytes.size,
            dataBase64 = imageBase64,
            width = 640,
            height = 480,
            orientation = 90,
        )
        val envelope = LanClipboardProtocol.parseEnvelope(eventJson)

        envelope shouldNotBe null
        val nonNullEnvelope = envelope!!
        nonNullEnvelope["event_type"]?.jsonPrimitive?.contentOrNull shouldBe "set_image"
        val payload = LanClipboardProtocol.payloadOrNull(nonNullEnvelope)
        payload shouldNotBe null
        val nonNullPayload = payload!!
        nonNullPayload["mime_type"]?.jsonPrimitive?.contentOrNull shouldBe "image/png"
        nonNullPayload["byte_size"]?.jsonPrimitive?.contentOrNull shouldBe imageBytes.size.toString()
        nonNullPayload["data_base64"]?.jsonPrimitive?.contentOrNull shouldBe imageBase64
        nonNullPayload["width"]?.jsonPrimitive?.contentOrNull shouldBe "640"
        nonNullPayload["height"]?.jsonPrimitive?.contentOrNull shouldBe "480"
        nonNullPayload["orientation"]?.jsonPrimitive?.contentOrNull shouldBe "90"

        val payloadHash = nonNullEnvelope["payload_hash"]?.jsonPrimitive?.contentOrNull
        payloadHash shouldBe LanClipboardProtocol.computePayloadHash(nonNullPayload)
    }

    test("computePayloadHash is stable across object key order") {
        val payloadA = buildJsonObject {
            put("mime_type", JsonPrimitive("text/plain"))
            put("text", JsonPrimitive("same text"))
            put(
                "meta",
                buildJsonObject {
                    put("is_sensitive", JsonPrimitive(false))
                    put("format", JsonPrimitive("plain"))
                },
            )
        }
        val payloadB = buildJsonObject {
            put(
                "meta",
                buildJsonObject {
                    put("format", JsonPrimitive("plain"))
                    put("is_sensitive", JsonPrimitive(false))
                },
            )
            put("text", JsonPrimitive("same text"))
            put("mime_type", JsonPrimitive("text/plain"))
        }

        LanClipboardProtocol.computePayloadHash(payloadA) shouldBe LanClipboardProtocol.computePayloadHash(payloadB)
    }

    test("isSupportedProtocolVersion requires same major and semver format") {
        LanClipboardProtocol.isSupportedProtocolVersion("1.0") shouldBe true
        LanClipboardProtocol.isSupportedProtocolVersion("1.99") shouldBe true

        LanClipboardProtocol.isSupportedProtocolVersion("2.0") shouldBe false
        LanClipboardProtocol.isSupportedProtocolVersion("1") shouldBe false
        LanClipboardProtocol.isSupportedProtocolVersion("1.a") shouldBe false
        LanClipboardProtocol.isSupportedProtocolVersion(null) shouldBe false
    }

    test("isSupportedSource allows only android and mac") {
        LanClipboardProtocol.isSupportedSource(LAN_CLIPBOARD_SOURCE_ANDROID) shouldBe true
        LanClipboardProtocol.isSupportedSource(LAN_CLIPBOARD_SOURCE_MAC) shouldBe true

        LanClipboardProtocol.isSupportedSource("ios") shouldBe false
        LanClipboardProtocol.isSupportedSource("") shouldBe false
        LanClipboardProtocol.isSupportedSource(null) shouldBe false
    }

    test("image payload helpers enforce whitelist constraints") {
        LanClipboardProtocol.isSupportedImageMimeType("image/png") shouldBe true
        LanClipboardProtocol.isSupportedImageMimeType("image/jpeg") shouldBe true
        LanClipboardProtocol.isSupportedImageMimeType("image/webp") shouldBe true
        LanClipboardProtocol.isSupportedImageMimeType("image/gif") shouldBe false
        LanClipboardProtocol.isSupportedImageMimeType(null) shouldBe false

        LanClipboardProtocol.isSupportedImageOrientation(0) shouldBe true
        LanClipboardProtocol.isSupportedImageOrientation(90) shouldBe true
        LanClipboardProtocol.isSupportedImageOrientation(180) shouldBe true
        LanClipboardProtocol.isSupportedImageOrientation(270) shouldBe true
        LanClipboardProtocol.isSupportedImageOrientation(45) shouldBe false
        LanClipboardProtocol.isSupportedImageOrientation(null) shouldBe false
    }

    test("buildAckEvent includes status and optional error code") {
        val ackJson = LanClipboardProtocol.buildAckEvent(
            deviceId = "android.pixel.test",
            ackedEventId = "evt-123",
            status = "rejected",
            errorCode = "BAD_MESSAGE",
        )
        val envelope = LanClipboardProtocol.parseEnvelope(ackJson)
        envelope shouldNotBe null

        val nonNullEnvelope = envelope!!
        nonNullEnvelope["event_type"]?.jsonPrimitive?.contentOrNull shouldBe "ack"
        val payload = LanClipboardProtocol.payloadOrNull(nonNullEnvelope)
        payload shouldNotBe null
        payload!!["acked_event_id"]?.jsonPrimitive?.contentOrNull shouldBe "evt-123"
        payload["status"]?.jsonPrimitive?.contentOrNull shouldBe "rejected"
        payload["error_code"]?.jsonPrimitive?.contentOrNull shouldBe "BAD_MESSAGE"
    }

    test("buildErrorEvent includes protocol error envelope") {
        val errorJson = LanClipboardProtocol.buildErrorEvent(
            deviceId = "android.pixel.test",
            code = "TEMPORARY_UNAVAILABLE",
            message = "Failed applying inbound clipboard text",
            retryable = true,
        )
        val envelope = LanClipboardProtocol.parseEnvelope(errorJson)
        envelope shouldNotBe null

        val nonNullEnvelope = envelope!!
        nonNullEnvelope["event_type"]?.jsonPrimitive?.contentOrNull shouldBe "error"
        val payload = LanClipboardProtocol.payloadOrNull(nonNullEnvelope)
        payload shouldNotBe null
        payload!!["code"]?.jsonPrimitive?.contentOrNull shouldBe "TEMPORARY_UNAVAILABLE"
        payload["message"]?.jsonPrimitive?.contentOrNull shouldBe "Failed applying inbound clipboard text"
        payload["retryable"]?.jsonPrimitive?.contentOrNull shouldBe "true"
    }

    test("websocketUrl normalizes endpoint host and path") {
        LanClipboardEndpoint(
            host = "::1",
            port = 8765,
            path = "v1/clipboard",
        ).websocketUrl() shouldBe "ws://[::1]:8765/v1/clipboard"

        LanClipboardEndpoint(
            host = "macbook.local",
            port = 8765,
            path = "",
        ).websocketUrl() shouldBe "ws://macbook.local:8765/v1/clipboard"
    }
})
