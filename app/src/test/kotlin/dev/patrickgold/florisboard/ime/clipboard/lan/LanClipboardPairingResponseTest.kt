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
import io.kotest.assertions.throwables.shouldThrow

class LanClipboardPairingResponseTest : FunSpec({
    test("pairing response parser keeps service identity for later discovery") {
        val offer = LanClipboardPairingOffer(
            code = "code",
            host = "192.168.1.12",
            port = 8765,
            redeemPath = "/v1/pair/redeem",
        )

        val credentials = parseLanClipboardPairingCredentialsResponse(
            """{"token":"gumlet-sync-2026","host":"192.168.1.44","port":8766,"ws_path":"v1/clipboard","service_id":"gumlet-bridge-mac-123"}""",
            offer,
        )

        credentials.token shouldBe "gumlet-sync-2026"
        credentials.host shouldBe "192.168.1.44"
        credentials.port shouldBe 8766
        credentials.websocketPath shouldBe "/v1/clipboard"
        credentials.serviceId shouldBe "gumlet-bridge-mac-123"
    }

    test("pairing response parser rejects missing token") {
        val offer = LanClipboardPairingOffer(
            code = "code",
            host = "192.168.1.12",
            port = 8765,
            redeemPath = "/v1/pair/redeem",
        )

        shouldThrow<IllegalStateException> {
            parseLanClipboardPairingCredentialsResponse(
                """{"host":"192.168.1.44"}""",
                offer,
            )
        }
    }
})
