package com.craft.capturespace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craft.capturespace.App
import com.craft.capturespace.ai.LocalAi
import com.craft.capturespace.ai.ModelFile
import com.craft.capturespace.ai.ModelState
import com.craft.capturespace.ai.Models
import java.text.NumberFormat
import kotlinx.coroutines.launch

/** Download, check and delete the on-device AI models. Nothing downloads unless the person taps. */
@Composable
fun ModelsScreen(nav: Nav) {
    val t = LocalTones.current
    val ctx = LocalContext.current
    val s = App.instance.settings
    var wifiOnly by remember { mutableStateOf(s.modelsWifiOnly) }
    var importMsg by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    var showNotices by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Models.refresh(ctx) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) { importMsg = "Copying and checking the file…"; Models.import(ctx, uri) { importMsg = it; tick++ } }
    }
    val used = remember(tick, Models.state.toMap()) { Models.diskUsed(ctx) }
    val retired = remember(tick) { Models.retiredPresent(ctx) }

    Column(Modifier.fillMaxSize().background(t.bg)) {
        TopBar("On-device AI", nav)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(
                "On-device models run entirely on this phone. Your captures never leave the device.",
                color = t.text, fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp)
            )
            Text(
                "Total download: ${bytes(Models.TOTAL_BYTES)} (${NumberFormat.getInstance().format(Models.TOTAL_BYTES)} bytes). " +
                    "Each file is checked for its exact size and SHA-256 fingerprint before it is used.",
                color = t.text2, fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.card).clickable { wifiOnly = !wifiOnly; s.modelsWifiOnly = wifiOnly }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Download over Wi-Fi only", color = t.text)
                    Text("Turn off to use mobile data.", color = t.text3, fontSize = 12.sp)
                }
                Switch(wifiOnly, { wifiOnly = it; s.modelsWifiOnly = it }, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Red, checkedThumbColor = Color.White))
            }
            Spacer(Modifier.height(12.dp))
            Models.ALL.forEach { ModelRow(it); Spacer(Modifier.height(10.dp)) }
            Pill("Download all", { Models.ALL.forEach { Models.download(ctx, it) } }, Modifier.fillMaxWidth(),
                enabled = Models.ALL.any { Models.state[it.name] !is ModelState.Ready && Models.state[it.name] !is ModelState.Downloading })
            Spacer(Modifier.height(8.dp))
            Pill("Import from storage", { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), filled = false)
            importMsg?.let { Text(it, color = t.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(12.dp))
            Text(
                "Model files on this phone now: ${bytes(used)} (${NumberFormat.getInstance().format(used)} bytes)." +
                    if (retired.isNotEmpty()) " This includes ${retired.size} file(s) from the earlier model set, so it can exceed 1 GB during the switch; they are removed only after the new set works." else "",
                color = t.text2, fontSize = 13.sp
            )
            Spacer(Modifier.height(10.dp))
            Text("Engine: ${LocalAi.statusLine}", color = t.text2, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Pill(if (testing) "Checking… (this can take a minute)" else "Check the engine", {
                testing = true; testMsg = null
                App.instance.scope.launch {
                    val r = try { LocalAi.selfTest(ctx) } catch (e: com.craft.capturespace.ai.AiException) { "Did not pass: ${e.userMessage}" } catch (e: Throwable) { "Did not pass: ${e.message ?: e.javaClass.simpleName}" }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { testMsg = r; testing = false; tick++ }
                }
            }, Modifier.fillMaxWidth(), filled = false, enabled = LocalAi.ready && !testing)
            testMsg?.let { Text(it, color = t.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            Text(
                "The check runs a short text question and reads a picture with printed words, on this phone, and shows the model's own answers and timings.",
                color = t.text3, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
            )
            if (retired.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Pill("Remove old model files (${bytes(retired.sumOf { it.length() })})", {
                    val gone = Models.deleteRetired(ctx)
                    importMsg = if (gone.isEmpty()) "Nothing was removed." else "Removed: " + gone.joinToString()
                    tick++
                }, Modifier.fillMaxWidth(), filled = false, danger = true, enabled = LocalAi.proven)
                Text(
                    if (LocalAi.proven) "The new models passed the check on this phone, so the old set (LFM2-VL-450M and Whisper tiny) can go."
                    else "The old set (LFM2-VL-450M and Whisper tiny) stays until the new models pass the check on this phone.",
                    color = t.text3, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Supported shared files: " + com.craft.capturespace.capture.Documents.SUPPORTED.joinToString() + ". Office documents aren't supported. " +
                    "Pictures are read by the model itself (there is no separate text recogniser). Speech is transcribed; music and other sounds aren't understood.",
                color = t.text3, fontSize = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Small on-device model (Qwen3.5-0.8B). Results can be wrong. Speed depends on your phone.",
                color = t.text3, fontSize = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Licences: Qwen3.5-0.8B by the Qwen team, Apache License 2.0 (GGUF files by Unsloth). Whisper small model by OpenAI, MIT licence, in whisper.cpp's format. " +
                    "Engines: llama.cpp and whisper.cpp, MIT licence. Not Nothing's or Google's AI, and not endorsed by any of them.",
                color = t.text3, fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            Pill(if (showNotices) "Hide licence texts" else "Show licence texts", { showNotices = !showNotices }, Modifier.fillMaxWidth(), filled = false)
            if (showNotices) {
                val notices = remember { try { ctx.assets.open("NOTICES.txt").bufferedReader().readText() } catch (_: Exception) { "NOTICES.txt is missing from this build." } }
                Text(notices, color = t.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "If the AI writes something offensive or harmful, use Share on that capture to send it to the developer, or delete it. " +
                    "Nothing is reported automatically.",
                color = t.text3, fontSize = 12.sp
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModelRow(m: ModelFile) {
    val t = LocalTones.current
    val ctx = LocalContext.current
    val st = Models.state[m.name] ?: ModelState.Missing
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.card).padding(14.dp)) {
        Text(m.name, color = t.text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Text("${m.purpose} · ${bytes(m.bytes)}", color = t.text2, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        when (st) {
            is ModelState.Downloading -> {
                LinearProgressIndicator(progress = { st.done.toFloat() / st.total }, modifier = Modifier.fillMaxWidth(), color = t.text, trackColor = t.divider)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${bytes(st.done)} of ${bytes(st.total)}", color = t.text2, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Pill("Cancel", { Models.cancel(m) }, filled = false)
                }
            }
            ModelState.Verifying -> Text("Checking the file…", color = t.text2, fontSize = 12.sp)
            ModelState.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Downloaded and verified", color = t.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Pill("Delete", { Models.delete(ctx, m) }, filled = false, danger = true)
            }
            ModelState.Missing -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Not downloaded", color = t.text3, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Pill("Download", { Models.download(ctx, m) })
            }
            is ModelState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(st.message, color = Palette.Red, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Pill("Retry", { Models.download(ctx, m) })
                Spacer(Modifier.width(6.dp))
                Pill("Delete", { Models.delete(ctx, m) }, filled = false, danger = true)
            }
        }
    }
}

private fun bytes(n: Long): String = if (n >= 1_000_000) "%.0f MB".format(n / 1_000_000.0) else "%.0f KB".format(n / 1_000.0)
