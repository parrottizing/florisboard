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

import android.content.Intent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LanClipboardAutostartReceiverTest : FunSpec({
    test("receiver handles only boot, unlock, and package replaced intents") {
        LanClipboardAutostartReceiver.shouldHandleAction(Intent.ACTION_BOOT_COMPLETED) shouldBe true
        LanClipboardAutostartReceiver.shouldHandleAction(Intent.ACTION_USER_UNLOCKED) shouldBe true
        LanClipboardAutostartReceiver.shouldHandleAction(Intent.ACTION_MY_PACKAGE_REPLACED) shouldBe true

        LanClipboardAutostartReceiver.shouldHandleAction(Intent.ACTION_PACKAGE_ADDED) shouldBe false
        LanClipboardAutostartReceiver.shouldHandleAction(Intent.ACTION_SCREEN_ON) shouldBe false
        LanClipboardAutostartReceiver.shouldHandleAction(null) shouldBe false
    }
})
