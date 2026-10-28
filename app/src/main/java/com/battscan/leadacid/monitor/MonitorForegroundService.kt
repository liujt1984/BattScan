package com.battscan.leadacid.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.battscan.leadacid.BattScanApp
import com.battscan.leadacid.R
import com.battscan.leadacid.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * Keeps monitoring alive with the phone screen off in a pocket.
 *
 * Both link teardowns in the 2026-10-28 capture were silent 0x08 timeouts, so the service exists
 * mainly to make sure the reconnect supervisor is still running when the rider stops for a minute.
 */
class MonitorForegroundService : LifecycleService() {

    private var lastNotifyAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIF_ID, buildNotification(null), serviceType())
                observeSnapshot()
                return START_STICKY
            }
        }
    }

    private fun serviceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }

    private fun observeSnapshot() {
        val engine = (application as BattScanApp).engine
        lifecycleScope.launch {
            engine.snapshot.collect { snap ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastNotifyAt < NOTIFY_THROTTLE_MS) return@collect
                lastNotifyAt = now
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIF_ID, buildNotification(snap))
            }
        }
    }

    private fun buildNotification(snap: MonitorSnapshot?): Notification {
        val content = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarm = snap?.criticalAlarm
        val title = when {
            alarm != null -> "电池告警 · ${alarm.detail}"
            snap?.link?.online == true -> "正在监测 · ${snap.link.deviceName ?: snap.link.deviceAddress ?: "WS-BLE"}"
            else -> "蓝牙未连接"
        }
        val latest = snap?.latest
        val body = if (latest != null) {
            getString(R.string.notif_body, latest.voltageV, latest.currentA, (snap.soc?.socPercent ?: 0.0).toInt())
        } else {
            snap?.link?.detail?.takeIf { it.isNotBlank() } ?: "等待数据"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(content)
            .setOngoing(true)
            .setSilent(alarm == null)
            .setPriority(if (alarm != null) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_monitor), NotificationManager.IMPORTANCE_LOW)
        channel.showBadge = true
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "com.battscan.leadacid.action.STOP"
        const val CHANNEL_ID = "battscan_monitor"
        const val NOTIF_ID = 4101
        const val NOTIFY_THROTTLE_MS = 5_000L

        fun startIntent(context: android.content.Context): Intent =
            Intent(context, MonitorForegroundService::class.java).setAction(ACTION_START)

        private const val ACTION_START = "com.battscan.leadacid.action.START"
    }
}
