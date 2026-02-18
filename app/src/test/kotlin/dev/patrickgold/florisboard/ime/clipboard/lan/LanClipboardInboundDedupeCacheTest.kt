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

class LanClipboardInboundDedupeCacheTest : FunSpec({
    test("tracks event ids and payload hashes per source") {
        val cache = LanClipboardInboundDedupeCache(
            eventIdCacheSize = 8,
            eventIdTtlMs = 1_000,
            payloadHashCacheSize = 8,
            payloadHashTtlMs = 1_000,
        )

        cache.record(
            source = LAN_CLIPBOARD_SOURCE_MAC,
            eventId = "evt-1",
            payloadHash = "hash-1",
            nowMs = 100,
        )

        cache.hasSeenEventId(LAN_CLIPBOARD_SOURCE_MAC, "evt-1", nowMs = 100) shouldBe true
        cache.hasSeenEventId(LAN_CLIPBOARD_SOURCE_ANDROID, "evt-1", nowMs = 100) shouldBe false
        cache.hasRecentPayloadHash(LAN_CLIPBOARD_SOURCE_MAC, "hash-1", nowMs = 100) shouldBe true
        cache.hasRecentPayloadHash(LAN_CLIPBOARD_SOURCE_ANDROID, "hash-1", nowMs = 100) shouldBe false
    }

    test("evicts stale entries by ttl") {
        val cache = LanClipboardInboundDedupeCache(
            eventIdCacheSize = 8,
            eventIdTtlMs = 10,
            payloadHashCacheSize = 8,
            payloadHashTtlMs = 5,
        )
        cache.record(
            source = LAN_CLIPBOARD_SOURCE_MAC,
            eventId = "evt-1",
            payloadHash = "hash-1",
            nowMs = 100,
        )

        cache.hasSeenEventId(LAN_CLIPBOARD_SOURCE_MAC, "evt-1", nowMs = 110) shouldBe true
        cache.hasSeenEventId(LAN_CLIPBOARD_SOURCE_MAC, "evt-1", nowMs = 111) shouldBe false
        cache.hasRecentPayloadHash(LAN_CLIPBOARD_SOURCE_MAC, "hash-1", nowMs = 106) shouldBe false
    }

    test("enforces fixed cache size") {
        val cache = LanClipboardInboundDedupeCache(
            eventIdCacheSize = 2,
            eventIdTtlMs = 10_000,
            payloadHashCacheSize = 2,
            payloadHashTtlMs = 10_000,
        )
        cache.record(LAN_CLIPBOARD_SOURCE_MAC, "evt-1", "hash-1", nowMs = 1)
        cache.record(LAN_CLIPBOARD_SOURCE_MAC, "evt-2", "hash-2", nowMs = 2)
        cache.record(LAN_CLIPBOARD_SOURCE_MAC, "evt-3", "hash-3", nowMs = 3)

        cache.hasSeenEventId(LAN_CLIPBOARD_SOURCE_MAC, "evt-1", nowMs = 4) shouldBe false
        cache.hasSeenEventId(LAN_CLIPBOARD_SOURCE_MAC, "evt-2", nowMs = 4) shouldBe true
        cache.hasSeenEventId(LAN_CLIPBOARD_SOURCE_MAC, "evt-3", nowMs = 4) shouldBe true

        cache.hasRecentPayloadHash(LAN_CLIPBOARD_SOURCE_MAC, "hash-1", nowMs = 4) shouldBe false
        cache.hasRecentPayloadHash(LAN_CLIPBOARD_SOURCE_MAC, "hash-2", nowMs = 4) shouldBe true
        cache.hasRecentPayloadHash(LAN_CLIPBOARD_SOURCE_MAC, "hash-3", nowMs = 4) shouldBe true
    }
})
