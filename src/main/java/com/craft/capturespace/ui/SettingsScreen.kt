package com.craft.capturespace.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon as AndroidIcon
import android.net.Uri
import android.os.Build
import android.provider.Settings as SysSettings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.craft.capturespace.App
import com.craft.capturespace.R
import com.craft.capturespace.ai.LocalAi
import com.craft.capturespace.ai.Pipeline
import com.craft.capturespace.capture.CaptureActivity
import com.craft.capturespace.data.Notifications
import com.craft.capturespace.data.Reminders
import com.craft.capturespace.shortcuts.CaptureTile
import com.craft.capturespace.shortcuts.KeyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun SettingsScreen(nav: Nav, onTheme: (Int) -> Unit) {
    val t = LocalTones.current
    val ctx = LocalContext.current
    val s = App.instance.settings
    val scope = rememberCoroutineScope()

    // Re-read system state (accessibility, assistant) whenever we come back to this screen.
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val serviceOn = remember(tick) { keyServiceEnabled(ctx) }
    val assistantOn = remember(tick) { isDefaultAssistant(ctx) }

    var linkPreviews by remember { mutableStateOf(s.linkPreviews == "on") }
    var auto by remember { mutableStateOf(s.autoAnalyze) }
    var theme by remember { mutableIntStateOf(s.theme) }
    var filters by remember { mutableStateOf(s.showFilters) }
    var haptics by remember { mutableStateOf(s.haptics) }
    var volume by remember { mutableStateOf(s.volumeShortcut) }
    var dbl by remember { mutableStateOf(s.doublePressAction) }
    var hold by remember { mutableStateOf(s.holdAction) }
    var floating by remember { mutableStateOf(s.floatingButton) }
    var disclose by remember { mutableStateOf(false) }
    var notif by remember { mutableStateOf(s.notificationShortcut) }
    var assist by remember { mutableStateOf(s.assistAction) }
    var reminders by remember { mutableStateOf(s.reminders) }
    var lead by remember { mutableIntStateOf(s.reminderLeadMinutes) }

    Column(Modifier.fillMaxSize().background(t.bg)) {
        TopBar("Settings", nav)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {

            // ---------------- Profile ----------------
            Group("Profile picture") {
                AvatarPicker()
            }

            // ---------------- AI ----------------
            Group("AI analysis") {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(t.card2).padding(12.dp)) {
                    Column {
                        Text(if (LocalAi.ready && LocalAi.proven) "On-device AI: ready" else if (LocalAi.ready) "On-device AI: installed, not checked yet" else "On-device AI: not set up", color = t.text, fontWeight = FontWeight.Medium)
                        Text(LocalAi.statusLine, color = t.text2, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Pill("Manage on-device models", { nav.push(Screen.Models) }, Modifier.fillMaxWidth(), filled = false)
                Spacer(Modifier.height(6.dp))
                Toggle("Analyse new captures automatically", "Runs on this phone. Results may be wrong.", auto) { auto = it; s.autoAnalyze = it }
            }

            // ---------------- Shortcuts ----------------
            Group("Capture shortcuts") {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(t.card2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Key shortcut service", color = t.text, fontWeight = FontWeight.Medium)
                        Text(if (serviceOn) "On" else "Needed for volume-down, floating button and screen capture", color = t.text2, fontSize = 12.sp)
                    }
                    Pill(if (serviceOn) "Manage" else "Turn on", {
                        if (serviceOn) open(ctx, Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS)) else disclose = true
                    }, filled = !serviceOn)
                }
                if (!serviceOn && android.os.Build.VERSION.SDK_INT >= 33) {
                    // Android 13+ blocks accessibility for apps installed from a file until the
                    // person allows it themselves in App info. This only explains Android's own steps.
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.YellowTint).padding(12.dp)) {
                        Text("Seeing \"App was denied access\" or \"Restricted setting\"?", color = Palette.InputText, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text(RESTRICTED_STEPS, color = Palette.InputText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.padding(top = 8.dp)) {
                            Pill("Open app info", {
                                open(ctx, Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
                            })
                            Spacer(Modifier.width(8.dp))
                            Pill("Accessibility", { open(ctx, Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS)) }, filled = false)
                        }
                    }
                }
                if (disclose) A11yDisclosureDialog(
                    onAccept = { disclose = false; open(ctx, Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onDismiss = { disclose = false },
                )
                Spacer(Modifier.height(6.dp))
                Toggle("Volume-down shortcut", "Double-press volume-down quickly, or press and hold it.", volume) { volume = it; s.volumeShortcut = it }
                if (volume) {
                    Choice("Double-press", dbl, listOf("screenshot" to "Capture the screen", "open" to "Open the capture menu")) { dbl = it; s.doublePressAction = it }
                    Choice("Press and hold", hold, listOf("voice" to "Record a voice capture", "none" to "Nothing (normal volume)")) { hold = it; s.holdAction = it }
                }
                Toggle("Floating button", "Tap for menu, hold to capture, drag to move.", floating) { floating = it; s.floatingButton = it }
                Toggle("Notification shortcut", "A quiet notification with Screen, Voice and Note buttons.", notif) {
                    notif = it; s.notificationShortcut = it
                    if (it) Notifications.showShortcut(ctx) else Notifications.hideShortcut(ctx)
                }
                Spacer(Modifier.height(6.dp))
                ActionRow("Quick Settings tile", "Add the Capture tile to your quick settings panel.", "Add tile") { requestTile(ctx) }
                ActionRow("Home screen shortcuts", "Pin a capture shortcut to your home screen.", "Pin") { pinShortcut(ctx) }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(t.card2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Power button / assistant", color = t.text, fontWeight = FontWeight.Medium)
                        Text(
                            if (assistantOn) "On. Hold the power button to capture."
                            else "Set as digital assistant to capture with the power button.",
                            color = t.text2, fontSize = 12.sp
                        )
                    }
                    Pill(if (assistantOn) "Manage" else "Set up", { openAssistantSettings(ctx) }, filled = !assistantOn)
                }
                Choice("Assistant opens", assist, listOf("menu" to "The capture menu", "screenshot" to "Capture the screen", "voice" to "Voice recording", "note" to "A new note")) { assist = it; s.assistAction = it }
                Toggle("Haptic feedback", "A soft tap on buttons and shortcuts.", haptics) { haptics = it; s.haptics = it }
            }

            // ---------------- Photos ----------------
            Group("Photos") {
                var mediaLoc by remember(tick) { mutableStateOf(Build.VERSION.SDK_INT < 29 || ctx.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
                val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { mediaLoc = it }
                ActionRow("Photo locations",
                    if (mediaLoc) "On. Shows where a photo was taken."
                    else "Show where a photo was taken. Android asks for photos access.",
                    if (mediaLoc) "Manage" else "Allow") {
                    if (mediaLoc) open(ctx, Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
                    else ask.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
                }
            }

            // ---------------- Links ----------------
            Group("Links") {
                Toggle("Fetch previews for links I share",
                    "Reads the public page only. Private or login posts are skipped.",
                    linkPreviews) { linkPreviews = it; s.linkPreviews = if (it) "on" else "off" }
            }

            // ---------------- Tasks ----------------
            Group("Tasks and events") {
                Toggle("Reminders", "Notify me before events and tasks with a time.", reminders) {
                    reminders = it; s.reminders = it
                    if (it && Build.VERSION.SDK_INT >= 31) {
                        val am = ctx.getSystemService(android.app.AlarmManager::class.java)
                        if (!am.canScheduleExactAlarms()) open(ctx, Intent(SysSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)))
                    }
                    Reminders.rescheduleAll(ctx)
                }
                if (reminders) Choice("Remind me", lead.toString(), listOf("0" to "At the time", "5" to "5 minutes before", "10" to "10 minutes before", "30" to "30 minutes before", "60" to "1 hour before")) {
                    lead = it.toInt(); s.reminderLeadMinutes = lead; Reminders.rescheduleAll(ctx)
                }
            }

            // ---------------- Look ----------------
            Group("Display") {
                Choice("Theme", theme.toString(), listOf("0" to "Follow system", "1" to "Light", "2" to "Dark")) { theme = it.toInt(); s.theme = theme; onTheme(theme) }
                Toggle("Show filters in Library", "Screenshots, Voice and Notes filters above your captures.", filters) { filters = it; s.showFilters = it }
            }

            // ---------------- Storage ----------------
            Group("Storage") {
                Text("Everything stays on this phone.", color = t.text2, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                ActionRow("Trash", "Deleted captures stay here for 30 days.", "Open") { nav.push(Screen.Trash) }
            }

            Group("About") {
                Text("Open Essential " + versionName(ctx), color = t.text)
                Text("Independent app. Not affiliated with any phone maker.", color = t.text2, fontSize = 13.sp)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}


/** Android's own steps to allow a restricted setting for an app installed from a file. */
private const val RESTRICTED_STEPS = """Android blocks this for apps installed from a file until you allow it:
1. Try to turn on "Open Essential buttons" in Accessibility once, and close the message.
2. Tap Open app info, then the ⋮ menu at the top right, then "Allow restricted settings", and confirm with your PIN or fingerprint.
3. Go back to Accessibility, open "Open Essential buttons" and turn it on.
If you don't see the ⋮ menu, do step 1 first; it only appears after Android has blocked the app once."""

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTones.current
    Spacer(Modifier.height(18.dp))
    SectionLabel(title, Modifier.padding(start = 4.dp, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(t.card).padding(16.dp), content = content)
}

@Composable
private fun Toggle(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalTones.current
    val ctx = LocalContext.current
    val change: (Boolean) -> Unit = { com.craft.capturespace.shortcuts.Haptics.tap(ctx); onChange(it) }
    Row(Modifier.fillMaxWidth().clickable { change(!value) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = t.text)
            sub?.let { Text(it, color = t.text3, fontSize = 12.sp) }
        }
        Spacer(Modifier.width(10.dp))
        Switch(value, change, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Red, checkedThumbColor = androidx.compose.ui.graphics.Color.White))
    }
}

@Composable
private fun Choice(title: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    val t = LocalTones.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = t.text, modifier = Modifier.weight(1f))
            Text(options.firstOrNull { it.first == value }?.second ?: value, color = t.text2, fontSize = 13.sp)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (k, label) ->
                DropdownMenuItem(text = { Text(label, fontWeight = if (k == value) FontWeight.Bold else FontWeight.Normal) }, onClick = { open = false; if (k != value) onPick(k) })
            }
        }
    }
}

@Composable
private fun ActionRow(title: String, sub: String, button: String, onClick: () -> Unit) {
    val t = LocalTones.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = t.text)
            Text(sub, color = t.text3, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Pill(button, onClick, filled = false)
    }
}

private fun open(ctx: Context, i: Intent) {
    try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (e: Exception) { Toast.makeText(ctx, "This setting isn't available on this phone.", Toast.LENGTH_SHORT).show() }
}

fun keyServiceEnabled(ctx: Context): Boolean {
    if (KeyService.instance != null) return true
    val enabled = SysSettings.Secure.getString(ctx.contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val me = ComponentName(ctx, KeyService::class.java).flattenToString()
    return enabled.split(':').any { it.equals(me, ignoreCase = true) }
}

private fun isDefaultAssistant(ctx: Context): Boolean {
    val v = SysSettings.Secure.getString(ctx.contentResolver, "voice_interaction_service") ?: return false
    return v.startsWith(ctx.packageName + "/")
}

private fun openAssistantSettings(ctx: Context) {
    val tries = listOf(
        Intent(SysSettings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(SysSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(SysSettings.ACTION_SETTINGS),
    )
    for (i in tries) {
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
    }
}

private fun requestTile(ctx: Context) {
    if (Build.VERSION.SDK_INT >= 33) {
        val sbm = ctx.getSystemService(StatusBarManager::class.java)
        sbm.requestAddTileService(
            ComponentName(ctx, CaptureTile::class.java), ctx.getString(R.string.tile_label),
            AndroidIcon.createWithResource(ctx, R.drawable.ic_tile), ctx.mainExecutor
        ) { code ->
            val msg = when (code) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Tile added."
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "The tile is already in quick settings."
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Tile not added."
                else -> "Open quick settings, tap the edit (pencil) button and drag the Capture tile in."
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(ctx, "Open quick settings, tap the edit (pencil) button and drag the Capture tile in.", Toast.LENGTH_LONG).show()
    }
}

private fun pinShortcut(ctx: Context) {
    val sm = ctx.getSystemService(ShortcutManager::class.java)
    if (sm == null || !sm.isRequestPinShortcutSupported) {
        Toast.makeText(ctx, "Your launcher doesn't support pinned shortcuts. Long-press the app icon instead.", Toast.LENGTH_LONG).show(); return
    }
    val intent = Intent(CaptureActivity.ACTION).setClass(ctx, CaptureActivity::class.java).putExtra("mode", "menu")
    val info = ShortcutInfo.Builder(ctx, "pin_capture")
        .setShortLabel("Capture")
        .setLongLabel("Capture to space")
        .setIcon(AndroidIcon.createWithResource(ctx, R.mipmap.ic_launcher))
        .setIntent(intent)
        .build()
    sm.requestPinShortcut(info, null)
}

private fun versionName(ctx: Context): String = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
} catch (_: Exception) { "" }
