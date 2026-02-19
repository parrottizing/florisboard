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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class LanClipboardPairingTest : FunSpec({
    test("parseLanClipboardPairingOffer accepts valid deep link") {
        val uri = Uri.parse(
            "ui://florisboard/settings/clipboard?" +
                "lan_pair_version=1&" +
                "lan_pair_host=192.168.1.12&" +
                "lan_pair_port=8765&" +
                "lan_pair_code=abc123&" +
                "lan_pair_redeem_path=%2Fv1%2Fpair%2Fredeem"
        )

        val offer = parseLanClipboardPairingOffer(uri)
        offer shouldNotBe null
        offer!!.host shouldBe "192.168.1.12"
        offer.port shouldBe 8765
        offer.code shouldBe "abc123"
        offer.redeemPath shouldBe "/v1/pair/redeem"
    }

    test("parseLanClipboardPairingOffer rejects missing one-time code") {
        val uri = Uri.parse(
            "ui://florisboard/settings/clipboard?" +
                "lan_pair_version=1&lan_pair_host=192.168.1.12"
        )

        parseLanClipboardPairingOffer(uri).shouldBeNull()
    }

    test("parseLanClipboardPairingOffer rejects unsupported pairing version") {
        val uri = Uri.parse(
            "ui://florisboard/settings/clipboard?" +
                "lan_pair_version=2&" +
                "lan_pair_host=192.168.1.12&" +
                "lan_pair_code=abc123"
        )

        parseLanClipboardPairingOffer(uri).shouldBeNull()
    }

    test("pairing helpers normalize host and paths") {
        sanitizePairingHost(" [fe80::1] ") shouldBe "fe80::1"
        normalizePairingPath("v1/pair/redeem") shouldBe "/v1/pair/redeem"
        normalizeWebsocketPath("v1/clipboard") shouldBe "/v1/clipboard"
        normalizeWebsocketPath("") shouldBe LAN_CLIPBOARD_DEFAULT_PATH
    }
})
