package com.craft.capturespace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craft.capturespace.App
import com.craft.capturespace.data.PlanItem
import com.craft.capturespace.data.Reminders
import com.craft.capturespace.data.TYPE_IMAGE

/**
 * The "For you" overview of the current Essential Space (store listing, Nov 2026):
 * a "You have N tasks today." headline, then SUGGESTED, EVENTS, TASKS, COLLECTIONS and
 * CAPTURES sections, each with a chevron that opens the full list.
 */
@Composable
fun ForYouTab(nav: Nav) {
    val t = LocalTones.current
    val ctx = LocalContext.current
    val repo = App.instance.repo
    val cards by repoState { it.cards() }
    val items by repoState { it.items() }
    val cols by repoState { it.collections() }
    val all = cards ?: return
    val plan = items ?: return
    val today = startOfToday()
    val todayEnd = today + 86_400_000L
    val events = plan.filter { it.kind == "event" && !it.done && (it.end ?: it.start ?: today) >= today }.sortedBy { it.start ?: Long.MAX_VALUE }
    val tasks = plan.filter { it.kind == "task" && !it.done }.sortedBy { it.start ?: Long.MAX_VALUE }
    val dueToday = tasks.count { it.start != null && it.start < todayEnd }
    val eventsToday = events.count { (it.start ?: Long.MAX_VALUE) < todayEnd }
    val suggested = all.firstOrNull { it.suggestion != null }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Text(
            buildAnnotatedString {
                // "You have 5 events and 7 tasks today." (bold counts), as in the original.
                val bold = SpanStyle(fontWeight = FontWeight.SemiBold)
                val ev = eventsToday
                if (dueToday == 0 && ev == 0) append("Nothing planned today.")
                else {
                    append("You have ")
                    if (ev > 0) withStyle(bold) { append(if (ev == 1) "1 event" else "$ev events") }
                    if (ev > 0 && dueToday > 0) append(" and ")
                    if (dueToday > 0) withStyle(bold) { append(if (dueToday == 1) "1 task" else "$dueToday tasks") }
                    append(" today.")
                }
            },
            fontSize = 27.sp, lineHeight = 33.sp, color = t.text,
            modifier = Modifier.padding(start = 24.dp, end = 60.dp, top = 28.dp, bottom = 8.dp)
        )

        suggested?.let { card ->
            val s = card.suggestion!!
            Header("Suggested") { nav.homeTab = 1 }
            Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(t.card).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildAnnotatedString {
                            if (s.kind == "add") {
                                append("This looks like it belongs in ")
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.name) }
                                append(". Add it?")
                            } else {
                                append("You've been capturing a lot of ")
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.name) }
                                append(" lately. Create a new collection?")
                            }
                        },
                        color = t.text, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.weight(1f).padding(4.dp)
                    )
                    card.file?.takeIf { card.type == TYPE_IMAGE }?.let {
                        Spacer(Modifier.width(8.dp))
                        CaptureImage(it, 300, Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Dismiss", { repo.setSuggestion(card.id, null) }, Modifier.weight(1f), filled = false)
                    Pill("Review", { nav.push(Screen.Detail(card.id)) }, Modifier.weight(1f))
                }
            }
        }

        if (events.isNotEmpty()) {
            Header("Events") { nav.homeTab = 3 }
            val e = events.first()
            val img = e.cardId?.let { id -> all.firstOrNull { it.id == id } }?.takeIf { it.type == TYPE_IMAGE }?.file
            Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(t.card)
                .clickable { e.cardId?.let { nav.push(Screen.Detail(it)) } ?: run { nav.homeTab = 3 } }.padding(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(e.title, color = t.text, fontSize = 20.sp, lineHeight = 25.sp, maxLines = 5, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(4.dp))
                    InfoChip(Ico.Calendar, whenLabel(ctx, e))
                    if (e.location.isNotBlank()) InfoChip(Ico.Flag, e.location)
                }
                img?.let {
                    Spacer(Modifier.width(10.dp))
                    CaptureImage(it, 500, Modifier.width(130.dp).heightIn(min = 160.dp).clip(RoundedCornerShape(12.dp)))
                }
            }
        }

        Header("Tasks") { nav.homeTab = 3 }
        Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.card).padding(vertical = 6.dp)) {
            if (tasks.isEmpty()) Text("There are no tasks currently available", color = t.text3, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
            tasks.take(4).forEach { TaskLine(it) }
        }

        val list = cols.orEmpty()
        if (list.isNotEmpty()) {
            Header("Collections") { nav.homeTab = 2 }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                list.forEach { c ->
                    Row(Modifier.width(250.dp).height(150.dp).clip(RoundedCornerShape(20.dp)).background(t.card)
                        .clickable { nav.push(Screen.Collection(c.id)) }.padding(12.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                            Text("${c.count}", color = t.text3, fontSize = 14.sp)
                            Text(c.name, color = t.text, fontSize = 22.sp, lineHeight = 26.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        c.coverFile?.let { CaptureImage(it, 300, Modifier.width(92.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp))) }
                    }
                }
            }
        }

        if (all.isNotEmpty()) {
            Header("Captures") { nav.homeTab = 1 }
            // A short preview (2 columns on phones, more on tablets); the Library tab has them all.
            val n = gridColumns().coerceAtMost(4)
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val recent = all.take(n * 3)
                List(n) { k -> recent.filterIndexed { i, _ -> i % n == k } }.forEach { col ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        col.forEach { c -> CaptureCard(c, selected = false, onClick = { nav.push(Screen.Detail(c.id)) }, onLongClick = { nav.homeTab = 1 }) }
                    }
                }
            }
        } else {
            EmptyState("EMPTY", "Double-press volume-down to capture your screen, hold it to record a voice note, or use the bar below.", Modifier.padding(top = 40.dp))
        }
    }
}

/** Upper-case section title with a chevron, as in the original's overview. */
@Composable
private fun Header(title: String, onOpen: () -> Unit) {
    val t = LocalTones.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), color = t.text, fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        // A thin drawn chevron, like the original's section arrows.
        val ink = t.text
        androidx.compose.foundation.Canvas(Modifier.size(10.dp)) {
            val w = size.width; val sw = 1.4.dp.toPx()
            drawLine(ink, androidx.compose.ui.geometry.Offset(w * 0.3f, w * 0.1f), androidx.compose.ui.geometry.Offset(w * 0.75f, w * 0.5f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(ink, androidx.compose.ui.geometry.Offset(w * 0.75f, w * 0.5f), androidx.compose.ui.geometry.Offset(w * 0.3f, w * 0.9f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

@Composable
private fun InfoChip(icon: Ico, text: String) {
    val t = LocalTones.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(t.card2).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, t.text, 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, color = t.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TaskLine(item: PlanItem) {
    val t = LocalTones.current
    val ctx = LocalContext.current
    val repo = App.instance.repo
    // Sized like the original's task rows: a small hollow circle, the title, and a small time line.
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 3.dp).size(16.dp).clip(CircleShape).border(1.2.dp, t.text2, CircleShape).clickable {
            repo.updateItem(item.copy(done = true)); Reminders.cancel(ctx, item.id)
        })
        Spacer(Modifier.width(12.dp))
        Column {
            Text(item.title, color = t.text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.start != null) Text(whenLabel(ctx, item), color = t.text3, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
