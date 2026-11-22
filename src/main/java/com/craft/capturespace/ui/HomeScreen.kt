@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.craft.capturespace.ui

import android.content.Intent
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craft.capturespace.App
import com.craft.capturespace.capture.CaptureActivity
import com.craft.capturespace.data.Card
import com.craft.capturespace.data.PlanItem
import com.craft.capturespace.data.Reminders
import com.craft.capturespace.data.TYPE_IMAGE
import com.craft.capturespace.data.TYPE_NOTE
import com.craft.capturespace.data.TYPE_VOICE
import com.craft.capturespace.shortcuts.Haptics
import java.util.Calendar
import java.util.Date

@Composable
fun HomeScreen(nav: Nav) {
    val t = LocalTones.current
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
        // No title bar, as in the current original. This phone has no capture key, so a small
        // capture button sits at the top right: tap for the capture menu, hold to record voice.
        // It floats over the page instead of taking a row, so the headline starts right under
        // the status bar as in the original.
        val captureButton: @Composable (Modifier) -> Unit = { m ->
            Box(
                m.padding(end = 10.dp, top = 6.dp).size(40.dp).clip(CircleShape).background(t.chip).combinedClickable(
                    onClick = { Haptics.tap(ctx); ctx.startActivity(Intent(ctx, CaptureActivity::class.java).setAction(CaptureActivity.ACTION).putExtra("mode", "menu")) },
                    onLongClick = { Haptics.tap(ctx); ctx.startActivity(Intent(ctx, CaptureActivity::class.java).setAction(CaptureActivity.ACTION).putExtra("mode", "voice")) },
                    onClickLabel = "New capture", onLongClickLabel = "Record voice"
                ),
                contentAlignment = Alignment.Center
            ) { Icon(Ico.Plus, t.text, 20.dp) }
        }
        // Collections and Tasks open from the section headers, as full pages with a way back.
        if (nav.homeTab >= 2) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(Ico.Back, { nav.homeTab = 0 }, description = "Back")
                Text(if (nav.homeTab == 2) "Collections" else "Tasks", color = t.text, fontSize = 18.sp, maxLines = 1)
            }
        }
        BackHandlerCompat(nav.homeTab != 0) { nav.homeTab = 0 }
        Box(Modifier.weight(1f)) {
            when (nav.homeTab) {
                0 -> Centered(960) { ForYouTab(nav) }
                1 -> LibraryTab(nav)
                2 -> CollectionsTab(nav)
                else -> Centered { TasksTab() }
            }
            if (nav.homeTab == 0) captureButton(Modifier.align(Alignment.TopEnd))
        }
        BottomNav(
            tab = nav.homeTab,
            onTab = { if (nav.homeTab != it) Haptics.tap(ctx); nav.homeTab = it },
            onSettings = { Haptics.tap(ctx); nav.push(Screen.Settings) },
            onSearch = { Haptics.tap(ctx); nav.push(Screen.Search) },
        )
    }
}

@Composable
private fun BackHandlerCompat(enabled: Boolean, onBack: () -> Unit) =
    androidx.activity.compose.BackHandler(enabled, onBack)

/**
 * The bottom bar of the current Essential Space layout: a round button on the left (settings, where
 * the original has the account picture), a pill switching between "For you" and "Library" (the
 * selected side shows its name, the other only its icon), and a round search button on the right.
 */
@Composable
private fun BottomNav(tab: Int, onTab: (Int) -> Unit, onSettings: () -> Unit, onSearch: () -> Unit) {
    val t = LocalTones.current
    // On tablets the bar keeps a phone-like width, centred, instead of stretching across.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Row(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The profile circle: the avatar picked in Settings, or a plain outline.
        AvatarCircle(52.dp, Modifier.clip(CircleShape).clickable(onClickLabel = "Profile and settings", onClick = onSettings)
            .semantics { contentDescription = "Profile and settings" + (Avatars.find(Avatars.current.value)?.let { ", picture: ${it.description}" } ?: "") })
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(50)).background(t.chip).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("For you", "Library").forEachIndexed { i, label ->
                val on = tab == i || (i == 0 && tab >= 2)
                Box(
                    Modifier.weight(if (on) 2f else 1f).fillMaxHeight().clip(RoundedCornerShape(50))
                        .background(if (on) t.card else Color.Transparent).clickable { onTab(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (i == 0) Ico.Sparkle else Ico.Image, if (on) t.text else t.text3, 16.dp)
                        if (on) {
                            Spacer(Modifier.width(8.dp))
                            Text(label, color = t.text, fontSize = 14.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(52.dp).clip(CircleShape).background(t.chip).clickable(onClick = onSearch), contentAlignment = Alignment.Center) {
            Icon(Ico.Search, t.text, 22.dp)
        }
    } }
}

/** The dark "Add a note" bar at the bottom, with photo and voice. */
@Composable
fun CaptureBar(onNote: () -> Unit, onPhoto: () -> Unit, onVoice: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(50)).background(Palette.InputBar).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Add a note", color = Palette.InputText.copy(alpha = 0.7f), fontSize = 15.sp, modifier = Modifier.weight(1f).clickable(onClick = onNote).padding(vertical = 10.dp))
        IconButton(Ico.Camera, onPhoto, tint = Palette.InputText, description = "Capture")
        Box(Modifier.size(42.dp).clip(CircleShape).background(Palette.Red).clickable { Haptics.tap(ctx); onVoice() }, contentAlignment = Alignment.Center) {
            Icon(Ico.Mic, Color.White, 20.dp)
        }
    }
}

@Composable
private fun LibraryTab(nav: Nav) {
    val t = LocalTones.current
    val ctx = LocalContext.current
    val settings = App.instance.settings
    var filter by rememberSaveable { mutableStateOf("all") }
    val cards by repoState { it.cards() }
    val colsState by repoState { it.collections() }
    val cols = colsState.orEmpty()
    val selected = remember { mutableStateListOf<Long>() }
    var addTo by remember { mutableStateOf<List<Long>?>(null) }
    val list = cards ?: return
    val shown = when (filter) {
        "image" -> list.filter { it.type == TYPE_IMAGE }
        "voice" -> list.filter { it.type == TYPE_VOICE }
        "note" -> list.filter { it.type == TYPE_NOTE }
        else -> list
    }
    val suggestion = list.firstOrNull { it.suggestion != null }

    Column {
        if (selected.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(Ico.Close, { selected.clear() }, description = "Cancel")
                Text("${selected.size} selected", color = t.text, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                IconButton(Ico.Folder, { addTo = selected.toList() }, description = "Add to collection")
                IconButton(Ico.Trash, {
                    selected.forEach { App.instance.repo.moveToTrash(it) }
                    Toast.makeText(ctx, "Moved to Trash", Toast.LENGTH_SHORT).show()
                    selected.clear()
                }, description = "Delete")
            }
        } else if (settings.showFilters && list.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("All", filter == "all") { filter = "all" }
                Chip("Images", filter == "image") { filter = "image" }
                Chip("Voice", filter == "voice") { filter = "voice" }
                Chip("Notes", filter == "note") { filter = "note" }
            }
        }
        if (list.isEmpty()) {
            EmptyState("EMPTY", "Double-press volume-down to capture your screen, hold it to record a voice note, or use the bar below. Your captures will appear here.", Modifier.padding(top = 60.dp))
            return@Column
        }
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(gridColumns()),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
            verticalItemSpacing = 10.dp,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // "You've captured 5 images and 9 notes this month." (picture-16, Library page).
            item(span = StaggeredGridItemSpan.FullLine, key = "headline") {
                val monthStart = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val month = list.filter { it.created >= monthStart }
                val parts = listOf(
                    month.count { it.type == TYPE_IMAGE } to "image",
                    month.count { it.type == TYPE_VOICE } to "recording",
                    month.count { it.type == TYPE_NOTE } to "note",
                ).filter { it.first > 0 }
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        if (parts.isEmpty()) append("Nothing captured this month yet.")
                        else {
                            append("You've captured ")
                            parts.forEachIndexed { i, (n, w) ->
                                if (i > 0) append(if (i == parts.lastIndex) " and " else ", ")
                                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold)); append("$n $w" + if (n == 1) "" else "s"); pop()
                            }
                            append(" this month.")
                        }
                    },
                    fontSize = 27.sp, lineHeight = 33.sp, color = t.text,
                    modifier = Modifier.padding(start = 12.dp, end = 40.dp, top = 20.dp, bottom = 6.dp)
                )
            }
            // The original's Library: a row of collection cards (count, name, cover), then captures.
            if (cols.isNotEmpty() && filter == "all" && selected.isEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine, key = "collections") {
                    Column {
                        Row(Modifier.fillMaxWidth().clickable { nav.homeTab = 2 }.padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("COLLECTIONS", color = t.text, fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text("›", color = t.text, fontSize = 16.sp)
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            cols.forEach { c ->
                                Row(
                                    Modifier.width(170.dp).height(72.dp).clip(RoundedCornerShape(14.dp)).background(t.card)
                                        .clickable { nav.push(Screen.Collection(c.id)) }
                                ) {
                                    Column(Modifier.weight(1f).fillMaxHeight().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                        Text("${c.count}", color = t.text3, fontSize = 11.sp)
                                        Text(c.name, color = t.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
                                    }
                                    if (c.coverFile != null) CaptureImage(c.coverFile, 200, Modifier.width(58.dp).fillMaxHeight())
                                }
                            }
                        }
                        Text("CAPTURES", color = t.text, fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 2.dp))
                    }
                }
            }
            if (suggestion != null && filter == "all") {
                item(span = StaggeredGridItemSpan.FullLine, key = "suggestion") { SuggestionBanner(suggestion, nav) }
            }
            items(shown, key = { it.id }) { c ->
                CaptureCard(
                    c, selected = c.id in selected,
                    onClick = {
                        if (selected.isNotEmpty()) { if (c.id in selected) selected.remove(c.id) else selected.add(c.id) }
                        else nav.push(Screen.Detail(c.id))
                    },
                    onLongClick = { if (c.id in selected) selected.remove(c.id) else selected.add(c.id) }
                )
            }
        }
    }
    addTo?.let { ids ->
        AddToCollectionDialog(ids, onDone = { addTo = null; selected.clear() })
    }
}

@Composable
private fun SuggestionBanner(card: Card, nav: Nav) {
    val t = LocalTones.current
    val s = card.suggestion ?: return
    val repo = App.instance.repo
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.card).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ico.Sparkle, Palette.Yellow, 16.dp)
            Spacer(Modifier.width(6.dp))
            SectionLabel("Suggested")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (s.kind == "add") "Add \"${card.displayTitle}\" to your ${s.name} collection?" else "Create a ${s.name} collection?",
            color = t.text, fontSize = 14.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Confirm", {
                if (s.kind == "add") repo.addToCollection(card.id, s.collectionId)
                else {
                    val id = repo.createCollection(s.name)
                    if (id > 0) {
                        repo.addToCollection(card.id, id)
                        // Gather the similar captures that led to the suggestion.
                        card.embedding?.let { e ->
                            repo.embeddings().filter { it.first != card.id && com.craft.capturespace.ai.Similarity.cosine(e, it.second) >= com.craft.capturespace.ai.Limits.SUGGEST_CREATE_MIN }
                                .forEach { repo.addToCollection(it.first, id) }
                        }
                    }
                }
                repo.setSuggestion(card.id, null)
            })
            Pill("Review", { nav.push(Screen.Detail(card.id)) }, filled = false)
            Pill("Dismiss", { repo.setSuggestion(card.id, null) }, filled = false)
        }
    }
}

@Composable
fun AddToCollectionDialog(cardIds: List<Long>, onDone: () -> Unit) {
    val t = LocalTones.current
    val repo = App.instance.repo
    val cols by repoState { it.collections() }
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = t.card,
        title = { Text("Add to collection") },
        text = {
            Column {
                (cols ?: emptyList()).forEach { c ->
                    Text(c.name, color = t.text, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                        cardIds.forEach { repo.addToCollection(it, c.id) }
                        cardIds.forEach { repo.setSuggestion(it, null) }
                        onDone()
                    }.padding(vertical = 12.dp, horizontal = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(newName, { newName = it; error = null }, placeholder = { Text("New collection") }, singleLine = true, isError = error != null)
                error?.let { Text(it, color = Palette.Red, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val id = repo.createCollection(newName)
                if (id < 0) { error = "There is already a collection with that name"; return@TextButton }
                cardIds.forEach { repo.addToCollection(it, id) }
                onDone()
            }, enabled = newName.isNotBlank()) { Text("Create", color = t.text) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel", color = t.text2) } }
    )
}

// ---------- Collections ----------

@Composable
private fun CollectionsTab(nav: Nav) {
    val t = LocalTones.current
    val cols by repoState { it.collections() }
    var creating by remember { mutableStateOf(false) }
    val list = cols ?: return
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(gridColumns()),
        contentPadding = PaddingValues(12.dp),
        verticalItemSpacing = 10.dp,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "new") {
            Column(
                Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp))
                    .border(1.dp, t.divider, RoundedCornerShape(16.dp)).clickable { creating = true }.padding(14.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Ico.Plus, t.text2)
                Spacer(Modifier.height(6.dp))
                Text("Create a collection", color = t.text2, fontSize = 13.sp)
            }
        }
        items(list, key = { it.id }) { c ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.card).clickable { nav.push(Screen.Collection(c.id)) }) {
                if (c.coverFile != null) CaptureImage(c.coverFile, 400, Modifier.fillMaxWidth().height(110.dp))
                else Box(Modifier.fillMaxWidth().height(110.dp).background(t.card2), contentAlignment = Alignment.Center) { Icon(Ico.Folder, t.text3, 30.dp) }
                Column(Modifier.padding(12.dp)) {
                    Text(c.name, color = t.text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${c.count} captures", color = Color(0xFF777777), fontSize = 12.sp)
                }
            }
        }
        if (list.isEmpty()) item(span = StaggeredGridItemSpan.FullLine, key = "empty") {
            EmptyState("NO COLLECTION", "Group related captures. You'll also get suggestions as your space grows.")
        }
    }
    if (creating) CreateCollectionDialog(onDone = { id -> creating = false; if (id != null) nav.push(Screen.Collection(id)) })
}

/** Create a collection; finds related captures by meaning (the original's smart collection). */
@Composable
fun CreateCollectionDialog(onDone: (Long?) -> Unit) {
    val t = LocalTones.current
    val repo = App.instance.repo
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf<List<Card>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<Long>() }
    LaunchedEffect(searching) {
        if (!searching) return@LaunchedEffect
        try {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.craft.capturespace.ai.Pipeline.smartCollection(name) }
            candidates = r; picked.clear(); picked.addAll(r.map { it.id })
            if (r.isEmpty()) error = "No related captures found. You can still create this collection."
        } catch (e: com.craft.capturespace.ai.AiException) {
            error = e.userMessage; candidates = emptyList()
        }
        searching = false
    }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        containerColor = t.card,
        title = { Text("Create a collection") },
        text = {
            Column {
                OutlinedTextField(name, { name = it; error = null; candidates = null }, placeholder = { Text("Collection title") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                if (searching) Row(verticalAlignment = Alignment.CenterVertically) { Pulse(); Spacer(Modifier.width(8.dp)); Text("Finding related captures…", fontSize = 13.sp, color = t.text2) }
                error?.let { Text(it, color = t.text2, fontSize = 13.sp) }
                candidates?.takeIf { it.isNotEmpty() }?.let { list ->
                    SectionLabel("Related captures")
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(list, key = { it.id }) { c ->
                            Row(Modifier.fillMaxWidth().clickable { if (c.id in picked) picked.remove(c.id) else picked.add(c.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(c.id in picked, { if (it) picked.add(c.id) else picked.remove(c.id) })
                                Text(c.displayTitle, color = t.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (candidates == null) TextButton(onClick = { searching = true }, enabled = name.isNotBlank() && !searching) { Text("Find related", color = t.text2) }
                TextButton(onClick = {
                    val id = repo.createCollection(name)
                    if (id < 0) { error = "There is already a collection with that name"; return@TextButton }
                    picked.forEach { repo.addToCollection(it, id) }
                    onDone(id)
                }, enabled = name.isNotBlank()) { Text("Confirm", color = t.text) }
            }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel", color = t.text2) } }
    )
}

// ---------- Tasks and events ----------

@Composable
private fun TasksTab() {
    val t = LocalTones.current
    val ctx = LocalContext.current
    val items by repoState { it.items() }
    var editing by remember { mutableStateOf<PlanItem?>(null) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val all = items ?: return
    val now = System.currentTimeMillis()
    val events = all.filter { it.kind == "event" && !it.done && (it.end ?: it.start ?: now) >= startOfToday() }
    val tasks = all.filter { it.kind == "task" && !it.done }
    val doneOrPast = all.filter { it.done || (it.kind == "event" && (it.end ?: it.start ?: now) < startOfToday()) }
    val todayEnd = startOfToday() + 86_400_000L
    val eToday = events.count { (it.start ?: 0) < todayEnd }
    val tToday = tasks.count { it.start != null && it.start < todayEnd }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            val e = plural(eToday, "event"); val k = plural(tToday, "task")
            Text(
                when {
                    eToday > 0 && tToday > 0 -> "You have $e and $k today."
                    eToday > 0 -> "You have $e today."
                    tToday > 0 -> "You have $k today."
                    else -> "Nothing due today."
                },
                fontSize = 20.sp, color = t.text, fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text("Events and tasks found in your captures appear here.", fontSize = 13.sp, color = t.text3)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Add a task", { editing = PlanItem(0, null, "task", "", null, null, false, "", false, now) }, filled = false)
                Pill("Add an event", { editing = PlanItem(0, null, "event", "", roundUpHour(), null, false, "", false, now) }, filled = false)
            }
            Spacer(Modifier.height(10.dp))
            SectionLabel("Events")
        }
        if (events.isEmpty()) item { Text("No events here", color = t.text3, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) }
        items(events, key = { "e${it.id}" }) { ItemRow(it, onEdit = { editing = it }) }
        item { Spacer(Modifier.height(8.dp)); SectionLabel("Tasks") }
        if (tasks.isEmpty()) item { Text("There are no tasks currently available", color = t.text3, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) }
        items(tasks, key = { "t${it.id}" }) { ItemRow(it, onEdit = { editing = it }) }
        if (doneOrPast.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(if (showDone) "Hide completed and past" else "Completed and past (${doneOrPast.size})", color = t.text2, fontSize = 13.sp,
                    modifier = Modifier.clickable { showDone = !showDone }.padding(vertical = 8.dp))
            }
            if (showDone) items(doneOrPast, key = { "d${it.id}" }) { ItemRow(it, onEdit = { editing = it }) }
        }
    }
    editing?.let { ItemEditor(it, onDone = { editing = null }) }
}

private fun plural(n: Int, w: String) = if (n == 1) "1 $w" else "$n ${w}s"

fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun roundUpHour(): Long = Calendar.getInstance().apply {
    add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** "In 12 min", "Overdue 5 min", "Tomorrow 10:00"… */
fun whenLabel(ctx: android.content.Context, item: PlanItem): String {
    val s = item.start ?: return "Not specified"
    val now = System.currentTimeMillis()
    val diff = s - now
    val time = DateFormat.getTimeFormat(ctx).format(Date(s))
    val day = startOfToday()
    return when {
        !item.allDay && diff in 0 until 3_600_000L -> "In ${diff / 60_000 + 1} min"
        !item.allDay && diff in -3_600_000L until 0 && !item.done -> "Overdue ${-diff / 60_000} min"
        s in day until day + 86_400_000L -> if (item.allDay) "Today · All day" else "Today $time"
        s in day + 86_400_000L until day + 2 * 86_400_000L -> if (item.allDay) "Tomorrow · All day" else "$time Tomorrow"
        s in day - 86_400_000L until day -> if (item.allDay) "Yesterday" else "$time Yesterday"
        else -> DateFormat.getMediumDateFormat(ctx).format(Date(s)) + if (item.allDay) "" else " $time"
    }
}

@Composable
private fun ItemRow(item: PlanItem, onEdit: () -> Unit) {
    val t = LocalTones.current
    val ctx = LocalContext.current
    val repo = App.instance.repo
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.card).clickable(onClick = onEdit).padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.kind == "task") {
            Checkbox(item.done, { repo.updateItem(item.copy(done = it)); if (it) Reminders.cancel(ctx, item.id) else Reminders.schedule(ctx, item.copy(done = false)) })
        } else {
            Box(Modifier.padding(14.dp).size(10.dp).clip(CircleShape).background(if (item.done) t.text3 else Palette.Red))
        }
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(item.title, color = t.text, fontSize = 15.sp, textDecoration = if (item.done) TextDecoration.LineThrough else null, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(whenLabel(ctx, item), item.location).filter { it.isNotBlank() }.joinToString(" · "), color = t.text3, fontSize = 12.sp)
        }
        Box {
            IconButton(Ico.More, { menu = true }, description = "More")
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(if (item.kind == "task") "Convert to Event" else "Convert to Task") }, onClick = {
                    menu = false
                    val conv = item.copy(kind = if (item.kind == "task") "event" else "task", start = item.start ?: if (item.kind == "task") roundUpHour() else null)
                    repo.updateItem(conv); Reminders.schedule(ctx, conv)
                })
                if (item.kind == "task") DropdownMenuItem(text = { Text(if (item.done) "Mark as not done" else "Mark as Completed") }, onClick = {
                    menu = false; repo.updateItem(item.copy(done = !item.done))
                })
                DropdownMenuItem(text = { Text("Add to calendar") }, onClick = { menu = false; addToCalendar(ctx, item) })
                item.cardId?.let { cid ->
                    DropdownMenuItem(text = { Text("Open capture") }, onClick = {
                        menu = false
                        ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("card", cid).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    })
                }
                DropdownMenuItem(text = { Text("Delete", color = Palette.Red) }, onClick = {
                    menu = false; Reminders.cancel(ctx, item.id); repo.deleteItem(item.id)
                })
            }
        }
    }
}

fun addToCalendar(ctx: android.content.Context, item: PlanItem) {
    val i = Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
        .putExtra(android.provider.CalendarContract.Events.TITLE, item.title)
        .putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, item.location)
        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, item.allDay)
    item.start?.let { i.putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
    (item.end ?: item.start?.plus(3_600_000L))?.let { i.putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, it) }
    try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {
        Toast.makeText(ctx, "No calendar app found", Toast.LENGTH_SHORT).show()
    }
}

/** Add or edit a task or an event: title, date and time, all day, location. */
@Composable
fun ItemEditor(item: PlanItem, onDone: () -> Unit) {
    ItemEditorBody(item, onDone)
}

/** "Today" / "Tomorrow" for those days, as the original's Date row shows; null otherwise. */
private fun dayWord(ms: Long): String? {
    val d = (ms - startOfToday()).let { if (it < 0) -1 else it / 86_400_000L }
    return when (d) { 0L -> "Today"; 1L -> "Tomorrow"; else -> null }
}

@Composable
private fun TimeDateRow(label: String, value: String, onClick: () -> Unit) {
    val t = LocalTones.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.chip).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = t.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = t.text2, fontSize = 14.sp)
        Spacer(Modifier.width(6.dp))
        Text("›", color = t.text2, fontSize = 18.sp)
    }
}

@Composable
private fun ItemEditorBody(item: PlanItem, onDone: () -> Unit) {
    val t = LocalTones.current
    val ctx = LocalContext.current
    val repo = App.instance.repo
    var title by remember { mutableStateOf(item.title) }
    var start by remember { mutableStateOf(item.start) }
    var end by remember { mutableStateOf(item.end) }
    var allDay by remember { mutableStateOf(item.allDay) }
    var location by remember { mutableStateOf(item.location) }
    var error by remember { mutableStateOf<String?>(null) }
    fun pick(initial: Long?, set: (Long?) -> Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = initial ?: roundUpHour() }
        android.app.DatePickerDialog(ctx, { _, y, m, d ->
            c.set(y, m, d)
            if (allDay) { c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); set(c.timeInMillis) }
            else android.app.TimePickerDialog(ctx, { _, h, min ->
                c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, min); c.set(Calendar.SECOND, 0); set(c.timeInMillis)
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), DateFormat.is24HourFormat(ctx)).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }
    fun fmt(ms: Long?) = ms?.let {
        DateFormat.getMediumDateFormat(ctx).format(Date(it)) + if (allDay) "" else " " + DateFormat.getTimeFormat(ctx).format(Date(it))
    } ?: "Assign a time"
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = t.card,
        title = { Text(if (item.id == 0L) (if (item.kind == "task") "Add a task" else "Add an event") else "Edit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, placeholder = { Text("Name") }, singleLine = true)
                // As in the original: "Event time" with Clear, then a Time row and a Date row.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (item.kind == "task") "Due time" else "Event time", color = t.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (start != null) Text("Clear", color = t.text, fontSize = 14.sp, modifier = Modifier.clickable { start = null; end = null }.padding(6.dp))
                }
                val base = Calendar.getInstance().apply { timeInMillis = start ?: roundUpHour() }
                if (!allDay) TimeDateRow("Time", start?.let { DateFormat.getTimeFormat(ctx).format(Date(it)) } ?: "Not set") {
                    android.app.TimePickerDialog(ctx, { _, h, min ->
                        base.set(Calendar.HOUR_OF_DAY, h); base.set(Calendar.MINUTE, min); base.set(Calendar.SECOND, 0); start = base.timeInMillis
                    }, base.get(Calendar.HOUR_OF_DAY), base.get(Calendar.MINUTE), DateFormat.is24HourFormat(ctx)).show()
                }
                TimeDateRow("Date", start?.let { dayWord(it) ?: DateFormat.getMediumDateFormat(ctx).format(Date(it)) } ?: "Not set") {
                    android.app.DatePickerDialog(ctx, { _, y, m, d -> base.set(y, m, d); start = base.timeInMillis },
                        base.get(Calendar.YEAR), base.get(Calendar.MONTH), base.get(Calendar.DAY_OF_MONTH)).show()
                }
                if (item.kind == "event") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("To", color = t.text3, modifier = Modifier.width(56.dp))
                        Text(if (end == null) "Not specified" else fmt(end), color = t.text, modifier = Modifier.clickable { pick(end ?: start) { end = it } }.padding(8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("All day", color = t.text, modifier = Modifier.weight(1f))
                        Switch(allDay, { allDay = it })
                    }
                    OutlinedTextField(location, { location = it }, placeholder = { Text("Location") }, singleLine = true)
                }
                error?.let { Text(it, color = Palette.Red, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isBlank()) { error = "Invalid input"; return@TextButton }
                val s = start; val e = end
                if (s != null && e != null && e <= s) { error = "The end time must be after the start time"; return@TextButton }
                val saved = if (item.id == 0L) {
                    val id = repo.addItem(item.kind, title, s, e, allDay, location)
                    repo.item(id)
                } else {
                    item.copy(title = title.trim(), start = s, end = e, allDay = allDay, location = location.trim()).also { repo.updateItem(it) }
                }
                saved?.let { Reminders.schedule(ctx, it) }
                onDone()
            }) { Text("Save", color = t.text) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel", color = t.text2) } }
    )
}
