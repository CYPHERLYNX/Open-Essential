package com.craft.capturespace.shortcuts

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.craft.capturespace.App
import com.craft.capturespace.capture.CaptureActivity

/** Quick Settings tile (also reachable from the lock screen's shade). */
class CaptureTile : TileService() {
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        Haptics.shortcut(this, "tile")
        val i = Intent(this, CaptureActivity::class.java).setAction(CaptureActivity.ACTION)
            .putExtra("mode", "menu").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val go = {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
        }
        if (isLocked) unlockAndRun { go() } else go()
    }
}

/**
 * Power button: when this app is chosen as the phone's "digital assistant", pressing and
 * holding the power button (Android 12+ "Hold for assistant" setting) opens quick capture.
 */
class AssistService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

class AssistSession(service: VoiceInteractionSessionService) : VoiceInteractionSession(service) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val mode = App.instance.settings.assistAction
        if (mode == "screenshot") {
            val ks = KeyService.instance
            hide()
            if (ks != null) { ks.buzz("assistant"); ks.captureScreen(KeyTiming.SCREENSHOT_DELAY_MS); return }
        }
        Haptics.shortcut(context, "assistant")
        val i = Intent(context, CaptureActivity::class.java).setAction(CaptureActivity.ACTION)
            .putExtra("mode", if (mode == "screenshot") "menu" else mode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 23) startAssistantActivity(i) else context.startActivity(i)
        hide()
    }
}

/**
 * Required companion of an assistant app. Open Essential doesn't offer speech-to-text to other
 * apps, so it politely tells callers it is unavailable.
 */
class AssistRecognition : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
