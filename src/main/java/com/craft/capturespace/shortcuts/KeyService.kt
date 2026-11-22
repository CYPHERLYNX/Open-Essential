package com.craft.capturespace.shortcuts

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.craft.capturespace.App
import com.craft.capturespace.capture.CaptureActivity
import com.craft.capturespace.capture.Importer
import com.craft.capturespace.capture.Recording
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Timing of the volume-down shortcut; see MECHANICS.md. */
object KeyTiming {
    const val DOUBLE_PRESS_MS = 350L
    const val HOLD_MS = 600L
    const val REPEAT_MS = 120L
    const val SCREENSHOT_DELAY_MS = 450L
}

/**
 * Stands in for the Essential Key on phones without one:
 *  - press volume-down twice quickly → capture the screen (or open the capture sheet),
 *  - press and hold → start a voice recording,
 *  - single press → lowers the volume as usual (or marks a key moment while recording).
 * It also hosts the optional floating capture button.
 */
class KeyService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingSingle = false
    private var secondPress = false
    private var holdFired = false
    private var lastApp: String? = null
    private var floating: View? = null

    private val settings get() = App.instance.settings

    private val singleRunnable = Runnable {
        pendingSingle = false
        if (Recording.active) {
            Recording.mark(); buzz("key moment")
        } else {
            audio().adjustSuggestedStreamVolume(AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
        }
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            audio().adjustSuggestedStreamVolume(AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
            handler.postDelayed(this, KeyTiming.REPEAT_MS)
        }
    }

    private val holdRunnable = Runnable {
        holdFired = true
        pendingSingle = false
        handler.removeCallbacks(singleRunnable)
        if (settings.holdAction == "voice" && !Recording.active) {
            buzz("volume hold")
            open("voice")
        } else {
            repeatRunnable.run()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        settings.prefs.registerOnSharedPreferenceChangeListener(this)
        updateFloating()
    }

    override fun onDestroy() {
        settings.prefs.unregisterOnSharedPreferenceChangeListener(this)
        removeFloating()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (key == "floating") handler.post { updateFloating() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        lastApp = pkg
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (!settings.volumeShortcut || e.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount > 0) return true
                holdFired = false
                if (pendingSingle) {
                    handler.removeCallbacks(singleRunnable)
                    pendingSingle = false
                    secondPress = true
                } else {
                    secondPress = false
                    handler.postDelayed(holdRunnable, KeyTiming.HOLD_MS)
                }
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(holdRunnable)
                handler.removeCallbacks(repeatRunnable)
                when {
                    holdFired -> holdFired = false
                    secondPress -> {
                        secondPress = false
                        buzz("volume double-press")
                        if (settings.doublePressAction == "screenshot") captureScreen(KeyTiming.SCREENSHOT_DELAY_MS / 3)
                        else open("menu")
                    }
                    else -> {
                        pendingSingle = true
                        handler.postDelayed(singleRunnable, KeyTiming.DOUBLE_PRESS_MS)
                    }
                }
            }
        }
        return true
    }

    private fun audio() = getSystemService(AudioManager::class.java)

    fun buzz(reason: String = "shortcut") = Haptics.shortcut(this, reason)

    private fun open(mode: String) {
        startActivity(
            Intent(this, CaptureActivity::class.java).setAction(CaptureActivity.ACTION).putExtra("mode", mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun appLabel(pkg: String?): String? = pkg?.let {
        try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() } catch (_: Exception) { null }
    }

    /** Saves what is on the screen as a capture (Android 11 and later). */
    fun captureScreen(delayMs: Long) {
        if (Build.VERSION.SDK_INT < 30) {
            toast("Screen capture needs Android 11 or later. Use the share menu instead.")
            return
        }
        val source = appLabel(lastApp)
        handler.postDelayed({
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    if (bmp == null) { toast("Couldn't capture the screen"); return }
                    val ctx = applicationContext
                    App.instance.scope.launch { Importer.bitmap(ctx, bmp, source) }
                    toast("Captured to Open Essential")
                }

                override fun onFailure(errorCode: Int) {
                    toast(if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) "Too fast, try again in a moment" else "Couldn't capture the screen ($errorCode)")
                }
            })
        }, delayMs)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- floating capture button ----------

    private fun updateFloating() {
        if (settings.floatingButton) addFloating() else removeFloating()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addFloating() {
        if (floating != null) return
        val wm = getSystemService(WindowManager::class.java)
        val size = (52 * resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val p = getSharedPreferences("floating", Context.MODE_PRIVATE)
            x = p.getInt("x", resources.displayMetrics.widthPixels - size - 24)
            y = p.getInt("y", resources.displayMetrics.heightPixels / 2)
        }
        val v = DotButton(this)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false; var downAt = 0L
        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = lp.x; startY = lp.y; moved = false; downAt = ev.eventTime
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) { lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt(); wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        getSharedPreferences("floating", Context.MODE_PRIVATE).edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                    } else if (ev.eventTime - downAt > 500) {
                        buzz("floating hold")
                        v.visibility = View.INVISIBLE
                        captureScreen(KeyTiming.SCREENSHOT_DELAY_MS)
                        handler.postDelayed({ v.visibility = View.VISIBLE }, KeyTiming.SCREENSHOT_DELAY_MS + 400)
                    } else {
                        buzz("floating tap"); open("menu")
                    }
                }
            }
            true
        }
        v.contentDescription = "Capture. Tap for options, hold to capture the screen."
        wm.addView(v, lp)
        floating = v
    }

    private fun removeFloating() {
        floating?.let { getSystemService(WindowManager::class.java).removeView(it) }
        floating = null
    }

    /** The floating button: a dark disc with a 3x3 dot mark. */
    private class DotButton(ctx: Context) : View(ctx) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 28, 28, 28) }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(242, 242, 242) }
        private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(215, 25, 33) }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            c.drawCircle(w / 2, w / 2, w / 2, bg)
            val step = w / 6f
            for (r in 0..2) for (col in 0..2) {
                val p = if (r == 2 && col == 2) red else dot
                c.drawCircle(w / 2 + (col - 1) * step, w / 2 + (r - 1) * step, w / 22f, p)
            }
        }
    }

    companion object {
        @Volatile
        var instance: KeyService? = null
            private set
    }
}
