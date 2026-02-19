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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisAppActivity
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.lanClipboardSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.florisboard.lib.android.stringRes

private const val NOTIFICATION_CHANNEL_ID = "${BuildConfig.APPLICATION_ID}.ime.clipboard.lan"
private const val NOTIFICATION_ID = 0x2BAD0200
private const val OPEN_SETTINGS_REQUEST_CODE = 0x2BAD0201
private const val RECONNECT_REQUEST_CODE = 0x2BAD0202
private const val CLIPBOARD_SETTINGS_DEEPLINK = "ui://florisboard/settings/clipboard"

class LanClipboardForegroundService : Service() {
    companion object {
        const val ACTION_START_OR_UPDATE = "${BuildConfig.APPLICATION_ID}.action.LAN_CLIPBOARD_START_OR_UPDATE"
        const val ACTION_STOP = "${BuildConfig.APPLICATION_ID}.action.LAN_CLIPBOARD_STOP"
        const val ACTION_RECONNECT = "${BuildConfig.APPLICATION_ID}.action.LAN_CLIPBOARD_RECONNECT"

        fun intent(context: Context, action: String): Intent {
            return Intent(context, LanClipboardForegroundService::class.java).apply {
                this.action = action
            }
        }
    }

    private val prefs by FlorisPreferenceStore
    private val lanClipboardSyncManager by lanClipboardSyncManager()
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var statusCollectionJob: Job? = null
    private var latestConnectionStatus: LanClipboardConnectionStatus = LanClipboardConnectionStatus.Disabled
    private var isForegroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        statusCollectionJob = serviceScope.launch {
            lanClipboardSyncManager.connectionStatusFlow.collectLatest { status ->
                latestConnectionStatus = status
                if (isForegroundStarted) {
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(status))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_OR_UPDATE
        if (action == ACTION_STOP) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        startForegroundIfNeeded()
        if (!shouldKeepRunningFromPrefs()) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_RECONNECT) {
            lanClipboardSyncManager.requestManualReconnect()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        statusCollectionJob?.cancel()
        serviceScope.cancel()
        isForegroundStarted = false
        super.onDestroy()
    }

    private fun shouldKeepRunningFromPrefs(): Boolean {
        return runCatching {
            LanClipboardForegroundController.shouldRunForegroundService(
                lanSyncEnabled = prefs.clipboard.lanSyncEnabled.get(),
                reliabilityMode = prefs.clipboard.lanSyncReliabilityMode.get(),
                token = prefs.clipboard.lanSyncToken.get(),
            )
        }.getOrDefault(false)
    }

    private fun startForegroundIfNeeded() {
        val notification = buildNotification(latestConnectionStatus)
        if (!isForegroundStarted) {
            startForeground(NOTIFICATION_ID, notification)
            isForegroundStarted = true
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundAndSelf() {
        if (isForegroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundStarted = false
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            stringRes(R.string.lan_clipboard__notification_channel__title),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: LanClipboardConnectionStatus): Notification {
        val statusText = buildStatusText(status)
        val builder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(stringRes(R.string.lan_clipboard__notification__title))
            .setContentText(statusText)
            .setStyle(Notification.BigTextStyle().bigText(statusText))
            .setContentIntent(openClipboardSettingsPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_preferences,
                stringRes(R.string.lan_clipboard__notification__open_settings),
                openClipboardSettingsPendingIntent(),
            )

        if (shouldShowReconnectAction(status)) {
            builder.addAction(
                android.R.drawable.stat_notify_sync,
                stringRes(R.string.lan_clipboard__notification__reconnect_now),
                reconnectPendingIntent(),
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun buildStatusText(status: LanClipboardConnectionStatus): String {
        return when (status.state) {
            LanClipboardConnectionState.DISABLED -> stringRes(R.string.lan_clipboard__notification__starting)
            LanClipboardConnectionState.DISCONNECTED -> status.message?.let {
                stringRes(
                    R.string.pref__clipboard__lan_sync_status__disconnected_with_reason,
                    "reason" to it,
                )
            } ?: stringRes(R.string.pref__clipboard__lan_sync_status__disconnected)
            LanClipboardConnectionState.DISCOVERING -> stringRes(R.string.pref__clipboard__lan_sync_status__discovering)
            LanClipboardConnectionState.CONNECTING -> stringRes(
                R.string.pref__clipboard__lan_sync_status__connecting,
                "endpoint" to (status.endpoint?.displayAddress() ?: stringRes(R.string.general__unknown)),
            )
            LanClipboardConnectionState.CONNECTED -> stringRes(
                R.string.pref__clipboard__lan_sync_status__connected,
                "endpoint" to (status.endpoint?.displayAddress() ?: stringRes(R.string.general__unknown)),
            )
            LanClipboardConnectionState.RECONNECTING -> stringRes(
                R.string.pref__clipboard__lan_sync_status__reconnecting,
                "attempt" to status.retryAttempt,
            )
            LanClipboardConnectionState.ERROR -> status.message ?: stringRes(R.string.pref__clipboard__lan_sync_status__error)
        }
    }

    private fun shouldShowReconnectAction(status: LanClipboardConnectionStatus): Boolean {
        return when (status.state) {
            LanClipboardConnectionState.CONNECTED,
            LanClipboardConnectionState.CONNECTING,
            -> false
            else -> true
        }
    }

    private fun openClipboardSettingsPendingIntent(): PendingIntent {
        val settingsIntent = Intent(this, FlorisAppActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(CLIPBOARD_SETTINGS_DEEPLINK)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            OPEN_SETTINGS_REQUEST_CODE,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun reconnectPendingIntent(): PendingIntent {
        return PendingIntent.getService(
            this,
            RECONNECT_REQUEST_CODE,
            intent(this, ACTION_RECONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
