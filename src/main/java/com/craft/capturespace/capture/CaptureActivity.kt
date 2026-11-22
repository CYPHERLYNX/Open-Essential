package com.craft.capturespace.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.craft.capturespace.App
import com.craft.capturespace.shortcuts.KeyService
import com.craft.capturespace.shortcuts.KeyTiming
import com.craft.capturespace.ui.*
import com.craft.capturespace.ui.Icon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Quick capture, shown over whatever is on screen. Every shortcut opens it:
 * mode = menu | screenshot | voice | note | photo | gallery.
 */
class CaptureActivity : ComponentActivity() {
    private var mode by mutableStateOf("menu")
    private var photoFile: File? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) Recording.start(this) else { toast("Microphone permission is needed to record"); mode = "menu" }
    }
    /** Asked from the photo screen's mic; recording starts on the next hold, not now. */
    private val micPermission2 = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) toast("Microphone permission is needed for a voice note")
    }
    private val takePhoto =registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = photoFile
        // As in the original (picture 18): show the photo framed in red with an "Add a note" bar.
        if (ok && f != null && f.length() > 0) mode = "photo_note"
        else { f?.delete(); mode = "menu" }
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) { mode = "menu"; return@registerForActivityResult }
        val ctx = applicationContext
        App.instance.scope.launch { Importer.imageUri(ctx, uri) }
        toast("Saved to Open Essential"); finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        // A capture started from the notification shortcut: the same short vibration as the other shortcuts.
        if (savedInstanceState == null) intent?.getStringExtra(EXTRA_HAPTIC)?.let { com.craft.capturespace.shortcuts.Haptics.shortcut(this, it) }
        // Android may close this app while the camera is open; the camera's answer then arrives
        // in a new instance, which must still know which file the photo was written to.
        val restoredPhoto = savedInstanceState?.getString(KEY_PHOTO)?.let { File(it) }
        if (restoredPhoto != null) { photoFile = restoredPhoto; mode = savedInstanceState.getString(KEY_MODE) ?: "busy" }
        else handle(intent)
        setContent {
            SpaceTheme(App.instance.settings.theme) { Sheet() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        photoFile?.let { outState.putString(KEY_PHOTO, it.absolutePath); outState.putString(KEY_MODE, mode) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() { super.onResume(); App.visible = true }
    override fun onPause() { super.onPause(); App.visible = false }

    private fun handle(i: Intent?) {
        val m = i?.getStringExtra("mode") ?: if (i?.action == Intent.ACTION_ASSIST) App.instance.settings.assistAction else "menu"
        start(m)
    }

    private fun start(m: String) {
        when (m) {
            "screenshot" -> screenshot()
            "voice" -> { mode = "voice"; if (!Recording.active) askMicAndRecord() }
            "photo" -> { mode = "busy"; photo() }
            "gallery" -> { mode = "busy"; pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            else -> mode = if (Recording.active) "voice" else m
        }
    }

    private fun screenshot() {
        val ks = KeyService.instance
        if (ks == null || Build.VERSION.SDK_INT < 30) {
            mode = "needs_service"
            return
        }
        finish()
        ks.captureScreen(KeyTiming.SCREENSHOT_DELAY_MS)
    }

    private fun askMicAndRecord() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) Recording.start(this)
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun photo() {
        val f = App.instance.repo.newFile("jpg")
        photoFile = f
        val uri = FileProvider.getUriForFile(this, "com.craft.capturespace.files", f)
        try { takePhoto.launch(uri) } catch (e: Exception) { toast("No camera app found"); mode = "menu" }
    }

    private fun toast(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show()

    @Composable
    private fun Sheet() {
        val t = LocalTones.current
        Box(Modifier.fillMaxSize().clickable(enabled = mode == "menu" || mode == "needs_service") { finish() }, contentAlignment = Alignment.BottomCenter) {
            if (mode == "busy") return@Box
            if (mode == "photo_note") { PhotoNotePanel(); return@Box }
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(10.dp)
                    .clip(RoundedCornerShape(28.dp)).background(t.bg)
                    .clickable(enabled = false) {}.padding(20.dp)
            ) {
                when (mode) {
                    "voice" -> VoicePanel()
                    "note" -> NotePanel()
                    "needs_service" -> ServicePanel()
                    else -> MenuPanel()
                }
            }
        }
    }

    @Composable
    private fun MenuPanel() {
        val t = LocalTones.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            DotText("CAPTURE", dot = 3.dp)
            Spacer(Modifier.weight(1f))
            Text("Open space", color = t.text2, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                startActivity(Intent(this@CaptureActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish()
            }.padding(8.dp))
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Big(Ico.Screen, "Screen", Modifier.weight(1f)) { start("screenshot") }
            Big(Ico.Mic, "Voice", Modifier.weight(1f)) { start("voice") }
            Big(Ico.Note, "Note", Modifier.weight(1f)) { start("note") }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Big(Ico.Camera, "Take photo", Modifier.weight(1f)) { start("photo") }
            Big(Ico.Image, "From photos", Modifier.weight(1f)) { start("gallery") }
        }
    }

    @Composable
    private fun Big(icon: Ico, label: String, modifier: Modifier, onClick: () -> Unit) {
        val t = LocalTones.current
        Column(
            modifier.clip(RoundedCornerShape(18.dp)).background(t.card).clickable(onClick = onClick).padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, t.text, 26.dp)
            Spacer(Modifier.height(8.dp))
            Text(label, fontSize = 13.sp, color = t.text)
        }
    }

    /**
     * The photo just taken, framed in red, with the dark "Add a note" bar, as in the reference
     * videos: type a note, or press and hold the mic to talk (the bar turns red with a live
     * waveform and timer; letting go keeps the voice note with the photo). The arrow, or Back,
     * saves it, and the card shrinks up and fades out before the screen closes.
     */
    @Composable
    private fun PhotoNotePanel() {
        val f = photoFile ?: return
        var text by remember { mutableStateOf("") }
        var voice by remember { mutableStateOf<Pair<File, Long>?>(null) }
        var holding by remember { mutableStateOf(false) }
        var leaving by remember { mutableStateOf(false) }
        var recMs by remember { mutableLongStateOf(0L) }
        LaunchedEffect(holding) { recMs = 0L; while (holding) { recMs = Recording.elapsed(); delay(200) } }
        val scope = rememberCoroutineScope()
        val bmp = remember(f) {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, o)
            var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > 1600) s *= 2
            android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
                ?.asImageBitmap()
        }
        // Entry: the picture settles into its frame. Exit: it shrinks upwards and fades.
        val shown = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(Unit) { shown.animateTo(1f, androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
        val save: () -> Unit = save@{
            if (leaving || holding) return@save
            leaving = true
            val v = voice
            Importer.imageFile(this@CaptureActivity, f, text.trim(), audio = v?.first, audioMs = v?.second ?: 0)
            scope.launch {
                shown.animateTo(0f, androidx.compose.animation.core.tween(360, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                toast("Saved to Open Essential"); finish()
            }
        }
        val micGranted = { checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
        androidx.activity.compose.BackHandler { save() }
        DisposableEffect(Unit) { onDispose { if (holding) Recording.cancel(applicationContext) } }
        val p = shown.value
        Column(
            Modifier.fillMaxSize().background(Color(0xFF111111)).statusBarsPadding().navigationBarsPadding().imePadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (bmp != null) androidx.compose.foundation.Image(
                    bmp, contentDescription = "Photo just taken",
                    modifier = Modifier.fillMaxWidth(0.8f).aspectRatio(bmp.width.toFloat() / bmp.height)
                        .graphicsLayer {
                            val s = if (leaving) 0.55f + 0.45f * p else 1.06f - 0.06f * p
                            scaleX = s; scaleY = s; alpha = p
                            translationY = if (leaving) -(1f - p) * size.height * 0.35f else 0f
                        }
                        .border(3.dp, Palette.Red, RoundedCornerShape(22.dp)).padding(3.dp).clip(RoundedCornerShape(19.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
            Spacer(Modifier.height(16.dp))
            val barColor by androidx.compose.animation.animateColorAsState(if (holding) Palette.Red else Palette.InputBar, label = "bar")
            Row(
                Modifier.fillMaxWidth(if (leaving) 0.4f + 0.6f * p else 1f).heightIn(min = 56.dp)
                    .graphicsLayer { alpha = if (leaving) p else 1f }
                    .clip(RoundedCornerShape(30.dp)).background(barColor).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val ink = Color.White
                Box(
                    Modifier.size(40.dp).clip(CircleShape).pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            if (!micGranted()) { micPermission2.launch(Manifest.permission.RECORD_AUDIO); return@detectTapGestures }
                            voice?.first?.delete(); voice = null
                            holding = true
                            Recording.start(this@CaptureActivity)
                            tryAwaitRelease()
                            // The recorder starts in a service; give it a moment if the press was very short.
                            var waited = 0
                            while (!Recording.active && waited < 1500) { delay(50); waited += 50 }
                            voice = if (Recording.active) Recording.stopToFile(this@CaptureActivity) else { Recording.cancel(this@CaptureActivity); null }
                            holding = false
                            if (voice == null) toast("Hold the mic while you talk")
                        })
                    },
                    contentAlignment = Alignment.Center
                ) { Icon(Ico.Mic, if (holding) ink else Palette.InputText, 20.dp) }
                Box(Modifier.weight(1f).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                    when {
                        holding -> LiveWave(ink, Modifier.fillMaxWidth().height(20.dp))
                        voice != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Voice note " + durationLabel(voice!!.second), color = Palette.InputText, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text("Remove", color = Palette.InputText.copy(alpha = 0.7f), fontSize = 13.sp,
                                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { voice?.first?.delete(); voice = null }.padding(6.dp))
                        }
                        else -> {
                            if (text.isEmpty()) Text("Add a note", color = Palette.InputText.copy(alpha = 0.7f), fontSize = 15.sp)
                            BasicTextField(
                                text, { text = it },
                                textStyle = androidx.compose.ui.text.TextStyle(color = Palette.InputText, fontSize = 15.sp),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.Red), maxLines = 4,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                if (holding) Text("%02d:%02d".format(recMs / 60000, (recMs / 1000) % 60), color = ink, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 8.dp))
                else Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Palette.Red).clickable { save() },
                    contentAlignment = Alignment.Center
                ) { Text("→", color = ink, fontSize = 20.sp, fontWeight = FontWeight.Medium) }
            }
            Spacer(Modifier.height(8.dp))
            Text(if (holding) "Release to keep the voice note" else "Hold the mic to talk · Discard photo",
                color = Color(0xFFAAAAAA), fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = !holding && !leaving) {
                    voice?.first?.delete(); f.delete(); finish()
                }.padding(8.dp))
        }
    }

    /** The live microphone level as bars from the left with a dotted line after them. */
    @Composable
    private fun LiveWave(ink: Color, modifier: Modifier) {
        val levels = remember { mutableStateListOf<Float>() }
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Recording.active) {
            while (Recording.active) { levels.add(Recording.level()); if (levels.size > 400) levels.removeAt(0); tick++; delay(120) }
        }
        androidx.compose.foundation.Canvas(modifier) {
            tick // redraw every sample (also refreshes the timer next to it)
            val step = 4.dp.toPx()
            val slots = (size.width / step).toInt().coerceAtLeast(1)
            val shown = levels.takeLast(slots)
            val mid = size.height / 2
            for (i in 0 until slots) {
                val x = i * step + step / 2
                if (i < shown.size) {
                    val h = (shown[i] * size.height).coerceAtLeast(2.dp.toPx())
                    drawLine(ink, androidx.compose.ui.geometry.Offset(x, mid - h / 2), androidx.compose.ui.geometry.Offset(x, mid + h / 2), 1.6.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                } else drawCircle(ink, 0.9.dp.toPx(), androidx.compose.ui.geometry.Offset(x, mid))
            }
        }
    }

    @Composable
    private fun NotePanel() {
        val t = LocalTones.current
        var text by remember { mutableStateOf("") }
        val fr = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(150); fr.requestFocus() }
        // The original's note bar: a dark pill with a microphone on the left, the text in the
        // middle and a round red arrow on the right that saves it.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(30.dp)).background(Palette.InputBar)
                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { start("voice") }, contentAlignment = Alignment.Center) {
                Icon(Ico.Mic, Palette.InputText, 20.dp)
            }
            Box(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                if (text.isEmpty()) Text("Add a note", color = Palette.InputText.copy(alpha = 0.6f), fontSize = 15.sp)
                androidx.compose.foundation.text.BasicTextField(
                    value = text, onValueChange = { text = it },
                    textStyle = androidx.compose.ui.text.TextStyle(color = Palette.InputText, fontSize = 15.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.Red),
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth().focusRequester(fr)
                )
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (text.isNotBlank()) Palette.Red else Palette.Red.copy(alpha = 0.4f))
                    .clickable(enabled = text.isNotBlank()) {
                        if (Importer.note(this@CaptureActivity, text) != null) toast("Saved to Open Essential")
                        finish()
                    },
                contentAlignment = Alignment.Center
            ) { Text("→", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium) }
        }
        Spacer(Modifier.height(10.dp))
        Text("Cancel", color = t.text2, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { finish() }.padding(8.dp))
    }

    @Composable
    private fun VoicePanel() {
        val t = LocalTones.current
        var elapsed by remember { mutableLongStateOf(0L) }
        val levels = remember { mutableStateListOf<Float>() }
        LaunchedEffect(Recording.active) {
            while (Recording.active) {
                elapsed = Recording.elapsed()
                levels.add(Recording.level())
                if (levels.size > 400) levels.removeAt(0)
                delay(200)
            }
        }
        // The original's recording bar: a red pill with a moving waveform and the time on the right.
        val ink = Color.White
        Row(
            // Slimmer, with a long thin waveform and a small timer, as in picture 20.
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(50)).background(Palette.Red).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // As in picture 17: the real sound so far as bars from the left, a dotted line after it.
            if (Recording.active) androidx.compose.foundation.Canvas(Modifier.weight(1f).height(20.dp)) {
                val step = 4.dp.toPx()
                val slots = (size.width / step).toInt().coerceAtLeast(1)
                val shown = levels.takeLast(slots)
                val mid = size.height / 2
                val bw = 1.6.dp.toPx()
                for (i in 0 until slots) {
                    val x = i * step + step / 2
                    if (i < shown.size) {
                        val h = (shown[i] * size.height).coerceAtLeast(2.dp.toPx())
                        drawLine(ink, androidx.compose.ui.geometry.Offset(x, mid - h / 2), androidx.compose.ui.geometry.Offset(x, mid + h / 2), bw, androidx.compose.ui.graphics.StrokeCap.Round)
                    } else drawCircle(ink, 0.9.dp.toPx(), androidx.compose.ui.geometry.Offset(x, mid))
                }
            }
            else Text("Starting…", color = ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text("%02d:%02d".format(elapsed / 60000, (elapsed / 1000) % 60), color = ink, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Press volume-down once (or the flag) when you hear something important to mark a key moment.",
            fontSize = 12.sp, color = t.text3
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = Recording.note, onValueChange = { Recording.note = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
            placeholder = { Text("Take note") }, shape = RoundedCornerShape(16.dp),
        )
        if (Recording.marks.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Key moments: " + Recording.marks.joinToString("  ") { durationLabel(it) }, fontSize = 12.sp, color = t.text2)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("Discard", { Recording.cancel(this@CaptureActivity); finish() }, filled = false)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(t.card).clickable(enabled = Recording.active) { Recording.mark() },
                contentAlignment = Alignment.Center
            ) { Icon(Ico.Flag, t.text) }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Palette.Red).clickable(enabled = Recording.active) {
                    val id = Recording.stopAndSave(this@CaptureActivity)
                    toast(if (id != null) "Saved to Open Essential" else "No audio detected")
                    finish()
                },
                contentAlignment = Alignment.Center
            ) { Icon(Ico.Stop, Color.White, 26.dp) }
        }
    }

    @Composable
    private fun ServicePanel() {
        val t = LocalTones.current
        Text("Turn on the capture buttons", fontWeight = FontWeight.Medium, fontSize = 18.sp, color = t.text)
        Spacer(Modifier.height(8.dp))
        Text(
            if (Build.VERSION.SDK_INT < 30) "Capturing the screen directly needs Android 11 or later. Take a screenshot as usual and share it to Open Essential instead."
            else com.craft.capturespace.ui.A11Y_DISCLOSURE,
            fontSize = 14.sp, color = t.text2
        )
        Spacer(Modifier.height(16.dp))
        Row {
            Pill("Not now", { finish() }, filled = false)
            Spacer(Modifier.weight(1f))
            if (Build.VERSION.SDK_INT >= 30) Pill("Agree and open settings", {
                startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish()
            })
        }
    }

    companion object {
        const val ACTION = "com.craft.capturespace.action.CAPTURE"
        /** Which shortcut started the capture, for its haptic tick (absent: no vibration). */
        const val EXTRA_HAPTIC = "haptic"
        private const val KEY_PHOTO = "photo_file"
        private const val KEY_MODE = "mode"
    }
}
