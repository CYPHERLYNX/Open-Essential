@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.craft.capturespace.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.text.format.DateUtils
import android.util.LruCache
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.craft.capturespace.App
import com.craft.capturespace.data.Card
import com.craft.capturespace.data.Repo
import com.craft.capturespace.data.Status
import com.craft.capturespace.data.TYPE_IMAGE
import com.craft.capturespace.data.TYPE_VOICE
import com.craft.capturespace.shortcuts.Haptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reloads [load] whenever the saved data changes. */
@Composable
fun <T> repoState(vararg keys: Any?, load: (Repo) -> T): State<T?> {
    val repo = App.instance.repo
    val ver by repo.changes.collectAsState()
    return produceState<T?>(initialValue = null, ver, *keys) {
        value = withContext(Dispatchers.IO) { load(repo) }
    }
}

// ---------- icons (drawn, so they match the thin line style) ----------

enum class Ico { Mic, Camera, Image, Note, Screen, Stop, Pause, Play, Flag, Back, Search, Gear, More, Plus, Check, Trash, Share, Close, Calendar, Folder, Sparkle, Restore, Edit }

@Composable
fun Icon(kind: Ico, tint: Color = LocalTones.current.text, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawIco(kind, tint) }
}

private fun DrawScope.drawIco(k: Ico, c: Color) {
    val w = size.width
    val sw = w * 0.085f
    val st = Stroke(width = sw, cap = StrokeCap.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(c, Offset(x1 * w, y1 * w), Offset(x2 * w, y2 * w), sw, StrokeCap.Round)
    when (k) {
        Ico.Mic -> {
            drawRoundRect(c, Offset(0.36f * w, 0.12f * w), Size(0.28f * w, 0.46f * w), CornerRadius(0.14f * w), style = st)
            drawArc(c, 0f, 180f, false, Offset(0.24f * w, 0.30f * w), Size(0.52f * w, 0.42f * w), style = st)
            line(0.5f, 0.72f, 0.5f, 0.88f)
        }
        Ico.Camera -> {
            drawRoundRect(c, Offset(0.12f * w, 0.28f * w), Size(0.76f * w, 0.54f * w), CornerRadius(0.08f * w), style = st)
            drawCircle(c, 0.14f * w, Offset(0.5f * w, 0.55f * w), style = st)
            line(0.36f, 0.28f, 0.42f, 0.18f); line(0.42f, 0.18f, 0.58f, 0.18f); line(0.58f, 0.18f, 0.64f, 0.28f)
        }
        Ico.Image -> {
            drawRoundRect(c, Offset(0.14f * w, 0.18f * w), Size(0.72f * w, 0.64f * w), CornerRadius(0.08f * w), style = st)
            drawCircle(c, 0.06f * w, Offset(0.36f * w, 0.38f * w))
            line(0.2f, 0.74f, 0.42f, 0.52f); line(0.42f, 0.52f, 0.56f, 0.64f); line(0.56f, 0.64f, 0.66f, 0.54f); line(0.66f, 0.54f, 0.8f, 0.68f)
        }
        Ico.Note -> {
            drawRoundRect(c, Offset(0.2f * w, 0.12f * w), Size(0.6f * w, 0.76f * w), CornerRadius(0.08f * w), style = st)
            line(0.33f, 0.36f, 0.67f, 0.36f); line(0.33f, 0.5f, 0.67f, 0.5f); line(0.33f, 0.64f, 0.55f, 0.64f)
        }
        Ico.Screen -> {
            line(0.15f, 0.32f, 0.15f, 0.15f); line(0.15f, 0.15f, 0.32f, 0.15f)
            line(0.68f, 0.15f, 0.85f, 0.15f); line(0.85f, 0.15f, 0.85f, 0.32f)
            line(0.85f, 0.68f, 0.85f, 0.85f); line(0.85f, 0.85f, 0.68f, 0.85f)
            line(0.32f, 0.85f, 0.15f, 0.85f); line(0.15f, 0.85f, 0.15f, 0.68f)
            drawCircle(c, 0.08f * w, Offset(0.5f * w, 0.5f * w))
        }
        Ico.Stop -> drawRoundRect(c, Offset(0.28f * w, 0.28f * w), Size(0.44f * w, 0.44f * w), CornerRadius(0.06f * w))
        Ico.Pause -> {
            drawRoundRect(c, Offset(0.28f * w, 0.22f * w), Size(0.14f * w, 0.56f * w), CornerRadius(0.04f * w))
            drawRoundRect(c, Offset(0.58f * w, 0.22f * w), Size(0.14f * w, 0.56f * w), CornerRadius(0.04f * w))
        }
        Ico.Play -> {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(0.32f * w, 0.2f * w); lineTo(0.8f * w, 0.5f * w); lineTo(0.32f * w, 0.8f * w); close()
            }
            drawPath(p, c)
        }
        Ico.Flag -> {
            line(0.28f, 0.12f, 0.28f, 0.9f)
            line(0.28f, 0.16f, 0.76f, 0.16f); line(0.76f, 0.16f, 0.64f, 0.33f); line(0.64f, 0.33f, 0.76f, 0.5f); line(0.76f, 0.5f, 0.28f, 0.5f)
        }
        Ico.Back -> { line(0.62f, 0.2f, 0.32f, 0.5f); line(0.32f, 0.5f, 0.62f, 0.8f) }
        Ico.Search -> { drawCircle(c, 0.24f * w, Offset(0.44f * w, 0.44f * w), style = st); line(0.62f, 0.62f, 0.84f, 0.84f) }
        Ico.Gear -> {
            drawCircle(c, 0.26f * w, Offset(0.5f * w, 0.5f * w), style = st)
            drawCircle(c, 0.08f * w, Offset(0.5f * w, 0.5f * w), style = st)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val x1 = 0.5f + 0.3f * Math.cos(a).toFloat(); val y1 = 0.5f + 0.3f * Math.sin(a).toFloat()
                val x2 = 0.5f + 0.4f * Math.cos(a).toFloat(); val y2 = 0.5f + 0.4f * Math.sin(a).toFloat()
                line(x1, y1, x2, y2)
            }
        }
        Ico.More -> for (i in 0..2) drawCircle(c, 0.065f * w, Offset(0.5f * w, (0.25f + i * 0.25f) * w))
        Ico.Plus -> { line(0.5f, 0.2f, 0.5f, 0.8f); line(0.2f, 0.5f, 0.8f, 0.5f) }
        Ico.Check -> { line(0.2f, 0.52f, 0.42f, 0.72f); line(0.42f, 0.72f, 0.8f, 0.3f) }
        Ico.Trash -> {
            line(0.18f, 0.26f, 0.82f, 0.26f); line(0.4f, 0.14f, 0.6f, 0.14f)
            drawRoundRect(c, Offset(0.26f * w, 0.26f * w), Size(0.48f * w, 0.6f * w), CornerRadius(0.06f * w), style = st)
            line(0.43f, 0.4f, 0.43f, 0.72f); line(0.57f, 0.4f, 0.57f, 0.72f)
        }
        Ico.Share -> {
            drawCircle(c, 0.09f * w, Offset(0.72f * w, 0.24f * w), style = st)
            drawCircle(c, 0.09f * w, Offset(0.28f * w, 0.5f * w), style = st)
            drawCircle(c, 0.09f * w, Offset(0.72f * w, 0.76f * w), style = st)
            line(0.36f, 0.45f, 0.64f, 0.29f); line(0.36f, 0.55f, 0.64f, 0.71f)
        }
        Ico.Close -> { line(0.25f, 0.25f, 0.75f, 0.75f); line(0.75f, 0.25f, 0.25f, 0.75f) }
        Ico.Calendar -> {
            drawRoundRect(c, Offset(0.14f * w, 0.2f * w), Size(0.72f * w, 0.66f * w), CornerRadius(0.08f * w), style = st)
            line(0.14f, 0.38f, 0.86f, 0.38f); line(0.34f, 0.12f, 0.34f, 0.26f); line(0.66f, 0.12f, 0.66f, 0.26f)
        }
        Ico.Folder -> {
            line(0.12f, 0.24f, 0.4f, 0.24f); line(0.4f, 0.24f, 0.48f, 0.34f)
            drawRoundRect(c, Offset(0.12f * w, 0.34f * w), Size(0.76f * w, 0.5f * w), CornerRadius(0.06f * w), style = st)
            line(0.12f, 0.24f, 0.12f, 0.4f)
        }
        Ico.Sparkle -> {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(0.5f * w, 0.1f * w); quadraticTo(0.55f * w, 0.45f * w, 0.9f * w, 0.5f * w)
                quadraticTo(0.55f * w, 0.55f * w, 0.5f * w, 0.9f * w); quadraticTo(0.45f * w, 0.55f * w, 0.1f * w, 0.5f * w)
                quadraticTo(0.45f * w, 0.45f * w, 0.5f * w, 0.1f * w); close()
            }
            drawPath(p, c)
        }
        Ico.Restore -> {
            drawArc(c, 200f, 290f, false, Offset(0.18f * w, 0.18f * w), Size(0.64f * w, 0.64f * w), style = st)
            line(0.2f, 0.28f, 0.2f, 0.46f); line(0.2f, 0.46f, 0.37f, 0.42f)
        }
        Ico.Edit -> { line(0.22f, 0.78f, 0.7f, 0.3f); line(0.62f, 0.22f, 0.78f, 0.38f); line(0.22f, 0.78f, 0.2f, 0.82f) }
    }
}

@Composable
fun IconButton(kind: Ico, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = LocalTones.current.text, description: String? = null) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(
        modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = description) { Haptics.tap(ctx); onClick() },
        contentAlignment = Alignment.Center
    ) { Icon(kind, tint) }
}

// ---------- images ----------

object Thumbs {
    private val cache = LruCache<String, Bitmap>(40)
    fun cached(path: String, max: Int): Bitmap? = cache.get("$path#$max")

    fun load(path: String, max: Int): Bitmap? {
        cached(path, max)?.let { return it }
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2
            var b = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
            val rot = try {
                when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (_: Exception) { 0f }
            if (rot != 0f) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot) }, true)
            cache.put("$path#$max", b)
            b
        } catch (e: Exception) {
            null
        }
    }
}

@Composable
fun CaptureImage(path: String, max: Int, modifier: Modifier = Modifier, crop: Boolean = true, keepRatio: Boolean = false) {
    val t = LocalTones.current
    val bmp by produceState(Thumbs.cached(path, max), path, max) {
        value = withContext(Dispatchers.IO) { Thumbs.load(path, max) }
    }
    val b = bmp
    if (b == null) {
        Box(modifier.background(t.card2).then(if (keepRatio) Modifier.aspectRatio(0.75f) else Modifier))
    } else {
        Image(
            bitmap = b.asImageBitmap(), contentDescription = null,
            contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
            modifier = modifier.then(if (keepRatio) Modifier.aspectRatio((b.width.toFloat() / b.height).coerceIn(0.45f, 1.8f)) else Modifier)
        )
    }
}

// ---------- adaptive layout ----------

/** Card grids: 2 columns on phones, more on tablets, foldables and in landscape. */
val CARD_MIN_WIDTH = 160.dp

@Composable
fun gridColumns(): Int {
    val w = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    return ((w - 24) / CARD_MIN_WIDTH.value.toInt()).coerceIn(2, 6)
}

/** Pages of text (details, settings, search) stay a readable width on large screens. */
const val READABLE_MAX_DP = 720

@Composable
fun Centered(maxDp: Int = READABLE_MAX_DP, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxDp.dp).fillMaxSize()) { content() }
    }
}

// ---------- small pieces ----------

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), fontSize = 12.sp, letterSpacing = Dimens.LABEL_TRACKING.em, color = LocalTones.current.text3, modifier = modifier)
}

@Composable
fun Chip(text: String, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val t = LocalTones.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) t.text else t.chip)
            .then(if (onClick != null) Modifier.clickable { Haptics.tap(ctx); onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(text, fontSize = 13.sp, color = if (selected) t.bg else t.text)
    }
}

/** Pill button; filled = the main action. */
@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true, danger: Boolean = false, enabled: Boolean = true) {
    val t = LocalTones.current
    val bg = when {
        danger -> Palette.Red
        filled -> t.text
        else -> Color.Transparent
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val fg = when {
        danger -> Color.White
        filled -> t.bg
        else -> t.text
    }
    Box(
        modifier.alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(50)).background(bg)
            .then(if (!filled && !danger) Modifier.border(1.dp, t.divider, RoundedCornerShape(50)) else Modifier)
            .clickable(enabled = enabled) { Haptics.tap(ctx); onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = fg, fontWeight = FontWeight.Medium, fontSize = 14.sp) }
}

@Composable
fun Pulse(color: Color = Palette.Red, size: Dp = 8.dp) {
    val tr = rememberInfiniteTransition(label = "pulse")
    val a by tr.animateFloat(0.25f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(size).alpha(a).clip(CircleShape).background(color))
}

fun timeLabel(ms: Long): String = DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

fun durationLabel(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun StatusLine(card: Card) {
    val t = LocalTones.current
    when (card.status) {
        Status.PENDING, Status.PROCESSING -> Row(verticalAlignment = Alignment.CenterVertically) {
            Pulse(); Spacer(Modifier.width(6.dp))
            Text(if (card.type == TYPE_VOICE) "Summarising..." else "Analysing....", fontSize = 12.sp, color = t.text3)
        }
        Status.UPDATING -> Row(verticalAlignment = Alignment.CenterVertically) {
            Pulse(); Spacer(Modifier.width(6.dp))
            Text("Updating…", fontSize = 12.sp, color = t.text3)
        }
        Status.FAILED -> Text("Unable to analyse · tap to see why", fontSize = 12.sp, color = Palette.Red)
        Status.OFFLINE -> Text("Waiting for connection", fontSize = 12.sp, color = t.text3)
        Status.NO_KEY, Status.OFF -> Text("Not analysed", fontSize = 12.sp, color = t.text3)
        else -> {}
    }
}

/** A capture in the library grid. */
@Composable
fun CaptureCard(card: Card, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val t = LocalTones.current
    val ctxH = androidx.compose.ui.platform.LocalContext.current
    val shape = RoundedCornerShape(Dimens.RadiusM)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(t.card)
            .then(if (selected) Modifier.border(2.dp, Palette.Red, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = { Haptics.shortcut(ctxH, "long-press"); onLongClick() })
    ) {
        when (card.type) {
            TYPE_IMAGE -> card.file?.let {
                // Tall screenshots are cut at the top portion, like a preview, so the text below stays readable.
                CaptureImage(it, 480, Modifier.fillMaxWidth().height(240.dp), crop = true)
            }
            // A shared link with a public preview picture shows it, like a link card in a chat.
            else -> {
                // A link's preview picture, like a link card in a chat.
                com.craft.capturespace.data.Meta.link(card.meta)?.takeIf { it.ok }?.image?.takeIf { java.io.File(it).exists() }?.let {
                    CaptureImage(it, 480, Modifier.fillMaxWidth().height(150.dp), crop = true)
                }
                // A document's first page (or its own thumbnail), shown whole like a sheet of paper.
                com.craft.capturespace.data.Meta.doc(card.meta)?.cover?.takeIf { java.io.File(it).exists() }?.let {
                    Box(Modifier.fillMaxWidth().height(170.dp).background(t.card2).padding(top = 14.dp, start = 14.dp, end = 14.dp), contentAlignment = Alignment.TopCenter) {
                        CaptureImage(it, 480, Modifier.fillMaxHeight().clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)), crop = false)
                    }
                }
            }
        }
        // As in the original's grid: a picture alone, or its title and text below it; voice and
        // notes as text, voice with a small waveform and its length at the bottom. Only states
        // that need attention (analysing, failed, offline) are shown; no date or "not analysed".
        val body = when {
            // Documents and links show their summary (or nothing while it is made), not the raw text.
            card.meta != null && (com.craft.capturespace.data.Meta.doc(card.meta) != null || com.craft.capturespace.data.Meta.link(card.meta) != null) ->
                card.summary.ifBlank {
                    // The shared text, unless it is only the link itself (the source line names the site).
                    com.craft.capturespace.data.Meta.link(card.meta)?.let { l -> card.note.trim().takeIf { it != l.url && it != l.finalUrl }?.take(300) } ?: ""
                }
            card.type == "note" -> card.note
            card.summary.isNotBlank() -> card.summary
            else -> card.note
        }
        val showTitle = card.type == TYPE_VOICE || card.title.isNotBlank()
        val showStatus = card.status in setOf(Status.PENDING, Status.PROCESSING, Status.UPDATING, Status.FAILED, Status.OFFLINE)
        if (showTitle || body.isNotBlank() || showStatus || card.type != TYPE_IMAGE) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (showTitle) {
                    Text(card.displayTitle, fontSize = 15.sp, color = t.text, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                }
                if (body.isNotBlank()) {
                    if (showTitle) Spacer(Modifier.height(6.dp))
                    Text(body, fontSize = 14.sp, color = if (showTitle) t.text2 else t.text, maxLines = if (card.type == "note") 8 else 3, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                }
                val link = com.craft.capturespace.data.Meta.link(card.meta)
                val doc = com.craft.capturespace.data.Meta.doc(card.meta)
                val source = doc?.let { d -> listOfNotNull(d.label, d.pages?.let { com.craft.capturespace.data.Meta.count(it, d.unit) }).joinToString(" · ") }
                    ?: link?.let { l -> if (l.failed) "${l.site ?: l.host} · Preview not available" else l.site ?: l.host }
                if (source != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(source, fontSize = 12.sp, color = t.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (card.type == TYPE_VOICE) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Waveform(seed = card.id, modifier = Modifier.width(22.dp).height(14.dp), color = t.text)
                        Spacer(Modifier.width(8.dp))
                        Text(durationLabel(card.durationMs), fontSize = 13.sp, color = t.text, fontWeight = FontWeight.Medium)
                    }
                }
                if (showStatus) { Spacer(Modifier.height(6.dp)); StatusLine(card) }
            }
        }
    }
}

/**
 * Decorative bars for a voice card (the recording's own levels aren't stored). Bars follow the
 * original's spectrum resources: 2 dp wide, rounded, half-height between 1 dp and 16 dp.
 */
@Composable
fun Waveform(seed: Long, modifier: Modifier = Modifier, color: Color = LocalTones.current.text2) {
    Canvas(modifier) {
        val bar = Dimens.SpectrumBar.toPx()
        val maxHalf = minOf(Dimens.SpectrumMaxHalf.toPx(), size.height / 2)
        val minHalf = 1.dp.toPx()
        val n = maxOf(1, (size.width / (bar * 2)).toInt())
        val gap = size.width / n
        var x = seed * 2654435761L
        for (i in 0 until n) {
            x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17)
            val half = minHalf + (((x ushr 33) and 0xFF) / 255f) * (maxHalf - minHalf)
            val cx = i * gap + gap / 2
            drawLine(color, Offset(cx, size.height / 2 - half), Offset(cx, size.height / 2 + half), bar, StrokeCap.Round)
        }
    }
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    val t = LocalTones.current
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        DotText(title, dot = 4.dp, color = t.text3)
        Spacer(Modifier.height(16.dp))
        Text(message, color = t.text3, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/**
 * Prominent disclosure shown before the person is sent to turn on the accessibility service
 * (Google Play's accessibility API policy).
 */
const val A11Y_DISCLOSURE =
    "Open Essential uses Android's Accessibility service to stand in for a capture key. With it on, the app:\n" +
        "• watches only the volume-down key, to detect a double-press or a hold (other keys are ignored);\n" +
        "• notes the name of the app on screen, so a capture can say where it came from;\n" +
        "• takes a picture of the screen only when you trigger a capture;\n" +
        "• shows the optional floating capture button.\n" +
        "It doesn't read what you type, doesn't act on other apps, and keeps everything on this phone. " +
        "A capture is sent to the AI service only when it's analysed, using your own AI key."

@Composable
fun A11yDisclosureDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    val t = LocalTones.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = t.card,
        title = { Text("Use of the Accessibility service", color = t.text) },
        text = { Text(A11Y_DISCLOSURE, color = t.text2, fontSize = 14.sp) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onAccept) { Text("Agree and open settings", color = t.text) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Not now", color = t.text2) } },
    )
}

@Composable
fun AiHint(text: String = "The information may contain some inaccuracies. Please double check details.") {
    val t = LocalTones.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.YellowTint).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Ico.Sparkle, Palette.Yellow, 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, color = t.text2)
    }
}
