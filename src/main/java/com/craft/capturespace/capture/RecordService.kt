package com.craft.capturespace.capture

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.craft.capturespace.App
import com.craft.capturespace.R
import com.craft.capturespace.ai.Pipeline
import com.craft.capturespace.data.Notifications
import com.craft.capturespace.data.TYPE_VOICE
import java.io.File

/** Live state of the one recording that can run at a time. */
object Recording {
    var active by mutableStateOf(false)
    var startedAt by mutableLongStateOf(0L)
    var level by mutableStateOf(0f)
    val marks = mutableStateListOf<Long>()
    var note by mutableStateOf("")
    internal var recorder: MediaRecorder? = null
    internal var file: File? = null

    fun elapsed(): Long = if (active) SystemClock.elapsedRealtime() - startedAt else 0L

    /** Loudness since the last call, 0..1, from the microphone (for the live waveform). */
    fun level(): Float = try {
        val a = recorder?.maxAmplitude ?: 0
        (Math.sqrt(a / 32767.0)).toFloat().coerceIn(0f, 1f)
    } catch (_: Exception) { 0f }

    /** "Mark Key Moments": the original does this with a single press of the Essential Key. */
    fun mark() {
        if (active) marks.add(elapsed())
    }

    fun start(ctx: Context): Boolean {
        if (active) return true
        val i = Intent(ctx, RecordService::class.java).setAction(RecordService.START)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        return true
    }

    /** Stops and saves; returns the new capture's id (or null when nothing was recorded). */
    fun stopAndSave(ctx: Context): Long? {
        val rec = recorder ?: return null
        val f = file
        val duration = elapsed()
        val m = marks.toList()
        val n = note
        try { rec.stop() } catch (_: Exception) { f?.delete() }
        rec.release()
        recorder = null; file = null; active = false; marks.clear(); note = ""
        ctx.stopService(Intent(ctx, RecordService::class.java))
        if (f == null || !f.exists() || f.length() == 0L) return null
        val repo = App.instance.repo
        val id = repo.insertCard(TYPE_VOICE, note = n, file = f.absolutePath, mime = "audio/aac", durationMs = duration, marks = m)
        if (App.instance.settings.autoAnalyze) Pipeline.enqueue(ctx, id) else repo.setStatus(id, com.craft.capturespace.data.Status.OFF)
        return id
    }

    /**
     * Stops without making a voice capture: the file is returned to go with a picture
     * (the hold-to-talk note on the photo screen). Null when nothing was recorded.
     */
    fun stopToFile(ctx: Context): Pair<File, Long>? {
        val rec = recorder ?: run { cancel(ctx); return null }
        val f = file
        val duration = elapsed()
        var ok = true
        try { rec.stop() } catch (_: Exception) { ok = false }
        rec.release()
        recorder = null; file = null; active = false; marks.clear(); note = ""
        ctx.stopService(Intent(ctx, RecordService::class.java))
        if (!ok || f == null || !f.exists() || f.length() == 0L) { f?.delete(); return null }
        return f to duration
    }

    fun cancel(ctx: Context) {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        file?.delete()
        recorder = null; file = null; active = false; marks.clear(); note = ""
        ctx.stopService(Intent(ctx, RecordService::class.java))
    }
}

/** Keeps the microphone running with the screen off or another app open. */
class RecordService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> begin()
            MARK -> Recording.mark()
            STOP -> Recording.stopAndSave(this)
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val mark = PendingIntent.getService(this, 2, Intent(this, RecordService::class.java).setAction(MARK), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 3, Intent(this, CaptureActivity::class.java).setAction(CaptureActivity.ACTION).putExtra("mode", "voice"), PendingIntent.FLAG_IMMUTABLE)
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, Notifications.CH_RECORD) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Recording")
            .setUsesChronometer(true)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Mark moment", mark).build())
            .addAction(Notification.Action.Builder(null, "Stop recording", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(Notifications.ID_RECORD, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(Notifications.ID_RECORD, n)

        if (Recording.recorder != null) return
        val f = App.instance.repo.newFile("aac")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(96_000)
            r.setAudioChannels(1)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            f.delete()
            stopSelf()
            return
        }
        Recording.recorder = r
        Recording.file = f
        Recording.startedAt = SystemClock.elapsedRealtime()
        Recording.marks.clear()
        Recording.active = true
    }

    companion object {
        const val START = "start"
        const val STOP = "stop"
        const val MARK = "mark"
    }
}
