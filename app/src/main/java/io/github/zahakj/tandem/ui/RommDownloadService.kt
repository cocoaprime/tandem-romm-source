package io.github.zahakj.tandem.ui

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.snapshotFlow
import io.github.zahakj.tandem.MainActivity
import io.github.zahakj.tandem.NetGate
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps user-started transfers alive while Tandem's activities are in the background. */
internal class RommDownloadService : Service() {
    private val scope = MainScope()
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private var lastCounts: Pair<Int, Int>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "RoMM downloads", NotificationManager.IMPORTANCE_LOW))
        scope.launch {
            snapshotFlow { RommSettingsState.config to NetGate.closed }.collect { (config, _) ->
                RommDownloads.environment(this@RommDownloadService, config)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            RommDownloads.cancelAll()
            if (lastCounts == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification("Preparing downloads…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: RuntimeException) {
            RommDownloads.serviceFailed()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        lastCounts = null
        RommDownloads.serviceStarted(this)
        return START_NOT_STICKY
    }

    fun update(running: Int, waiting: Int, force: Boolean = false) {
        val counts = running to waiting
        if (counts == lastCounts && !force) return
        lastCounts = counts
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val active = if (running == 1) "1 download active" else "$running downloads active"
        val label = if (waiting == 0) active else "$active · $waiting queued"
        notifications.notify(NOTIFICATION_ID, notification(label))
    }

    fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        RommDownloads.serviceStopped(this)
        super.onDestroy()
    }

    private fun notification(label: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, RommDownloadService::class.java).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("RoMM downloads")
            .setContentText(label)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Cancel all", cancel).build())
            .apply {
                if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
            .build()
    }

    companion object {
        private const val CHANNEL = "romm-downloads"
        private const val NOTIFICATION_ID = 29431
        private const val CANCEL = "io.github.zahakj.tandem.romm.CANCEL_DOWNLOADS"

        fun request(context: Context) {
            context.startForegroundService(Intent(context, RommDownloadService::class.java))
        }
    }
}
