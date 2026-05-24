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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class LanClipboardEndpointSelectorTest : FunSpec({
    test("auto discovery preserves legacy manual fallback when no service identity is known") {
        val endpoint = selectLanClipboardEndpoint(
            endpointMode = LanClipboardEndpointMode.AUTO_DISCOVERY,
            host = "192.168.1.12",
            port = 8765,
            preferredServiceId = "",
            discoveredEndpoints = emptyList(),
        )

        endpoint!!.host shouldBe "192.168.1.12"
        endpoint.port shouldBe 8765
        endpoint.path shouldBe LAN_CLIPBOARD_DEFAULT_PATH
        endpoint.isManual shouldBe true
    }

    test("auto discovery waits for mdns when paired service identity exists but nothing is discovered") {
        selectLanClipboardEndpoint(
            endpointMode = LanClipboardEndpointMode.AUTO_DISCOVERY,
            host = "192.168.1.12",
            port = 8765,
            preferredServiceId = "gumlet-bridge-mac-123",
            discoveredEndpoints = emptyList(),
        ).shouldBeNull()
    }

    test("auto discovery prefers the paired service identity when multiple bridges are visible") {
        val oldEndpoint = LanClipboardEndpoint(
            host = "192.168.1.12",
            port = 8765,
            serviceId = "gumlet-bridge-old",
        )
        val pairedEndpoint = LanClipboardEndpoint(
            host = "192.168.1.44",
            port = 8765,
            serviceId = "gumlet-bridge-mac-123",
        )

        val endpoint = selectLanClipboardEndpoint(
            endpointMode = LanClipboardEndpointMode.AUTO_DISCOVERY,
            host = "192.168.1.12",
            port = 8765,
            preferredServiceId = "gumlet-bridge-mac-123",
            discoveredEndpoints = listOf(oldEndpoint, pairedEndpoint),
        )

        endpoint shouldBe pairedEndpoint
    }

    test("service id update is emitted after auto discovery connects without stored service id") {
        preferredLanClipboardServiceIdUpdate(
            connectedEndpoint = LanClipboardEndpoint(
                host = "192.168.110.86",
                port = 8765,
                serviceId = "gumlet-bridge-current",
            ),
            currentPreferredServiceId = "",
        ) shouldBe "gumlet-bridge-current"
    }

    test("service id update is skipped for manual endpoints or unchanged ids") {
        preferredLanClipboardServiceIdUpdate(
            connectedEndpoint = LanClipboardEndpoint(
                host = "192.168.110.86",
                port = 8765,
                serviceId = "gumlet-bridge-current",
                isManual = true,
            ),
            currentPreferredServiceId = "",
        ).shouldBeNull()

        preferredLanClipboardServiceIdUpdate(
            connectedEndpoint = LanClipboardEndpoint(
                host = "192.168.110.86",
                port = 8765,
                serviceId = "gumlet-bridge-current",
            ),
            currentPreferredServiceId = "gumlet-bridge-current",
        ).shouldBeNull()
    }
})
