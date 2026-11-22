package com.craft.capturespace.shortcuts

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.craft.capturespace.App

/**
 * Soft haptics ("Haptic feedback" in Settings).
 *
 * - [tap]: a light tick for buttons, switches, chips and tabs inside the app.
 * - [shortcut]: a soft, rounded click when a capture shortcut is recognised (volume key, tile,
 *   notification, assistant). Tagged as accessibility feedback so Android plays it while the key
 *   service runs in the background.
 *
 * On phones with a modern actuator (Android 11+ primitives) these are short, low-intensity
 * primitives; elsewhere a very short, low-amplitude pulse. Needs only the VIBRATE permission.
 */
object Haptics {
    private const val TAG = "OE-haptic"
    @Volatile private var lastTap = 0L

    fun tap(ctx: Context) {
        if (!App.instance.settings.haptics) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTap < 40) return // a burst of taps should not feel like a buzz
        lastTap = now
        play(ctx, "tap", soft = true, background = false)
    }

    fun shortcut(ctx: Context, reason: String) {
        if (!App.instance.settings.haptics) { Log.i(TAG, "skipped ($reason): turned off in Settings"); return }
        play(ctx, reason, soft = false, background = true)
    }

    private fun play(ctx: Context, reason: String, soft: Boolean, background: Boolean) {
        val v = vibrator(ctx)
        if (v == null || !v.hasVibrator()) { Log.i(TAG, "skipped ($reason): no vibrator"); return }
        try {
            val (effect, kind) = effect(v, soft)
            if (Build.VERSION.SDK_INT >= 33) {
                val usage = if (background) VibrationAttributes.USAGE_ACCESSIBILITY else VibrationAttributes.USAGE_TOUCH
                v.vibrate(effect, VibrationAttributes.createForUsage(usage))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, AudioAttributes.Builder()
                    .setUsage(if (background) AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            }
            if (!soft) Log.i(TAG, "vibrate $kind ($reason)")
        } catch (e: Exception) {
            Log.w(TAG, "vibrate failed ($reason): ${e.javaClass.simpleName}")
        }
    }

    private fun effect(v: Vibrator, soft: Boolean): Pair<VibrationEffect, String> {
        if (Build.VERSION.SDK_INT >= 30) {
            val p = if (soft) VibrationEffect.Composition.PRIMITIVE_TICK else VibrationEffect.Composition.PRIMITIVE_CLICK
            if (v.areAllPrimitivesSupported(p)) {
                val c = VibrationEffect.startComposition().addPrimitive(p, if (soft) 0.55f else 0.7f)
                return c.compose() to (if (soft) "tick" else "click") + " primitive"
            }
        }
        val ms = if (soft) 12L else 28L
        val amp = if (v.hasAmplitudeControl()) (if (soft) 70 else 140) else VibrationEffect.DEFAULT_AMPLITUDE
        return VibrationEffect.createOneShot(ms, amp) to "$ms ms pulse"
    }

    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
}
