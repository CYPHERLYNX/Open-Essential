package com.craft.capturespace.ai

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.craft.capturespace.App
import com.craft.capturespace.R
import com.craft.capturespace.data.Notifications
import com.craft.capturespace.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps a model download going when the person leaves the app or turns the screen off, with a
 * visible notification showing progress and a Cancel button. It only runs while a download the
 * person started is in progress, and stops itself as soon as none is.
 */
class DownloadService : Service() {
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            Models.cancelAll()
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notification(0, 0)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(Notifications.ID_DOWNLOAD, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(Notifications.ID_DOWNLOAD, n)
        } catch (_: Exception) {
            // Android refused a background start; the download still runs while the app is open.
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher?.isActive != true) watcher = App.instance.scope.launch {
            delay(500)
            while (Models.anyDownloading()) {
                val (done, total) = Models.progress()
                if (Notifications.canPost(this@DownloadService)) {
                    getSystemService(android.app.NotificationManager::class.java)
                        .notify(Notifications.ID_DOWNLOAD, notification(done, total))
                }
                delay(1000)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android 15 limits background data transfers; stop cleanly, the .part file resumes later. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Models.cancelAll()
        stopSelf()
    }

    override fun onDestroy() {
        watcher?.cancel()
        super.onDestroy()
    }

    private fun notification(done: Long, total: Long): Notification {
        val open = PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 21, Intent(this, DownloadService::class.java).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE)
        val b = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, Notifications.CH_DOWNLOAD) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Downloading on-device AI models")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
        if (total > 0) {
            b.setContentText("${done / 1_000_000} of ${total / 1_000_000} MB")
            b.setProgress(1000, ((done * 1000) / total).toInt(), false)
        } else b.setProgress(0, 0, true)
        return b.build()
    }

    companion object {
        const val CANCEL = "cancel"

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, DownloadService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) {
                // Not allowed right now (app in the background); the download continues while the app is open.
            }
        }
    }
}
