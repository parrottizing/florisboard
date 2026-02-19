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
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import dev.patrickgold.florisboard.app.FlorisPreferenceStore

private const val TAG = "LanClipboardFgController"

object LanClipboardForegroundController {
    private val prefs by FlorisPreferenceStore

    fun syncWithPrefs(context: Context) {
        val appContext = context.applicationContext
        val shouldRunForegroundService = runCatching {
            shouldRunForegroundService(
                lanSyncEnabled = prefs.clipboard.lanSyncEnabled.get(),
                reliabilityMode = prefs.clipboard.lanSyncReliabilityMode.get(),
                token = prefs.clipboard.lanSyncToken.get(),
            )
        }.getOrElse { error ->
            Log.w(TAG, "Unable to read LAN clipboard preferences for foreground sync", error)
            return
        }

        if (shouldRunForegroundService) {
            startOrUpdate(appContext)
        } else {
            stop(appContext)
        }
    }

    private fun startOrUpdate(context: Context) {
        try {
            ContextCompat.startForegroundService(
                context,
                LanClipboardForegroundService.intent(
                    context = context,
                    action = LanClipboardForegroundService.ACTION_START_OR_UPDATE,
                ),
            )
        } catch (error: RuntimeException) {
            if (isForegroundServiceStartNotAllowed(error)) {
                Log.w(TAG, "Foreground service start not allowed, will retry on next trigger", error)
            } else {
                throw error
            }
        }
    }

    private fun stop(context: Context) {
        context.stopService(
            LanClipboardForegroundService.intent(
                context = context,
                action = LanClipboardForegroundService.ACTION_STOP,
            ),
        )
    }

    @VisibleForTesting
    internal fun shouldRunForegroundService(
        lanSyncEnabled: Boolean,
        reliabilityMode: LanClipboardReliabilityMode,
        @Suppress("UNUSED_PARAMETER")
        token: String,
    ): Boolean {
        // Token state must not gate service liveness, so disconnected states stay visible.
        return lanSyncEnabled && reliabilityMode == LanClipboardReliabilityMode.MODE_B_ALWAYS_ON
    }

    private fun isForegroundServiceStartNotAllowed(error: Throwable): Boolean {
        var currentError: Throwable? = error
        while (currentError != null) {
            if (currentError.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException") {
                return true
            }
            currentError = currentError.cause
        }
        return false
    }
}
