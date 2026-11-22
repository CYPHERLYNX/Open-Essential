package com.craft.capturespace.ui

import androidx.compose.foundation.background
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craft.capturespace.App
import com.craft.capturespace.ai.Pipeline
import com.craft.capturespace.data.TRASH_DAYS
import com.craft.capturespace.data.TYPE_IMAGE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun TopBar(title: String, nav: Nav, actions: @Composable RowScope.() -> Unit = {}) {
    val t = LocalTones.current
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(Ico.Back, { nav.pop() }, description = "Back")
        Text(title, color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

// ---------- Search: "Ask or search space..." ----------

@Composable
fun SearchScreen(nav: Nav) {
    val t = LocalTones.current
    var query by rememberSaveable_ { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Pipeline.SearchResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tasks by remember { mutableStateOf<List<com.craft.capturespace.data.PlanItem>>(emptyList()) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val fr = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val cols = gridColumns().coerceAtMost(5) // the page is at most 960 dp wide
    LaunchedEffect(Unit) { delay(150); fr.requestFocus() }
    LaunchedEffect(submitted) {
        if (submitted.isBlank()) return@LaunchedEffect
        busy = true
        result = withContext(Dispatchers.IO) { Pipeline.search(submitted) }
        // Tasks and events whose title has the words searched for, as in the original's search.
        tasks = withContext(Dispatchers.IO) {
            com.craft.capturespace.App.instance.repo.items().filter {
                !it.done && com.craft.capturespace.ai.TextSearch.match(submitted, listOf(com.craft.capturespace.ai.TextSearch.Field("Task", it.title, 1))) != null
            }.take(6)
        }
        busy = false
    }
    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
        // As in the original: "← Search" at the top, results in the middle, the field at the bottom.
        Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Ico.Back, { nav.pop() }, description = "Back")
            Text("Search", color = t.text, fontSize = 20.sp)
        }
        val r = result
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) { Pulse(); Spacer(Modifier.width(8.dp)); Text("Searching your space…", color = t.text2) } }
            else if (r == null) item { Text("Enter keyword or description of what you're trying to find. Ask a question to get an answer from your captures.", color = t.text3, fontSize = 14.sp) }
            else {
                r.answer?.let { a ->
                    item {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.card).padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Ico.Sparkle, Palette.Yellow, 16.dp); Spacer(Modifier.width(6.dp)); SectionLabel("Answer") }
                            Spacer(Modifier.height(8.dp))
                            Text(a, color = t.text, fontSize = 15.sp, lineHeight = 22.sp)
                            Spacer(Modifier.height(10.dp))
                            AiHint()
                        }
                    }
                }
                r.note?.let { item { Text(it, color = t.text3, fontSize = 12.sp) } }
                if (tasks.isNotEmpty()) item {
                    SectionLabel("Tasks")
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.card).padding(vertical = 4.dp)) {
                        tasks.forEach { it ->
                            Row(Modifier.fillMaxWidth().clickable { it.cardId?.let { id -> nav.push(Screen.Detail(id)) } }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Box(Modifier.padding(top = 3.dp).size(16.dp).border(1.2.dp, t.text2, androidx.compose.foundation.shape.CircleShape))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(it.title, color = t.text, fontSize = 15.sp)
                                    if (it.start != null) Text(whenLabel(ctx, it), color = t.text3, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                if (r.cards.isEmpty() && tasks.isEmpty()) item { EmptyState("NO RESULTS", "No results match your search query", Modifier.padding(top = 30.dp)) }
                if (r.cards.isNotEmpty()) item { SectionLabel(if (r.answer != null) "Sources" else "Captures") }
                // Two columns of capture cards, as in the original's search results.
                val pairs = r.cards.withIndex().chunked(cols)
                itemsIndexed_(pairs) { _, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { (i, c) ->
                            Column(Modifier.weight(1f)) {
                                if (r.answer != null) Text("[${i + 1}]", color = t.text3, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                                CaptureCard(c, selected = false, onClick = { nav.push(Screen.Detail(c.id)) }, onLongClick = {})
                                // Why it matched: the field and the words around the match.
                                r.hits[c.id]?.let { h ->
                                    val s = h.start.coerceIn(0, h.snippet.length); val e = h.end.coerceIn(s, h.snippet.length)
                                    Text(
                                        androidx.compose.ui.text.buildAnnotatedString {
                                            withStyle(androidx.compose.ui.text.SpanStyle(color = t.text3)) { append(h.field + " · ") }
                                            append(h.snippet.substring(0, s))
                                            withStyle(androidx.compose.ui.text.SpanStyle(color = t.text, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) { append(h.snippet.substring(s, e)) }
                                            append(h.snippet.substring(e))
                                        },
                                        color = t.text2, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp)
                                    )
                                }
                            }
                        }
                        repeat(cols - pair.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(50)).background(t.chip).padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Ico.Search, t.text, 20.dp)
            TextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.weight(1f).focusRequester(fr),
                placeholder = { Text("Ask or search space...") },
                singleLine = true,
                colors = TextFieldDefaults.colors(focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitted = query.trim(); kb?.hide() }),
            )
            if (query.isNotEmpty()) IconButton(Ico.Close, { query = "" }, description = "Clear")
        }
    }
}

// small helpers kept local to avoid extra imports in this file
@Composable
private fun <T> rememberSaveable_(init: () -> MutableState<T>): MutableState<T> =
    androidx.compose.runtime.saveable.rememberSaveable { init() }

private fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexed_(list: List<T>, content: @Composable (Int, T) -> Unit) {
    items(list.size) { i -> content(i, list[i]) }
}

// ---------- One collection ----------

@Composable
fun CollectionScreen(nav: Nav, id: Long) {
    val t = LocalTones.current
    val repo = App.instance.repo
    val col by repoState(id) { it.collection(id) }
    val cards by repoState(id) { it.collectionCards(id) }
    var rename by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var removeCard by remember { mutableStateOf<Long?>(null) }
    val c = col
    Column(Modifier.fillMaxSize().background(t.bg)) {
        TopBar(c?.name ?: "", nav) {
            IconButton(Ico.Edit, { rename = true }, description = "Edit")
            IconButton(Ico.Trash, { remove = true }, description = "Remove collection")
        }
        val list = cards ?: return@Column
        if (list.isEmpty()) EmptyState("EMPTY", "No captures in this collection yet. Open a capture and choose Add to collection.", Modifier.padding(top = 60.dp))
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(gridColumns()), contentPadding = PaddingValues(12.dp),
            verticalItemSpacing = 10.dp, horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(list, key = { it.id }) { card ->
                CaptureCard(card, false, onClick = { nav.push(Screen.Detail(card.id)) }, onLongClick = { removeCard = card.id })
            }
        }
    }
    if (rename && c != null) {
        var v by remember { mutableStateOf(c.name) }
        var err by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { rename = false }, containerColor = t.card,
            title = { Text("Collection title") },
            text = { Column { OutlinedTextField(v, { v = it; err = false }, singleLine = true); if (err) Text("There is already a collection with that name", color = Palette.Red, fontSize = 12.sp) } },
            confirmButton = { TextButton(onClick = { if (repo.renameCollection(id, v)) rename = false else err = true }) { Text("Save", color = t.text) } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel", color = t.text2) } }
        )
    }
    if (remove) AlertDialog(
        onDismissRequest = { remove = false }, containerColor = t.card,
        title = { Text("Are you sure to remove?") },
        text = { Text("Captures inside this collection will not be deleted.") },
        confirmButton = { TextButton(onClick = { remove = false; repo.deleteCollection(id); nav.pop() }) { Text("Remove", color = Palette.Red) } },
        dismissButton = { TextButton(onClick = { remove = false }) { Text("Cancel", color = t.text2) } }
    )
    removeCard?.let { cid ->
        AlertDialog(
            onDismissRequest = { removeCard = null }, containerColor = t.card,
            title = { Text("Remove from collection?") },
            text = { Text("Removing this capture from the collection will not delete the capture itself.") },
            confirmButton = { TextButton(onClick = { repo.removeFromCollection(cid, id); removeCard = null }) { Text("Remove", color = Palette.Red) } },
            dismissButton = { TextButton(onClick = { removeCard = null }) { Text("Cancel", color = t.text2) } }
        )
    }
}

// ---------- Trash ----------

@Composable
fun TrashScreen(nav: Nav) {
    val t = LocalTones.current
    val repo = App.instance.repo
    val cards by repoState { it.trash() }
    var emptyAll by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(t.bg)) {
        TopBar("Trash", nav) { if (!cards.isNullOrEmpty()) IconButton(Ico.Trash, { emptyAll = true }, description = "Empty trash") }
        Text("Captures will be permanently deleted after $TRASH_DAYS days", color = t.text3, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
        val list = cards ?: return@Column
        if (list.isEmpty()) EmptyState("EMPTY", "Nothing to see here", Modifier.padding(top = 60.dp))
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { c ->
                val left = TRASH_DAYS - ((System.currentTimeMillis() - (c.deletedAt ?: 0)) / 86_400_000L).toInt()
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.displayTitle, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${left.coerceAtLeast(0)} DAYS", color = t.text3, fontSize = 11.sp)
                    }
                    IconButton(Ico.Restore, { repo.restore(c.id) }, description = "Restore")
                    IconButton(Ico.Trash, { repo.deleteForever(c.id) }, tint = Palette.Red, description = "Delete forever")
                }
            }
        }
    }
    if (emptyAll) AlertDialog(
        onDismissRequest = { emptyAll = false }, containerColor = t.card,
        title = { Text("Are you sure you want to delete?") },
        text = { Text("These captures will be deleted and cannot be restored.") },
        confirmButton = { TextButton(onClick = { emptyAll = false; cards?.forEach { repo.deleteForever(it.id) } }) { Text("Delete", color = Palette.Red) } },
        dismissButton = { TextButton(onClick = { emptyAll = false }) { Text("Cancel", color = t.text2) } }
    )
}

// ---------- Welcome ----------

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val t = LocalTones.current
    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Spacer(Modifier.height(40.dp))
        // The dot heading is sized to the screen so no line is cut off at the right edge.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val lines = listOf("WELCOME TO" to t.text, "OPEN" to Palette.Red, "ESSENTIAL" to Palette.Red)
            val cols = lines.maxOf { it.first.length * 6 - 1 }
            val step = minOf(maxWidth / cols, 5.3.dp)
            Column(verticalArrangement = Arrangement.spacedBy(step * 1.6f)) {
                lines.forEach { (s, c) -> DotText(s, dot = step * 0.75f, gap = step * 0.25f, color = c) }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("A universal space and smart organiser for your thoughts, ideas and memories.", color = t.text2, fontSize = 16.sp)
        Spacer(Modifier.height(30.dp))
        Tip("Get insights from your captures", "Save any screen or picture you find interesting and pull out the key information.")
        Tip("Organise your thoughts", "Transcribe and summarise your recordings and key conversations.")
        Tip("Remember more, effortlessly", "Find anything again by asking for it in your own words.")
        Tip("No special key needed", "Double-press volume-down to capture your screen, hold it to record. Or use the tile, the notification, the floating button or the power button.")
        Spacer(Modifier.height(24.dp))
        Text("AI runs only on this phone. No account or AI key is needed, and your captures never leave the device.", color = t.text3, fontSize = 12.sp)
        Spacer(Modifier.height(24.dp))
        Pill("Get started", onDone, Modifier.fillMaxWidth())
    }
}

@Composable
private fun Tip(title: String, body: String) {
    val t = LocalTones.current
    Row(Modifier.padding(vertical = 10.dp)) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(RoundedCornerShape(50)).background(t.text))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = t.text, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Text(body, color = t.text2, fontSize = 14.sp)
        }
    }
}
