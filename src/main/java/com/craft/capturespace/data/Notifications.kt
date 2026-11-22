package com.craft.capturespace.data

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.craft.capturespace.App
import com.craft.capturespace.R
import com.craft.capturespace.capture.CaptureActivity
import com.craft.capturespace.ui.MainActivity
import java.text.DateFormat
import java.util.Date

object Notifications {
    const val CH_READY = "ready"
    const val CH_REMIND = "reminders"
    const val CH_SHORTCUT = "shortcut"
    const val CH_RECORD = "recording"
    const val CH_DOWNLOAD = "model_download"
    private const val ID_SHORTCUT = 1
    const val ID_RECORD = 2
    const val ID_DOWNLOAD = 3

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_READY, "Captures ready", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_REMIND, "Tasks and events", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SHORTCUT, "Capture shortcut", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_RECORD, "Recording", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DOWNLOAD, "Model download", NotificationManager.IMPORTANCE_LOW))
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun builder(ctx: Context, channel: String): Notification.Builder =
        (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel) else @Suppress("DEPRECATION") Notification.Builder(ctx))
            .setSmallIcon(R.drawable.ic_tile)

    fun openCardIntent(ctx: Context, cardId: Long): PendingIntent = PendingIntent.getActivity(
        ctx, cardId.toInt(),
        Intent(ctx, MainActivity::class.java).putExtra("card", cardId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun captureIntent(ctx: Context, mode: String, request: Int): PendingIntent = PendingIntent.getActivity(
        ctx, request,
        Intent(ctx, CaptureActivity::class.java).setAction(CaptureActivity.ACTION).putExtra("mode", mode)
            .putExtra(CaptureActivity.EXTRA_HAPTIC, "notification").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun ready(ctx: Context, card: Card) {
        if (!canPost(ctx) || App.visible) return
        val n = builder(ctx, CH_READY)
            .setContentTitle(card.displayTitle)
            .setContentText(card.summary.ifBlank { "Your capture is ready" })
            .setStyle(Notification.BigTextStyle().bigText(card.summary))
            .setContentIntent(openCardIntent(ctx, card.id))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(1000 + card.id.toInt(), n)
    }

    /** The persistent "notification shortcut": quick capture from the notification shade. */
    fun showShortcut(ctx: Context) {
        if (!canPost(ctx)) return
        val n = builder(ctx, CH_SHORTCUT)
            .setContentTitle("Open Essential")
            .setContentText("Capture your screen, a voice note or a note")
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(captureIntent(ctx, "menu", 10))
            .addAction(Notification.Action.Builder(null, "Screen", captureIntent(ctx, "screenshot", 11)).build())
            .addAction(Notification.Action.Builder(null, "Voice", captureIntent(ctx, "voice", 12)).build())
            .addAction(Notification.Action.Builder(null, "Note", captureIntent(ctx, "note", 13)).build())
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_SHORTCUT, n)
    }

    fun hideShortcut(ctx: Context) =
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_SHORTCUT)

    fun reminder(ctx: Context, item: PlanItem) {
        if (!canPost(ctx)) return
        val whenText = item.start?.let {
            if (item.allDay) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))
            else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
        } ?: ""
        val content = item.cardId?.let { openCardIntent(ctx, it) } ?: PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).putExtra("open", "tasks"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = builder(ctx, CH_REMIND)
            .setContentTitle(if (item.kind == "event") "REMINDER · ${item.title}" else item.title)
            .setContentText(listOf(whenText, item.location).filter { it.isNotBlank() }.joinToString(" · "))
            .setContentIntent(content)
            .setAutoCancel(true)
        if (item.kind == "task") {
            val done = PendingIntent.getBroadcast(
                ctx, item.id.toInt(),
                Intent(ctx, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_DONE).putExtra("item", item.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(Notification.Action.Builder(null, "Mark as Completed", done).build())
        }
        ctx.getSystemService(NotificationManager::class.java).notify(5000 + item.id.toInt(), b.build())
    }
}

/** Reminders for tasks and events, rebuilt after a restart. */
object Reminders {
    fun schedule(ctx: Context, item: PlanItem) {
        cancel(ctx, item.id)
        val s = App.instance.settings
        if (!s.reminders || item.done || item.start == null) return
        val lead = if (item.kind == "event" && !item.allDay) s.reminderLeadMinutes * 60_000L else 0L
        val at = if (item.allDay) item.start + 9 * 3600_000L else item.start - lead
        if (at <= System.currentTimeMillis()) return
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, item.id)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context, itemId: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, itemId))
    }

    fun rescheduleAll(ctx: Context) {
        App.instance.repo.items().forEach { schedule(ctx, it) }
    }

    private fun pending(ctx: Context, itemId: Long) = PendingIntent.getBroadcast(
        ctx, itemId.toInt(),
        Intent(ctx, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_REMIND).putExtra("item", itemId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = App.instance
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Reminders.rescheduleAll(ctx)
                if (app.settings.notificationShortcut) Notifications.showShortcut(ctx)
            }
            ACTION_REMIND -> app.repo.item(intent.getLongExtra("item", -1))?.let {
                if (!it.done) Notifications.reminder(ctx, it)
            }
            ACTION_DONE -> {
                val id = intent.getLongExtra("item", -1)
                app.repo.item(id)?.let { app.repo.updateItem(it.copy(done = true)) }
                ctx.getSystemService(NotificationManager::class.java).cancel(5000 + id.toInt())
            }
        }
    }

    companion object {
        const val ACTION_REMIND = "com.craft.capturespace.REMIND"
        const val ACTION_DONE = "com.craft.capturespace.TASK_DONE"
    }
}
