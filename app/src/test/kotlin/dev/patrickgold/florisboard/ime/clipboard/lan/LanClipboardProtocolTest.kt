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
