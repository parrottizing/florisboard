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

class LanClipboardForegroundControllerTest : FunSpec({
    test("foreground predicate runs only when LAN sync is enabled and mode is always-on") {
        data class Case(
            val lanSyncEnabled: Boolean,
            val reliabilityMode: LanClipboardReliabilityMode,
            val token: String,
            val expected: Boolean,
        )

        val cases = listOf(
            Case(
                lanSyncEnabled = false,
                reliabilityMode = LanClipboardReliabilityMode.MODE_A_BEST_EFFORT,
                token = "",
                expected = false,
            ),
            Case(
                lanSyncEnabled = false,
                reliabilityMode = LanClipboardReliabilityMode.MODE_B_ALWAYS_ON,
                token = "pairing-token",
                expected = false,
            ),
            Case(
                lanSyncEnabled = true,
                reliabilityMode = LanClipboardReliabilityMode.MODE_A_BEST_EFFORT,
                token = "",
                expected = false,
            ),
            Case(
                lanSyncEnabled = true,
                reliabilityMode = LanClipboardReliabilityMode.MODE_A_BEST_EFFORT,
                token = "pairing-token",
                expected = false,
            ),
            Case(
                lanSyncEnabled = true,
                reliabilityMode = LanClipboardReliabilityMode.MODE_B_ALWAYS_ON,
                token = "",
                expected = true,
            ),
            Case(
                lanSyncEnabled = true,
                reliabilityMode = LanClipboardReliabilityMode.MODE_B_ALWAYS_ON,
                token = "pairing-token",
                expected = true,
            ),
        )

        cases.forEach { case ->
            LanClipboardForegroundController.shouldRunForegroundService(
                lanSyncEnabled = case.lanSyncEnabled,
                reliabilityMode = case.reliabilityMode,
                token = case.token,
            ) shouldBe case.expected
        }
    }
})
