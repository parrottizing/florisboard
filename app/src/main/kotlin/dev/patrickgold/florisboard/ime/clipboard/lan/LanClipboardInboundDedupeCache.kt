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

import java.util.LinkedHashMap

private const val DEFAULT_EVENT_ID_CACHE_SIZE = 512
private const val DEFAULT_EVENT_ID_TTL_MS = 10 * 60 * 1000L
private const val DEFAULT_PAYLOAD_HASH_CACHE_SIZE = 64
private const val DEFAULT_PAYLOAD_HASH_TTL_MS = 30 * 1000L

internal class LanClipboardInboundDedupeCache(
    private val eventIdCacheSize: Int = DEFAULT_EVENT_ID_CACHE_SIZE,
    private val eventIdTtlMs: Long = DEFAULT_EVENT_ID_TTL_MS,
    private val payloadHashCacheSize: Int = DEFAULT_PAYLOAD_HASH_CACHE_SIZE,
    private val payloadHashTtlMs: Long = DEFAULT_PAYLOAD_HASH_TTL_MS,
) {
    private val lock = Any()
    private val eventIds = LinkedHashMap<String, Long>()
    private val payloadHashes = LinkedHashMap<String, Long>()

    fun hasSeenEventId(source: String, eventId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        return synchronized(lock) {
            pruneLocked(nowMs)
            eventIds.containsKey(eventIdKey(source, eventId))
        }
    }

    fun hasRecentPayloadHash(source: String, payloadHash: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        return synchronized(lock) {
            pruneLocked(nowMs)
            payloadHashes.containsKey(payloadHashKey(source, payloadHash))
        }
    }

    fun record(source: String, eventId: String, payloadHash: String, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            pruneLocked(nowMs)
            eventIds[eventIdKey(source, eventId)] = nowMs
            payloadHashes[payloadHashKey(source, payloadHash)] = nowMs
            trimToSizeLocked(eventIds, eventIdCacheSize)
            trimToSizeLocked(payloadHashes, payloadHashCacheSize)
        }
    }

    private fun pruneLocked(nowMs: Long) {
        pruneExpiredLocked(eventIds, nowMs - eventIdTtlMs)
        pruneExpiredLocked(payloadHashes, nowMs - payloadHashTtlMs)
    }

    private fun pruneExpiredLocked(entries: LinkedHashMap<String, Long>, thresholdMs: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value < thresholdMs) {
                iterator.remove()
            }
        }
    }

    private fun trimToSizeLocked(entries: LinkedHashMap<String, Long>, maxSize: Int) {
        while (entries.size > maxSize) {
            val eldest = entries.entries.iterator().next()
            entries.remove(eldest.key)
        }
    }

    private fun eventIdKey(source: String, eventId: String): String = "$source:$eventId"

    private fun payloadHashKey(source: String, payloadHash: String): String = "$source:$payloadHash"
}
