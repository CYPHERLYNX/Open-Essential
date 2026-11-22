package com.craft.capturespace.ui

import android.content.Intent
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craft.capturespace.App
import com.craft.capturespace.ai.Pipeline
import com.craft.capturespace.data.Card
import com.craft.capturespace.data.Status
import com.craft.capturespace.data.TYPE_IMAGE
import com.craft.capturespace.data.TYPE_NOTE
import com.craft.capturespace.data.TYPE_VOICE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DetailScreen(nav: Nav, id: Long) {
    val t = LocalTones.current
    val ctx = LocalContext.current
    val repo = App.instance.repo
    val cardState by repoState(id) { it.card(id) }
    val card = cardState ?: return Box(Modifier.fillMaxSize().background(t.bg))
    val items by repoState(id) { it.itemsForCard(id) }
    val related by repoState(id, card.embedding?.size) { Pipeline.related(card) }
    val inCollections by repoState(id) { r -> r.cardCollectionIds(id).mapNotNull { r.collection(it) } }
    var menu by remember { mutableStateOf(false) }
    var addTo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editingTitle by remember { mutableStateOf(false) }
    var voiceTab by remember { mutableIntStateOf(0) }
    var sharing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // As in the original: back, the capture's title in the middle, and a ⋯ menu.
            IconButton(Ico.Back, { nav.pop() }, description = "Back")
            Text(card.displayTitle, color = t.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f).clickable { editingTitle = true })
            Box {
                IconButton(Ico.More, { menu = true }, description = "More")
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; sharing = true })
                    DropdownMenuItem(text = { Text("Add to collection") }, onClick = { menu = false; addTo = true })
                    DropdownMenuItem(text = { Text("Edit title") }, onClick = { menu = false; editingTitle = true })
                    if (card.status !in setOf(Status.PROCESSING, Status.UPDATING)) DropdownMenuItem(text = { Text("Analyse again") }, onClick = { menu = false; Pipeline.reanalyse(ctx, id) })
                    DropdownMenuItem(text = { Text("Save as Markdown") }, onClick = { menu = false; Export.saveMarkdown(ctx, card) })
                    DropdownMenuItem(text = { Text("Save as PDF") }, onClick = { menu = false; Export.savePdf(ctx, card) })
                    if (card.type == TYPE_IMAGE) DropdownMenuItem(text = { Text("Save image to Photos") }, onClick = { menu = false; Export.saveImage(ctx, card) })
                    if (card.type == TYPE_VOICE) DropdownMenuItem(text = { Text("Save recording") }, onClick = { menu = false; Export.saveAudio(ctx, card) })
                    DropdownMenuItem(text = { Text("Delete capture", color = Palette.Red) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(6.dp))
            when (card.type) {
                TYPE_IMAGE -> {
                    card.file?.let { CaptureImage(it, 1600, Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)), crop = false, keepRatio = true) }
                    // The voice note held down on the capture screen, with its transcript once analysed.
                    card.audio?.takeIf { java.io.File(it).exists() }?.let { a ->
                        var showText by remember(card.id) { mutableStateOf(false) }
                        Spacer(Modifier.height(10.dp))
                        Player(a, card.audioMs, emptyList(), onTranscript = { showText = !showText })
                        if (showText) {
                            Spacer(Modifier.height(8.dp))
                            Text(card.transcript.ifBlank { "No transcript yet: it is made when the capture is analysed." },
                                color = if (card.transcript.isBlank()) t.text3 else t.text, fontSize = 14.sp, lineHeight = 20.sp)
                        }
                    }
                }
                TYPE_VOICE -> card.file?.let { Player(it, card.durationMs, card.marks, onTranscript = { voiceTab = 1 }) }
                else -> {
                    com.craft.capturespace.data.Meta.link(card.meta)?.let { LinkBlock(card, it) }
                    com.craft.capturespace.data.Meta.doc(card.meta)?.let { d ->
                        d.cover?.takeIf { java.io.File(it).exists() }?.let {
                            CaptureImage(it, 900, Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(16.dp)), crop = false, keepRatio = true)
                            Spacer(Modifier.height(10.dp))
                        }
                        // Facts from the file itself: its type, pages and name.
                        Text(listOfNotNull(d.label, d.pages?.let { com.craft.capturespace.data.Meta.count(it, d.unit) }, d.name).joinToString(" · "),
                            color = t.text3, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
            // The original's headline card: the AI summary in a serif face on a white card.
            if (card.hasNotes && card.summary.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(t.card).padding(horizontal = 16.dp, vertical = 18.dp)) {
                    Text(card.summary.substringBefore(". ").let { if (it.endsWith(".")) it else "$it." },
                        color = t.text, fontSize = 20.sp, lineHeight = 27.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                listOfNotNull(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(card.created)),
                    card.sourceApp?.let { "Saved from \"$it\"" }).joinToString(" · "),
                fontSize = 12.sp, color = t.text3
            )
            if (!inCollections.isNullOrEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    inCollections!!.forEach { c -> Chip(c.name) { nav.push(Screen.Collection(c.id)) } }
                }
            }
            Spacer(Modifier.height(16.dp))

            AnalysisState(card)

            if (card.hasNotes) {
                if (card.type == TYPE_VOICE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Summary", voiceTab == 0) { voiceTab = 0 }
                        Chip("Transcript", voiceTab == 1) { voiceTab = 1 }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                if (card.type != TYPE_VOICE || voiceTab == 0) {
                    if (card.summary.isNotBlank()) Block("Summary") { Text(card.summary, color = t.text, fontSize = 15.sp, lineHeight = 22.sp) }
                    if (card.details.isNotEmpty()) Block("Details") {
                        card.details.forEach { d ->
                            Row(Modifier.padding(vertical = 4.dp)) {
                                Text(detailEmoji(d.label)?.let { "$it  ${d.label}" } ?: d.label, color = t.text3, fontSize = 13.sp, modifier = Modifier.width(124.dp))
                                val geo = if (d.label == "Location") com.craft.capturespace.ai.PhotoInfo.parse(d.value) else null
                                if (geo != null) {
                                    // Coordinates from the photo: opened in the person's maps app only when tapped.
                                    Text(d.value, color = Palette.Blue, fontSize = 14.sp, modifier = Modifier.clickable(onClickLabel = "Open in maps") {
                                        try {
                                            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse("geo:${geo.first},${geo.second}?q=${geo.first},${geo.second}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                        } catch (_: Exception) { android.widget.Toast.makeText(ctx, "No maps app found.", android.widget.Toast.LENGTH_SHORT).show() }
                                    })
                                } else SelectionContainer { Text(d.value, color = t.text, fontSize = 14.sp) }
                            }
                        }
                    }
                    if (card.topics.isNotEmpty()) Block("Main Topics") { card.topics.forEach { Bullet(it) } }
                    if (card.actions.isNotEmpty()) Block("Action Items") { card.actions.forEach { Bullet(it) } }
                    if (card.keyMoments.isNotEmpty()) Block(if (card.type == TYPE_VOICE) "Key Moments" else "Key points") { card.keyMoments.forEach { Bullet(it) } }
                    if (!items.isNullOrEmpty()) Block("Suggestions") {
                        items!!.forEach { it2 ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(if (it2.kind == "event") Palette.Red else t.text2))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(it2.title, color = t.text, fontSize = 14.sp)
                                    Text((if (it2.kind == "event") "Event · " else "Task · ") + whenLabel(ctx, it2), color = t.text3, fontSize = 12.sp)
                                }
                                if (it2.suggested) {
                                    // Found by the AI: nothing is created and no reminder is set until tapped.
                                    Pill("Add", { repo.confirmItem(it2.id)?.let { c -> com.craft.capturespace.data.Reminders.schedule(ctx, c) } }, filled = false)
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(Ico.Close, { repo.deleteItem(it2.id) }, description = "Dismiss suggestion")
                                } else if (it2.kind == "event") IconButton(Ico.Calendar, { addToCalendar(ctx, it2) }, description = "Add to calendar")
                                else Checkbox(it2.done, { v -> repo.updateItem(it2.copy(done = v)) })
                            }
                        }
                    }
                    if (card.tags.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            card.tags.forEach { Chip("#$it") }
                        }
                    }
                } else {
                    Block("Transcript") {
                        SelectionContainer { Text(card.transcript.ifBlank { "No voice detected" }, color = t.text2, fontSize = 15.sp, lineHeight = 23.sp) }
                    }
                }
            }

            // A document's text is shown read-only and in parts (a long one is far too big for an edit box).
            if (com.craft.capturespace.data.Meta.doc(card.meta) != null) DocumentText(card) else NoteEditor(card)

            // "Collections" with an "Add to collection" card, as in the original.
            Spacer(Modifier.height(18.dp))
            Text("Collections", color = t.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(t.card).clickable { addTo = true }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(t.chip), contentAlignment = Alignment.Center) { Icon(Ico.Plus, t.text, 18.dp) }
                Spacer(Modifier.width(14.dp))
                Text("Add to collection", color = t.text, fontSize = 15.sp)
            }
            if (card.hasNotes) { Spacer(Modifier.height(14.dp)); AiHint() }

            if (!related.isNullOrEmpty()) {
                Spacer(Modifier.height(18.dp))
                SectionLabel("Related captures")
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    related!!.forEach { r ->
                        Column(Modifier.width(150.dp).clip(RoundedCornerShape(14.dp)).background(t.card).clickable { nav.push(Screen.Detail(r.id)) }) {
                            if (r.type == TYPE_IMAGE && r.file != null) CaptureImage(r.file, 300, Modifier.fillMaxWidth().height(90.dp))
                            Text(r.displayTitle, color = t.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (addTo) AddToCollectionDialog(listOf(id)) { addTo = false }
    if (sharing) ShareSheet(card) { sharing = false }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        containerColor = t.card,
        title = { Text("Delete capture") },
        text = { Text("This capture will move to Trash. You can restore it from Trash within 30 days.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; repo.moveToTrash(id); nav.pop() }) { Text("Delete", color = Palette.Red) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = t.text2) } }
    )
    if (editingTitle) {
        var v by remember { mutableStateOf(card.displayTitle) }
        AlertDialog(
            onDismissRequest = { editingTitle = false },
            containerColor = t.card,
            title = { Text("Edit title") },
            text = { OutlinedTextField(v, { v = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                val changed = v.trim() != card.title.trim()
                repo.setTitle(id, v.trim()); editingTitle = false
                // The new title is saved at once; the notes are made again with it as a label
                // (a few seconds later, so quick successive edits make one analysis).
                if (changed && App.instance.settings.autoAnalyze && card.status !in setOf(Status.NO_KEY, Status.OFF)) Pipeline.reanalyse(ctx, id, 2500)
            }) { Text("Save", color = t.text) } },
            dismissButton = { TextButton(onClick = { editingTitle = false }) { Text("Cancel", color = t.text2) } }
        )
    }
}

@Composable
private fun AnalysisState(card: Card) {
    val t = LocalTones.current
    val ctx = LocalContext.current
    when (card.status) {
        Status.PENDING, Status.PROCESSING -> Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Pulse(); Spacer(Modifier.width(8.dp))
            Text("Processing. We will notify you when content is ready.", color = t.text2, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (card.status == Status.PROCESSING) { Spacer(Modifier.width(8.dp)); Pill("Stop", { Pipeline.stop(card.id) }, filled = false) }
        }
        Status.UPDATING -> Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Pulse(); Spacer(Modifier.width(8.dp))
            Text("Updating… The notes below are from the last analysis.", color = t.text2, fontSize = 14.sp)
        }
        Status.DONE -> card.error?.let { err ->
            // The last re-analysis failed: the notes shown are the earlier ones.
            Row(Modifier.padding(bottom = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.card).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(err, color = t.text2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Pill("Retry", { Pipeline.reanalyse(ctx, card.id) })
            }
        }
        Status.FAILED, Status.OFFLINE -> Column(Modifier.padding(bottom = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.card).padding(14.dp)) {
            Text(if (card.status == Status.OFFLINE) "Waiting" else "Analysis failed", color = t.text, fontWeight = FontWeight.Medium)
            card.error?.let { Text(it, color = t.text2, fontSize = 13.sp) }
            Spacer(Modifier.height(10.dp))
            Pill("Retry", { Pipeline.reanalyse(ctx, card.id) })
        }
        Status.NO_KEY, Status.OFF -> Column(Modifier.padding(bottom = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.card).padding(14.dp)) {
            Text(if (card.status == Status.NO_KEY) "Saved on this phone. Not analysed yet: " + (card.error ?: com.craft.capturespace.ai.LocalAi.statusLine) else "Automatic analysis is off.", color = t.text2, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            Pill("Start AI analysis", { App.instance.repo.setStatus(card.id, Status.PENDING); Pipeline.enqueue(ctx, card.id) }, enabled = com.craft.capturespace.ai.LocalAi.ready)
        }
    }
}

@Composable
private fun Block(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionLabel(title)
    Spacer(Modifier.height(6.dp))
    Column(content = content)
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun Bullet(s: String) {
    val t = LocalTones.current
    Row(Modifier.padding(vertical = 3.dp)) {
        Box(Modifier.padding(top = 8.dp).size(5.dp).clip(CircleShape).background(t.text2))
        Spacer(Modifier.width(10.dp))
        Text(s, color = t.text, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun NoteEditor(card: Card) {
    val t = LocalTones.current
    var text by remember(card.id) { mutableStateOf(card.note) }
    var dirty by remember(card.id) { mutableStateOf(false) }
    SectionLabel(if (card.type == TYPE_NOTE) "Note" else "Your note")
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = text, onValueChange = { text = it; dirty = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = if (card.type == TYPE_NOTE) 160.dp else 70.dp),
        placeholder = { Text("Add a note") }, shape = RoundedCornerShape(14.dp),
    )
    if (dirty) {
        Spacer(Modifier.height(8.dp))
        Row {
            Spacer(Modifier.weight(1f))
            Pill("Save", {
                App.instance.repo.setNote(card.id, text)
                dirty = false
                if (card.type == TYPE_NOTE && App.instance.settings.autoAnalyze) {
                    Pipeline.reanalyse(App.instance, card.id, 1500)
                }
            })
        }
    }
}

@Composable
private fun Player(path: String, durationMs: Long, marks: List<Long>, onTranscript: () -> Unit = {}) {
    val t = LocalTones.current
    val ctx = LocalContext.current
    val player = remember(path) { MediaPlayer() }
    var ready by remember(path) { mutableStateOf(false) }
    var playing by remember(path) { mutableStateOf(false) }
    var pos by remember(path) { mutableIntStateOf(0) }
    var total by remember(path) { mutableIntStateOf(durationMs.toInt()) }
    DisposableEffect(path) {
        try {
            player.setDataSource(path)
            player.setOnPreparedListener { ready = true; if (it.duration > 0) total = it.duration }
            player.setOnCompletionListener { playing = false; pos = 0 }
            player.prepareAsync()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Can't play this recording", Toast.LENGTH_SHORT).show()
        }
        onDispose { player.release() }
    }
    LaunchedEffect(playing) {
        while (playing) { pos = try { player.currentPosition } catch (_: Exception) { 0 }; delay(200) }
    }
    // Laid out like the original (picture 11): a header with a waveform mark, "Transcript ›" and
    // play, then elapsed time, a thin timeline with yellow key-moment ticks, and the time left.
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(t.card).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Waveform(seed = path.hashCode().toLong(), modifier = Modifier.width(18.dp).height(14.dp), color = t.text2)
            Spacer(Modifier.weight(1f))
            Text("Transcript ›", color = t.text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onTranscript).padding(horizontal = 8.dp, vertical = 4.dp))
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(36.dp).clip(CircleShape).clickable(enabled = ready) {
                if (playing) { player.pause(); playing = false } else { player.start(); playing = true }
            }, contentAlignment = Alignment.Center) { Icon(if (playing) Ico.Pause else Ico.Play, t.text, 20.dp) }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(durationLabel(pos.toLong()), fontSize = 11.sp, color = t.text3)
            Spacer(Modifier.width(8.dp))
            BoxWithConstraints(Modifier.weight(1f).height(24.dp), contentAlignment = Alignment.CenterStart) {
                val w = maxWidth
                val frac = if (total > 0) (pos.toFloat() / total).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(2.dp).background(t.divider))
                Box(Modifier.width(w * frac).height(2.dp).background(t.text))
                if (total > 0) marks.forEach { m ->
                    Box(Modifier.offset(x = w * (m.toFloat() / total).coerceIn(0f, 1f)).size(width = 3.dp, height = 10.dp).background(Palette.Red))
                }
                Slider(
                    value = frac,
                    onValueChange = { v -> pos = (v * total).toInt(); if (ready) player.seekTo(pos) },
                    colors = SliderDefaults.colors(thumbColor = Color.Transparent, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("-" + durationLabel((total - pos).coerceAtLeast(0).toLong()), fontSize = 11.sp, color = t.text3)
        }
        if (marks.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                marks.forEach { m -> Chip(durationLabel(m)) { if (ready) { player.seekTo(m.toInt()); pos = m.toInt() } } }
            }
        }
    }
}

/**
 * The original's share sheet: a format row (Image, PDF, Markdown), a black "Share summary"
 * button and "Save summary" below it. Image is the capture itself (the recording for voice).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(card: Card, onClose: () -> Unit) {
    val t = LocalTones.current
    val ctx = LocalContext.current
    val formats = buildList {
        if (card.type == TYPE_IMAGE) add("image" to "Image")
        if (card.type == TYPE_VOICE) add("image" to "Audio")
        add("pdf" to "PDF"); add("markdown" to "Markdown")
    }
    var format by remember { mutableStateOf("pdf") }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = t.bg) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                formats.forEach { (k, label) ->
                    val on = k == format
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(t.card)
                            .then(if (on) Modifier.border(1.5.dp, t.text, RoundedCornerShape(14.dp)) else Modifier)
                            .clickable { format = k }.padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(when (k) { "image" -> if (card.type == TYPE_VOICE) Ico.Mic else Ico.Image; "pdf" -> Ico.Note; else -> Ico.Edit }, t.text, 20.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(label, color = t.text, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Pill("Share summary", { onClose(); Export.shareAs(ctx, card, format) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text("Save summary", color = t.text, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(50))
                .clickable { onClose(); Export.saveAs(ctx, card, format) }.padding(horizontal = 20.dp, vertical = 12.dp))
        }
    }
}

/**
 * A shared link: its public preview (picture, title, site) once read, or why it couldn't be read,
 * with "Fetch preview" when previews were skipped, and the first-time question when not decided yet.
 */
@Composable
private fun LinkBlock(card: Card, l: com.craft.capturespace.data.Meta.Link) {
    val t = LocalTones.current
    val ctx = LocalContext.current
    val open = { try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(l.url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(t.card).clickable { open() }) {
        if (l.ok) l.image?.takeIf { java.io.File(it).exists() }?.let {
            CaptureImage(it, 1200, Modifier.fillMaxWidth().heightIn(max = 260.dp), crop = true)
        }
        Column(Modifier.padding(14.dp)) {
            Text(l.site ?: l.host, color = t.text3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (l.ok && l.title != null) Text(l.title, color = t.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(l.url, color = t.text2, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when {
                l.state == "fetching" -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pulse(); Spacer(Modifier.width(8.dp)); Text("Fetching the public preview…", color = t.text2, fontSize = 13.sp)
                }
                l.failed -> {
                    Text(l.message ?: "Preview not available.", color = t.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    if (l.state in setOf("offline", "timeout", "error", "blocked")) {
                        Spacer(Modifier.height(8.dp)); Pill("Try again", { com.craft.capturespace.capture.LinkPreview.fetchThenAnalyse(ctx, card.id) }, filled = false)
                    }
                }
                l.state == "asked" -> {
                    Text(com.craft.capturespace.capture.LinkConsent.MESSAGE, color = t.text2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.padding(top = 10.dp)) {
                        Pill("Fetch previews", { App.instance.settings.linkPreviews = "on"; com.craft.capturespace.capture.Importer.linkChoice(ctx, card.id, true) })
                        Spacer(Modifier.width(8.dp))
                        Pill("Not now", { App.instance.settings.linkPreviews = "off"; com.craft.capturespace.capture.Importer.linkChoice(ctx, card.id, false) }, filled = false)
                    }
                }
                l.state == "skipped" || l.state == "new" -> {
                    Text("Preview not fetched. Only the shared text was used.", color = t.text3, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(8.dp))
                    Pill("Fetch preview", { com.craft.capturespace.capture.LinkPreview.fetchThenAnalyse(ctx, card.id) }, filled = false)
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

/** The text read from a document: the first part, and more on request (capped; the file itself is kept). */
@Composable
private fun DocumentText(card: Card) {
    val t = LocalTones.current
    var shown by remember(card.id) { mutableIntStateOf(1_500) }
    val text = card.note
    SectionLabel("Document text")
    Spacer(Modifier.height(6.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.card).padding(14.dp)) {
        SelectionContainer { Text(text.take(shown).let { if (text.length > shown) it.trimEnd() + "…" else it }, color = t.text2, fontSize = 13.sp, lineHeight = 19.sp) }
        if (text.length > shown && shown < DOC_TEXT_MAX) {
            Spacer(Modifier.height(8.dp))
            Text("Show more", color = Palette.Blue, fontSize = 13.sp, modifier = Modifier.clickable { shown = minOf(shown * 4, DOC_TEXT_MAX) })
        } else if (text.length > shown) {
            Spacer(Modifier.height(8.dp))
            Text("Showing the first ${shown / 1000}k of ${text.length / 1000}k characters. The full text is still searchable.", color = t.text3, fontSize = 12.sp)
        }
    }
    Spacer(Modifier.height(18.dp))
}

private const val DOC_TEXT_MAX = 24_000

/** A small emoji in front of a detail's label, chosen by code from the label (none when nothing fits). */
internal fun detailEmoji(label: String): String? {
    val l = label.lowercase()
    val table = listOf(
        listOf("taken", "date", "day", "deadline", "due") to "📅",
        listOf("time", "duration", "timeframe", "hour") to "⏰",
        listOf("location", "place", "address", "venue", "city", "area") to "📍",
        listOf("price", "cost", "amount", "total", "budget", "fee", "paid", "lunch") to "💰",
        listOf("people", "person", "attendee", "participant", "organizer", "organiser", "author", "speaker", "name") to "👤",
        listOf("phone", "contact", "mobile") to "📞",
        listOf("email", "mail") to "✉️",
        listOf("link", "website", "url", "site") to "🔗",
        listOf("camera") to "📷",
        listOf("file", "document", "pages", "page", "slides", "sheets") to "📄",
        listOf("object", "item", "product") to "🧩",
        listOf("event", "meeting") to "🗓️",
        listOf("flight", "train", "bus", "travel", "trip") to "✈️",
        listOf("code", "pnr", "booking", "order", "ticket", "reference", "id") to "🔖",
        listOf("text", "texts", "sign") to "🔤",
        listOf("topic", "topics", "subject", "activity", "activities", "category") to "🏷️",
    )
    return table.firstOrNull { (keys, _) -> keys.any { k -> l == k || l == k + "s" || l.split(' ', '_', '-').contains(k) } }?.second
}
