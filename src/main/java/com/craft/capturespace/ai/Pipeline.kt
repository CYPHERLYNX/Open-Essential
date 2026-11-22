package com.craft.capturespace.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import com.craft.capturespace.App
import com.craft.capturespace.data.Card
import com.craft.capturespace.data.Detail
import com.craft.capturespace.data.Json
import com.craft.capturespace.data.Meta
import com.craft.capturespace.data.Notifications
import com.craft.capturespace.data.Reminders
import com.craft.capturespace.data.Status
import com.craft.capturespace.data.Suggestion
import com.craft.capturespace.data.TYPE_IMAGE
import com.craft.capturespace.data.TYPE_NOTE
import com.craft.capturespace.data.TYPE_VOICE
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Values the pipeline works with; see MECHANICS.md for where each comes from. */
object Limits {
    const val MIN_AUDIO_MS = 2_000L          // "Audio is too short to generate summary"
    const val MAX_AUDIO_BYTES = 18L * 1024 * 1024 // inline request limit of the AI service
    const val IMAGE_MAX_PX = 1600
    const val RELATED_MIN = 0.62f
    const val SUGGEST_ADD_MIN = 0.72f
    const val SUGGEST_CREATE_MIN = 0.72f
    const val SUGGEST_CREATE_COUNT = 2
    const val SEARCH_MIN = 0.50f
    const val SMART_COLLECTION_MIN = 0.55f
    /** Length of a key moment after its mark (original: Flip2RecordEngine.getTranscriptsAtKeyTimestamps). */
    const val KEY_MOMENT_WINDOW_MS = 30_000L
}

/**
 * Key-moment ranges from the marks, by the original's rule: a range runs from a mark to
 * min(mark + 30 s, duration); a later mark at or before the current end extends it, otherwise
 * a new range starts.
 */
fun keyMomentWindows(marks: List<Long>, durationMs: Long): List<Pair<Long, Long>> {
    val end = { k: Long -> if (durationMs > 0) minOf(k + Limits.KEY_MOMENT_WINDOW_MS, durationMs) else k + Limits.KEY_MOMENT_WINDOW_MS }
    val out = ArrayList<Pair<Long, Long>>()
    var start = -1L; var stop = -1L
    for (k in marks.sorted()) {
        if (start < 0) { start = k; stop = end(k) }
        else if (k <= stop) stop = end(k)
        else { out.add(start to stop); start = k; stop = end(k) }
    }
    if (start >= 0) out.add(start to stop)
    return out
}

/**
 * Capture → understand → organise, the same flow the original runs on its server:
 * a capture is saved at once, analysed in the background into a title, summary, tags,
 * key details, tasks and events (voice: transcript, main topics, action items and key
 * moments), indexed for meaning-based search, and offered to a matching collection.
 */
object Pipeline {
    private val running = mutableSetOf<Long>()
    /** Cards asked to be analysed again while an analysis of them was running: run once more after it. */
    private val again = mutableSetOf<Long>()
    /** Bumped on every request to analyse a card again; a result from an older request is not saved. */
    private val revision = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    fun enqueue(ctx: Context, id: Long) {
        App.instance.scope.launch { process(ctx.applicationContext, id) }
    }

    /**
     * Analyse a card again (after a rename, a note edit or "Analyse again"). A card that already has
     * notes keeps showing them as "Updating…"; several requests within [delayMs] become one run, and
     * a request made while a run is going starts one more run after it (that run's result is dropped).
     */
    fun reanalyse(ctx: Context, id: Long, delayMs: Long = 0) {
        val app = App.instance
        val rev = revision.merge(id, 1, Int::plus)!!
        app.scope.launch {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            if (revision[id] != rev) return@launch
            val c = app.repo.card(id) ?: return@launch
            val hasNotes = c.status in setOf(Status.DONE, Status.UPDATING) && (c.summary.isNotBlank() || c.details.isNotEmpty() || c.title.isNotBlank())
            app.repo.setStatus(id, if (hasNotes) Status.UPDATING else Status.PENDING)
            val busy = synchronized(running) { (id in running).also { if (it) again.add(id) } }
            if (!busy) process(ctx.applicationContext, id)
        }
    }

    suspend fun resumePending(ctx: Context) {
        val repo = App.instance.repo
        repo.cardsWithStatus(Status.PENDING, Status.PROCESSING, Status.UPDATING, Status.OFFLINE).forEach { process(ctx, it.id) }
        if (LocalAi.ready) repo.cardsWithStatus(Status.NO_KEY).forEach { process(ctx, it.id) }
    }

    fun process(ctx: Context, id: Long) {
        synchronized(running) { if (!running.add(id)) return }
        try {
            do {
                synchronized(running) { again.remove(id) }
                run(ctx, id)
            } while (synchronized(running) { again.remove(id) })
        } finally {
            synchronized(running) { running.remove(id); again.remove(id) }
        }
    }

    private fun run(ctx: Context, id: Long) {
        val app = App.instance
        val repo = app.repo
        val card = repo.card(id) ?: return
        if (card.deletedAt != null) return
        val rev = revision[id]
        // A card with notes is updated in place: its old notes stay until the new ones are saved.
        val updating = card.status == Status.UPDATING
        // Waits as "Not analysed" (with the reason) until the engine and its models are on the phone.
        if (!LocalAi.ready) {
            if (updating) repo.setStatus(id, Status.DONE, "Couldn't update: " + LocalAi.statusLine)
            else repo.setStatus(id, Status.NO_KEY, LocalAi.statusLine)
            return
        }
        if (!updating) repo.setStatus(id, Status.PROCESSING)
        // A time limit for one analysis, so a capture never stays "Analysing" for ever.
        val limitMs = when (card.type) { TYPE_VOICE -> 25 * 60_000L; TYPE_IMAGE -> 6 * 60_000L; else -> 12 * 60_000L }
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        current = id
        val dog = app.scope.launch { kotlinx.coroutines.delay(limitMs); timedOut.set(true); LocalAi.cancel() }
        val fail: (String, Boolean) -> Unit = { raw, offline ->
            val msg = when {
                synchronized(stopped) { stopped.remove(id) } -> "Stopped. Tap Retry to analyse it."
                timedOut.get() -> "Stopped: the analysis took longer than ${limitMs / 60_000} minutes. Tap Retry to try again, or close other apps first."
                else -> raw
            }
            when {
                revision[id] != rev -> {} // a newer request will run next
                updating -> repo.setStatus(id, Status.DONE, "Couldn't update: $msg")
                else -> repo.setStatus(id, if (offline) Status.OFFLINE else Status.FAILED, msg)
            }
        }
        try {
            var transcript: LocalAi.Transcript? = null
            val ask: (String) -> String = when (card.type) {
                TYPE_IMAGE -> {
                    val path = card.file ?: throw AiException("The image file is missing.")
                    val detail = (card.mime ?: "").contains("png") || card.sourceApp != null
                    // A voice note held down on the capture screen: transcribed first (Whisper frees
                    // its memory afterwards), then given to the picture model as the person's words.
                    val spoken = card.audio?.let { File(it) }?.takeIf { it.exists() && card.audioMs >= Limits.MIN_AUDIO_MS }?.let { a ->
                        LocalAi.transcribeAudio(ctx, a.path, whisperLanguage()).takeIf { it.text.isNotBlank() }
                    }
                    transcript = spoken
                    val (rgb, w, h) = loadRgb(path, if (detail) ImagePolicy.DETAIL_SIDE else ImagePolicy.DEFAULT_SIDE)
                    val p = imagePrompt(card) + (spoken?.let {
                        "\nWith the picture the person recorded this voice note (machine transcript, may contain mistakes): \"${it.text.take(3000)}\"\n" +
                            "Use what they said to decide the title, the tasks and the reminders."
                    } ?: "");
                    { extra -> LocalAi.analyseImage(ctx, systemPrompt(), p + extra, rgb, w, h, NotesGrammar.MAX_TOKENS, grammar = NotesGrammar.build(false)) }
                }
                TYPE_VOICE -> {
                    val f = File(card.file ?: throw AiException("The audio file is missing."))
                    if (!f.exists()) throw AiException("The audio file is missing.")
                    if (card.durationMs in 1 until Limits.MIN_AUDIO_MS) throw AiException("Audio is too short to generate summary")
                    // On-device speech check (original's rule); a file that can't be checked goes on.
                    val speech = SpeechCheck.check(f.path)
                    if (speech != null && !speech.speech) throw AiException("No voice detected")
                    val t = LocalAi.transcribeAudio(ctx, f.path, whisperLanguage())
                    if (t.text.isBlank()) throw AiException("No speech was recognised in this recording.")
                    transcript = t
                    val p = voicePrompt(card, t.text);
                    { extra -> LocalAi.summariseText(ctx, systemPrompt(), p + extra, NotesGrammar.MAX_TOKENS, grammar = NotesGrammar.build(true)) }
                }
                else -> {
                    if (card.note.isBlank()) throw AiException("Nothing to analyse yet.")
                    val link = Meta.link(card.meta)
                    val picture = link?.takeIf { it.ok }?.image?.let { File(it) }?.takeIf { it.exists() }
                    when {
                        // A shared AI chat whose page carries no text: the small model can't read the fine
                        // print in its preview picture (tested on the phone with a ChatGPT share: it wrote
                        // about the screen and the prompt instead). The card shows that picture, which
                        // already displays the chat's first lines; nothing is made up.
                        link != null && link.kind == "AI chat" && link.description == null &&
                            card.note.trim().let { it == link.url || it == link.finalUrl } -> {
                            { _ -> JSONObject().put("title", linkLabel(link)).put("summary", "").toString() }
                        }
                        // A shared link with a public preview picture: the picture model reads it with the page's tags.
                        link != null && picture != null -> {
                            // A shared chat's picture is mostly text: read at the higher detail.
                            val (rgb, w, h) = loadRgb(picture.path, if (link.kind == "AI chat") ImagePolicy.DETAIL_SIDE else ImagePolicy.DEFAULT_SIDE)
                            val p = linkPrompt(card, link, true);
                            { extra -> LocalAi.analyseImage(ctx, systemPrompt(), p + extra, rgb, w, h, NotesGrammar.MAX_TOKENS, grammar = NotesGrammar.build(false)) }
                        }
                        // A link that couldn't be read and nothing but its address was shared: there is
                        // nothing for the model to describe (seen on the phone: "User requested login").
                        // The card shows the site and "Preview not available" instead.
                        // Same for a shared AI chat whose page gives no text: a title alone would be guessed from.
                        link != null && card.note.trim().let { it == link.url || it == link.finalUrl } &&
                            (!link.ok || (link.kind == "AI chat" && link.description == null)) -> {
                            { _ -> JSONObject().put("title", linkLabel(link)).put("summary", "").toString() }
                        }
                        link != null -> {
                            val p = linkPrompt(card, link, false);
                            { extra -> LocalAi.summariseText(ctx, systemPrompt(), p + extra, NotesGrammar.MAX_TOKENS, grammar = NotesGrammar.build(false)) }
                        }
                        else -> {
                            val digest = digest(ctx, card.note) { stopCheck(id, timedOut) }
                            // If the final prompt still doesn't fit the model's window, try once with a shorter digest.
                            val notes = { body: String, extra: String -> LocalAi.summariseText(ctx, systemPrompt(), notePrompt(card, body, digest.second) + extra, NotesGrammar.MAX_TOKENS, grammar = NotesGrammar.build(false)) };
                            { extra ->
                                try { notes(digest.first, extra) } catch (e: AiException) {
                                    if (!e.userMessage.contains("too long", true)) throw e
                                    notes(digest.first.take(digest.first.length / 2) + "\n[Shortened to fit the model.]", extra)
                                }
                            }
                        }
                    }
                }
            }
            // Ask for the notes; if the reply isn't valid, ask once more, then keep it as plain text.
            var raw = ask("")
            var o = parseNotes(raw)
            if (o == null) {
                raw = ask("\nYour previous reply was not valid JSON. Reply with only the JSON object, nothing else.")
                o = parseNotes(raw)
            }
            if (o == null) {
                o = JSONObject().put("title", "").put("summary", raw.take(4000)).put("format_note", "format not recognised")
            }
            transcript?.let { o.put("transcript", it.text); if (o.optString("language").isBlank()) o.put("language", it.language) }
            // A newer rename or edit came in while this ran: its run replaces this result.
            if (revision[id] != rev) return
            // Re-read: the person may have renamed it or deleted it while the model was working.
            val now = repo.card(id) ?: return
            if (now.deletedAt != null) return
            save(ctx, card.copy(userTitle = now.userTitle, title = now.title), o)
        } catch (e: AiException) {
            fail(e.userMessage, e.offline)
        } catch (e: OutOfMemoryError) {
            fail("Not enough free memory on the phone to analyse this. Close other apps and tap Retry.", false)
        } catch (e: Exception) {
            fail("Analysis failed (${e.javaClass.simpleName}${e.message?.takeIf { it.isNotBlank() }?.let { ": " + it.take(120) } ?: ""}).", false)
        } finally {
            dog.cancel(); current = 0L
            synchronized(stopped) { stopped.remove(id) }
        }
    }

    @Volatile private var current = 0L
    private val stopped = mutableSetOf<Long>()

    /** The capture being analysed right now (0 when none). */
    fun analysing(): Long = current

    /** Stops the analysis of [id] if it is running; the card shows "Stopped" with Retry. */
    fun stop(id: Long) {
        if (current != id) return
        synchronized(stopped) { stopped.add(id) }
        LocalAi.cancel()
    }

    private fun stopCheck(id: Long, timedOut: java.util.concurrent.atomic.AtomicBoolean) {
        if (timedOut.get() || synchronized(stopped) { id in stopped }) throw AiException("Cancelled.")
    }

    /**
     * The text the notes are made from. A short text is used as it is. A long document is read the
     * way a person skims one: its beginning as written (title, authors, abstract or introduction),
     * then short summaries of up to [DIGEST_PARTS] parts spread over the rest. Returns the text and
     * whether it is a digest. Sized to fit the model's 4096-token window with the notes it writes.
     */
    private fun digest(ctx: Context, text: String, check: () -> Unit): Pair<String, Boolean> {
        val t = text.trim()
        if (t.length <= DIGEST_SINGLE) return t to false
        val headEnd = t.lastIndexOf("\n\n", DIGEST_HEAD).takeIf { it > DIGEST_HEAD / 2 } ?: DIGEST_HEAD
        val head = t.substring(0, headEnd).trim()
        val parts = Chunker.split(t.substring(headEnd), 3_000)
        val pick = if (parts.size <= DIGEST_PARTS) parts.indices.toList()
            else (0 until DIGEST_PARTS).map { (it * (parts.size - 1).toDouble() / (DIGEST_PARTS - 1)).toInt() }.distinct()
        val sb = StringBuilder("The beginning of the document:\n").append(head).append("\n\nSummaries of later parts:\n")
        val t0 = android.os.SystemClock.elapsedRealtime()
        var read = 0
        for (i in pick) {
            check()
            if (android.os.SystemClock.elapsedRealtime() - t0 > DIGEST_BUDGET_MS) break
            val s = LocalAi.summariseText(ctx, systemPrompt(),
                "Summarise this part of a longer document in 2-4 factual sentences. Keep names, dates, numbers and decisions; add nothing.\n\"\"\"\n${parts[i]}\n\"\"\"", 180)
            sb.append("Part ${i + 2} of ${parts.size + 1}: ").append(NotesCleaner.text(s).take(600)).append('\n')
            read++
        }
        if (read < parts.size) sb.append("(This is a long document: the notes cover its beginning and ${read} of its ${parts.size} later parts.)\n")
        return sb.toString() to true
    }

    private const val DIGEST_SINGLE = 6_000
    private const val DIGEST_HEAD = 2_400
    private const val DIGEST_PARTS = 5
    private const val DIGEST_BUDGET_MS = 5 * 60_000L

    /** The app is English only: speech is recognised as English. */
    private fun whisperLanguage(): String = "en"

    /** The model's reply as a JSON object with at least a title or summary, or null. */
    internal fun parseNotes(text: String): JSONObject? {
        val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = t.indexOf('{'); val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(t.substring(start, end + 1)).takeIf { it.optString("title").isNotBlank() || it.optString("summary").isNotBlank() }
        } catch (_: Exception) { null }
    }

    private fun save(ctx: Context, card: Card, o: JSONObject) {
        val repo = App.instance.repo
        val details = o.optJSONArray("details").objects().mapNotNull {
            val l = it.optString("label").trim(); val v = it.optString("value").trim()
            if (l.isEmpty() || v.isEmpty()) null else Detail(l, v)
        }.toMutableList()
        // Places the model named, and dates found by code in the source text, as details.
        // Places written out in the person's own text or the transcript, found by code first.
        PlaceFinder.find(card.note + "\n" + o.optString("transcript")).forEach { p -> if (details.none { it.value.equals(p, true) }) details += Detail("Place", p) }
        o.optJSONArray("places").strings().forEach { p -> if (details.none { it.value.equals(p, true) || it.value.contains(p, true) }) details += Detail("Place", p) }
        // Dates written in the content, found by code: added once each, in words, and not again
        // when the model already has a row for the same day (in any format).
        run {
            val now = LocalDateTime.now()
            val fmt = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)
            val dayOf = { v: String -> WhenFinder.find(v, now, 1).firstOrNull()?.date }
            val isoDay = Regex("""^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2})?$""")
            details.removeAll { d -> isoDay.matches(d.value.trim()) && details.any { o2 -> o2 !== d && !isoDay.matches(o2.value.trim()) && dayOf(o2.value) == dayOf(d.value) } }
            details.replaceAll { d -> if (Regex("""^\d{4}-\d{2}-\d{2}$""").matches(d.value.trim())) runCatching { d.copy(value = LocalDate.parse(d.value.trim()).format(fmt)) }.getOrDefault(d) else d }
            DateFinder.find(card.note.take(20_000) + "\n" + o.optString("transcript")).forEach { iso ->
                val day = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return@forEach
                if (details.none { dayOf(it.value) == day }) details += Detail("Date", day.format(fmt))
            }
        }
        // No "Language" row: the app works in English only, so it would say the same thing every time.
        if (o.has("format_note")) details += Detail("Note", "Format not recognised: the model's reply is kept as plain text.")
        // One line per fact: the model sometimes repeats the language, or a shorter form of a value
        // under the same label ("Time: 1pm" next to "Time: next Tuesday at 1pm").
        run {
            // The model sometimes copies the prompt's "Current date and time" line as a detail
            // (seen on the phone: "date: Friday 2026-11-20 19:34 GMT+05:30"). That isn't content.
            val promptClock = Regex("""(?i)(monday|tuesday|wednesday|thursday|friday|saturday|sunday) \d{4}-\d{2}-\d{2} \d{2}:\d{2}""")
            // Also today's date alone in the prompt's own form ("Friday 2026-11-20", seen on the phone).
            val today = java.time.LocalDate.now().toString()
            val promptDay = Regex("""(?i)^((monday|tuesday|wednesday|thursday|friday|saturday|sunday),?\s+)?${Regex.escape(today)}(\s+\d{2}:\d{2})?(\s+\S+)?$""")
            val todayText = Regex("""(?i)^(today is\s+)?""" + Regex.escape(today()) + """\.?$""")
            details.removeAll { promptClock.containsMatchIn(it.value) || promptDay.matches(it.value.trim()) || todayText.matches(it.value.trim()) }
            details.replaceAll { d -> d.copy(label = d.label.replaceFirstChar { it.titlecase(Locale.ROOT) }) }
            val lang = details.lastOrNull { it.label.equals("Language", true) }
            details.removeAll { it.label.equals("Language", true) && it !== lang }
            val keep = details.filter { d ->
                details.none { o2 -> o2 !== d && o2.label.equals(d.label, true) && o2.value.length > d.value.length && o2.value.contains(d.value, true) }
            }.distinctBy { it.label.lowercase(Locale.ROOT) + "\u0000" + it.value.lowercase(Locale.ROOT) }
            details.clear(); details.addAll(NotesCleaner.details(keep))
        }
        val link = Meta.link(card.meta)
        val doc = Meta.doc(card.meta)
        // Who-posted-it rows only when the content names that person or site; the small model
        // otherwise fills them with guesses ("User: User", "Platform: Social media").
        run {
            val source = TextSearch.normalise(listOfNotNull(card.note, o.optString("transcript"), card.title.takeIf { card.userTitle }, link?.title, link?.description, link?.author, link?.site, link?.host).joinToString(" "))
            val who = Regex("""(?i)^(user|username|user name|account|handle|platform|app|author|creator|channel|poster|posted by|uploader|site|website|source)$""")
            details.removeAll { who.matches(it.label.trim()) && !source.contains(TextSearch.normalise(it.value)) }
        }
        // Facts found by code go first, as they are: the file type and pages, the link's site.
        val known = ArrayList<Detail>()
        doc?.let { d ->
            known += Detail("File", d.label)
            d.pages?.let { known += Detail(d.unit.replaceFirstChar { c -> c.titlecase(Locale.ROOT) }.let { u -> if (it == 1) u.removeSuffix("s") else u }, it.toString()) }
        }
        // A photo's own information (EXIF): when it was taken and, if saved by the camera, where.
        if (card.type == TYPE_IMAGE) card.file?.let { PhotoInfo.read(it) }?.let { p ->
            p.taken?.let { known += Detail("Taken", PhotoInfo.takenText(it)) }
            if (p.hasLocation) known += Detail("Location", PhotoInfo.coords(p.lat!!, p.lon!!))
            p.camera?.let { known += Detail("Camera", it) }
        }
        link?.let { l ->
            known += Detail("Link", l.host)
            if (l.ok) { l.site?.let { known += Detail("Site", it) }; l.author?.let { known += Detail("Author", it) } }
        }
        details.removeAll { d -> known.any { it.label.equals(d.label, true) } || d.label.equals("Language", true) }
        details.addAll(0, known)
        // Short and to the point: long values are cut at a word, and at most 10 rows are kept.
        details.replaceAll { d -> if (d.value.length <= 90) d else d.copy(value = d.value.take(90).substringBeforeLast(' ').trimEnd(',', ';') + "…") }
        while (details.size > 10) details.removeAt(details.lastIndex)
        val actions = NotesCleaner.lines(o.optJSONArray("action_items").strings())
        val v = ContentValues().apply {
            // A title the person typed is theirs: a later analysis never replaces it.
            // A public page's own title is a fact ("Me at the zoo"); the model tends to shorten it ("Zoo").
            val pageTitle = link?.takeIf { it.ok }?.title?.takeIf { it.length in 3..90 }
            // A model title that is only the site's name ("ChatGPT") says nothing about this capture.
            val modelTitle = NotesCleaner.text(o.optString("title")).takeUnless { t ->
                link != null && (t.equals(link.site, true) || t.equals(link.host, true))
            } ?: ""
            if (!card.userTitle) put("title", (doc?.title ?: pageTitle ?: modelTitle).ifBlank {
                // No title from the model: the document's own title, the page's title, or the file name.
                doc?.title ?: link?.title ?: doc?.name?.substringBeforeLast('.') ?: link?.let { linkLabel(it) } ?: ""
            }.take(120))
            // The small model often narrates the sharing ("Jack shared a link to a tweet") instead of
            // the post; then the page's own text says it better (seen on the phone, 2026-11-20).
            // A summary of the screen rather than the chat ("A chat interface showing the first message") is dropped.
            val said = NotesCleaner.text(o.optString("summary")).takeUnless {
                link?.kind == "AI chat" && Regex("""(?i)\bchat interface\b|\bfirst message\b|\bhosted on\b|\bchat (page|window|screen)\b""").containsMatchIn(it)
            } ?: ""
            val narrates = Regex("""(?i)\b(shared?|shares|sharing)\b.{0,40}\blink\b|\ba link to\b|^(the )?(user|person)\b""").containsMatchIn(said)
            // A social post's own words are its best summary; the model adds outside "knowledge"
            // (seen: a Threads post summarised as a biography of its author).
            val postText = link?.takeIf { it.ok && it.site in setOf("X", "Threads", "Instagram", "Facebook") }?.description
                ?.takeIf { it.length >= 3 }?.let { if (it.length <= 240) it else it.take(240).substringBeforeLast(' ') + "…" }
            put("summary", postText ?: if (narrates && link?.ok == true) (link.description?.take(240) ?: link.title ?: said) else said)
            put("tags", Json.strings(NotesCleaner.lines(o.optJSONArray("tags").strings(), dropFiller = false).take(6)))
            put("details", Json.details(details))
            put("actions", Json.strings(actions))
            if (card.type == TYPE_VOICE) {
                put("transcript", o.optString("transcript").trim())
                put("topics", Json.strings(NotesCleaner.lines(o.optJSONArray("topics").strings(), dropFiller = false)))
                put("key_moments", Json.strings(NotesCleaner.lines(o.optJSONArray("key_moments").strings())))
            } else {
                put("key_moments", Json.strings(NotesCleaner.lines(o.optJSONArray("highlights").strings())))
            }
            put("status", Status.DONE)
            putNull("error")
        }
        repo.update(card.id, v)

        // Tasks and events found in the capture are stored only as suggestions: nothing is created
        // and no reminder is set until the person taps to add one. Model output is never an instruction.
        repo.deleteItemsForCard(card.id)
        val zone = ZoneId.systemDefault()
        val check = DateCheck.forCard(card, o.optString("transcript"), zone)
        val seen = HashSet<String>()
        o.optJSONArray("tasks").objects().forEach { t ->
            val title = NotesCleaner.text(t.optString("title"))
            if (title.isEmpty() || NotesCleaner.isFiller(title) || !seen.add("task:" + title.lowercase(Locale.ROOT))) return@forEach
            val due = check.task(t.optString("due"))
            repo.addItem("task", title, due?.first, null, due?.second ?: false, cardId = card.id, suggested = true)
        }
        o.optJSONArray("events").objects().forEach { e ->
            val title = NotesCleaner.text(e.optString("title"))
            if (title.isEmpty() || !seen.add("event:" + title.lowercase(Locale.ROOT))) return@forEach
            val start = check.event(e.optString("start"))
            val end = parseWhen(e.optString("end"), zone)?.takeIf { start != null && !start.second }
            val allDay = start?.second ?: false
            val endMs = end?.first?.takeIf { s -> start == null || s > start.first }
            repo.addItem("event", title, start?.first, endMs, allDay, e.optString("location").trim(), card.id, suggested = true)
        }

        repo.card(card.id)?.let { Notifications.ready(ctx, it) }
    }

    private fun suggest(cardId: Long, emb: FloatArray, hint: String): Suggestion? {
        val repo = App.instance.repo
        if (repo.cardCollectionIds(cardId).isNotEmpty()) return null
        val all = repo.embeddings().toMap()
        var best: Pair<Long, Float>? = null
        var bestName = ""
        for (col in repo.collections()) {
            val vecs = repo.collectionCards(col.id).mapNotNull { all[it.id] }
            val c = Similarity.centroid(vecs) ?: continue
            val s = Similarity.cosine(emb, c)
            if (best == null || s > best.second) { best = col.id to s; bestName = col.name }
        }
        if (best != null && best.second >= Limits.SUGGEST_ADD_MIN) return Suggestion("add", best.first, bestName)
        if (hint.isNotEmpty() && repo.collections().none { it.name.equals(hint, true) }) {
            val similar = all.count { (id, v) -> id != cardId && Similarity.cosine(emb, v) >= Limits.SUGGEST_CREATE_MIN }
            if (similar >= Limits.SUGGEST_CREATE_COUNT) return Suggestion("create", 0, hint)
        }
        return null
    }

    // ---------- related captures, search, questions ----------

    fun related(card: Card, limit: Int = 4): List<Card> {
        val emb = card.embedding ?: return emptyList()
        val repo = App.instance.repo
        return repo.embeddings().asSequence()
            .filter { it.first != card.id }
            .map { it.first to Similarity.cosine(emb, it.second) }
            .filter { it.second >= Limits.RELATED_MIN }
            .sortedByDescending { it.second }
            .take(limit)
            .mapNotNull { repo.card(it.first) }
            .toList()
    }

    data class SearchResult(
        val cards: List<Card>,
        val answer: String?,
        val semantic: Boolean,
        val note: String?,
        /** Why each capture matched: the field and a snippet. */
        val hits: Map<Long, TextSearch.Hit> = emptyMap(),
    )

    fun isQuestion(q: String): Boolean {
        val s = q.trim().lowercase(Locale.ROOT)
        if (s.endsWith("?")) return true
        val words = listOf("what", "when", "where", "who", "which", "how", "why", "did", "do", "does", "is", "are", "can", "should", "find me", "show me", "remind me")
        return words.any { s.startsWith("$it ") }
    }

    fun search(query: String): SearchResult {
        val app = App.instance
        val repo = app.repo
        val q = query.trim()
        if (q.isEmpty()) return SearchResult(emptyList(), null, false, null)
        val cards = repo.cards()
        // Whole-word search over every text a capture holds (see TextSearch). No search by meaning:
        // this version has no verified on-device embedding model, and results must contain the words.
        val colNames = repo.collections().associate { it.id to it.name }
        val hits = HashMap<Long, TextSearch.Hit>()
        val note: String? = null
        val scored = cards.mapNotNull { c ->
            val h = TextSearch.match(q, searchFields(c, repo.cardCollectionIds(c.id).mapNotNull { colNames[it] })) ?: return@mapNotNull null
            hits[c.id] = h
            c to h.score
        }.sortedWith(compareByDescending<Pair<Card, Int>> { it.second }.thenByDescending { it.first.created })
            .map { it.first }
            .take(40)

        var answer: String? = null
        var shownNote = note
        if (LocalAi.ready && isQuestion(q) && scored.isNotEmpty()) {
            answer = try {
                ask(q, scored.take(6))
            } catch (e: AiException) {
                shownNote = e.userMessage
                null
            }
        }
        return SearchResult(scored, answer, false, shownNote, hits)
    }

    /** Every text of a capture that search looks in, with how much a match there counts. */
    internal fun searchFields(c: Card, collections: List<String>): List<TextSearch.Field> = listOf(
        TextSearch.Field("Title", c.title, 60),
        TextSearch.Field("Summary", c.summary, 50),
        TextSearch.Field("Tags", c.tags.joinToString(", "), 45),
        TextSearch.Field("Your note", if (c.type == TYPE_NOTE) "" else c.note, 40),
        TextSearch.Field(if (c.file != null && c.type == TYPE_NOTE) "Document text" else "Text", if (c.type == TYPE_NOTE) c.note else "", 35),
        TextSearch.Field("Transcript", c.transcript, 35),
        TextSearch.Field("Details", c.details.joinToString("\n") { "${it.label}: ${it.value}" }, 30),
        TextSearch.Field("Key points", (c.topics + c.keyMoments).joinToString("\n"), 25),
        TextSearch.Field("Action items", c.actions.joinToString("\n"), 25),
        TextSearch.Field("Link", listOfNotNull(c.sourceApp).joinToString(" "), 20),
        TextSearch.Field("Collection", collections.joinToString(", "), 20),
    )

    private fun ask(question: String, cards: List<Card>): String {
        val ctxText = buildString {
            cards.forEachIndexed { i, c ->
                append("[${i + 1}] ${c.displayTitle} (saved ${fmtDate(c.created)}, ${c.type})\n")
                if (c.summary.isNotBlank()) append("Summary: ${c.summary}\n")
                c.details.forEach { append("${it.label}: ${it.value}\n") }
                if (c.note.isNotBlank()) append("Note: ${c.note.take(1500)}\n")
                if (c.transcript.isNotBlank()) append("Transcript: ${c.transcript.take(2500)}\n")
                append('\n')
            }
        }
        val prompt = """Answer the question using only the person's saved captures below.
Be short and direct (one to three sentences). Mention which captures you used as [n].
If the captures don't contain the answer, say you couldn't find it in their space.
Today is ${now()}.

Question: $question

Captures:
${ctxText.take(9000)}"""
        return LocalAi.summariseText(App.instance, systemPrompt(), prompt, 200, Sampling.TEXT_CARD).trim()
    }

    /** Candidate captures for a new collection with this name (smart collection): word matches on this phone. */
    fun smartCollection(name: String): List<Card> {
        val terms = name.lowercase(Locale.ROOT).split(Regex("\\W+")).filter { it.length > 2 }
        if (terms.isEmpty()) return emptyList()
        return App.instance.repo.cards()
            .filter { c -> val t = c.searchText.lowercase(Locale.ROOT); terms.any { t.contains(it) } }
            .take(24)
    }

    // ---------- prompts ----------

    private fun languageLine(): String = "Write in English."

    // Measured on the phone: when this listed "screenshots, photos, voice recordings and notes", the
    // model echoed them back as tasks ("Save screenshot", "Save photo", "Save voice recording").
    private fun systemPrompt() =
        "You write short, factual notes about one thing a person saved. " +
            "Only state what the content itself shows or says. Never invent dates, names, numbers or tasks. " +
            "An empty field is better than a guess."

    /**
     * Plain "Friday, 20 November 2026". Measured on the phone: with a full clock line
     * ("Friday 2026-11-20 19:34 GMT+05:30") the 0.8B model copied it into the details as content.
     */
    private fun today(): String =
        java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))

    private fun now(): String =
        ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm z", Locale.ENGLISH))

    /**
     * Dates written in [text], worked out by code from when the capture was made. Measured on the
     * phone: told only today's date, the 0.8B model wrote "next Tuesday" as today's date.
     */
    private fun dateHints(card: Card, text: String): String {
        if (text.isBlank()) return ""
        val now = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(card.created), ZoneId.systemDefault())
        val h = WhenFinder.hints(text, now)
        return if (h.isEmpty()) "" else "\nDates written in the content, already worked out (use exactly these dates): $h."
    }

    private fun common(card: Card, includeNote: Boolean = true, source: String = card.note) = """
(For resolving words like "tomorrow" or "next Friday" only, not part of the content: today is ${today()}.)${dateHints(card, source)}
${languageLine()}
Reply with only one compact JSON object on a single line (no indentation), with these keys in this order. Leave a field empty ("" or []) when the content doesn't say it; never guess. Copy names, places and words exactly as written.
- "title": a short title (max 8 words).
- "summary": 1-3 sentences on what this is.
- "highlights": up to 5 key points from the content, one short sentence each.
- "tags": up to 4 one-word categories that fit this content.
- "details": the key facts as {"label": ..., "value": ...}: a one-word label and a short value of a few words (date, time, place, price, people, objects, phone number, link, code). At most 8 rows, one fact each; no row when the content doesn't give the value.
- "action_items": only things the content itself asks someone to do (a deadline, a reminder, a request, a booking). If it asks for nothing, [].
- "tasks": the same actions as {"title": ..., "due": "yyyy-MM-ddTHH:mm" or "yyyy-MM-dd" or ""}; the due date only if the content gives one.
- "events": scheduled happenings as {"title", "start", "end", "all_day", "location"}; only when the content gives the date.
- "places": places named in the content.
- "language": the language of the content.
""".trim() + (if (includeNote && card.note.isNotBlank()) "\nThe person added this note: \"${card.note.take(1500)}\"" else "") +
        (card.sourceApp?.let { "\nIt was saved from the app \"$it\"." } ?: "") +
        // A typed title is the person's label for it, a hint for the topic and the tags: it is not
        // evidence (titled "youtuber reactions", a screenshot still only shows what it shows).
        // A title the person typed is their correction: they know what they saved. The notes follow
        // it (what it is, who, where), but nothing is added that neither the title nor the content gives.
        (if (card.userTitle && card.title.isNotBlank()) "\nThe person corrected the title of this capture to \"${card.title.take(120)}\". They know what it is: " +
            "treat that title as correct and make the summary, tags and details match it (use its names for the subject, people and place). " +
            "State what the title says plainly (not \"possibly\"), and describe only what is really in the content: don't add people or things that aren't there. " +
            "Don't add facts that neither the title nor the content gives, and keep the title exactly as written." else "")

    private fun imagePrompt(card: Card) =
        "This is a screenshot or photo the person saved. Read the visible text carefully and describe only what it shows.\n" +
            "If it is a screenshot of an app screen, a pop-up or a message from the phone (offline, error, permission, sign-in, download), " +
            "describe it as a screenshot of that screen; don't present it as facts about the person.\n" +
            "For a photo, say what is in it: add details rows \"Objects\" (the main things you can see, comma-separated), " +
            "\"People\" (who is in it: a name only when it is written in the picture or given by the person, otherwise describe them, e.g. \"two children\"), " +
            "and \"Place\" (only when a sign, text or a well-known landmark shows where it is). Never guess who a person is from their face.\n" +
            common(card)

    private fun notePrompt(card: Card, body: String, digest: Boolean): String {
        val doc = Meta.doc(card.meta)
        val what = doc?.let { d ->
            "This is the text of a ${d.label}" + (d.pages?.let { " (" + Meta.count(it, d.unit) + ")" } ?: "") + (d.name?.let { " named \"${it.take(120)}\"" } ?: "") + " that the person saved. " +
                (d.title?.let { "Its stored title is \"${it.take(120)}\". " } ?: "For the title, use the document's own title (usually in its first lines); don't use the file name if the text has a real title. ") + "\n"
        } ?: "This is text the person saved:\n"
        return what + (if (digest) "It is long, so you get its beginning and summaries of later parts.\n" else "") +
            "\"\"\"\n$body\n\"\"\"\n" + common(card, includeNote = false, source = body)
    }

    /** A plain name for a link that couldn't be read: "Instagram post", "Post on X", or the site's address. */
    internal fun linkLabel(l: Meta.Link): String {
        val h = l.host.lowercase(Locale.ROOT); val u = (l.finalUrl ?: l.url).lowercase(Locale.ROOT)
        if (l.kind == "AI chat") return l.title ?: "Shared ${l.site ?: "AI"} chat"
        return when {
            "instagram.com" in h -> if ("/reel" in u) "Instagram reel" else "Instagram post"
            h == "x.com" || "twitter.com" in h -> "Post on X"
            "facebook.com" in h || h == "fb.watch" -> if ("/reel" in u || "/watch" in u || "fb.watch" in h) "Facebook video" else "Facebook post"
            "tiktok.com" in h -> "TikTok video"
            "threads.net" in h || "threads.com" in h -> "Threads post"
            "linkedin.com" in h -> "LinkedIn post"
            "reddit.com" in h -> "Reddit post"
            "youtube.com" in h || h == "youtu.be" -> "YouTube video"
            else -> "Link from ${l.host}"
        }
    }

    /** A shared link: what the public page says about itself, or only the shared text when it couldn't be read. */
    private fun linkPrompt(card: Card, l: Meta.Link, withPicture: Boolean): String {
        val facts = listOfNotNull(l.site?.let { "Site: $it" }, l.kind?.let { "Type: $it" }, l.title?.let { "Title: $it" },
            l.author?.let { "Author: $it" }, l.description?.let { "Description: ${it.take(900)}" }).joinToString("\n")
        val page = if (l.ok && facts.isNotEmpty())
            "Public page details, from the page's own preview tags (they may be incomplete):\n$facts\n" +
                (if (withPicture && l.kind == "AI chat") "The picture is ${l.site ?: "the assistant"}'s own preview of this shared chat: it shows the chat's first messages. " +
                    "Read the words written in the picture. The title is the first line of that text, copied as written (without emoji). " +
                    "The summary says what the conversation is about, using the words in the picture. Never describe the screen itself (\"a chat interface\", \"the first message\").\n"
                else if (withPicture) "The picture is the page's preview image (for a post, reel or video, usually its cover).\n" else "")
        else "The page itself was not read (" + (l.message?.removePrefix("Preview not available: ")?.trimEnd('.') ?: "link previews are off") + "). " +
            "You only have the shared text: don't guess what the page, post or video contains.\n"
        return "The person shared a link (${l.host}).\n" + page +
            "Shared text:\n\"\"\"\n${card.note.take(3000)}\n\"\"\"\n" +
            "Say what this link is (for example a post, reel, video, thread, article or product page) and what it is about, using only these details. " +
            "The summary describes the page itself (for example \"A YouTube video of …\", \"A Wikipedia article about …\"), never the sharing: don't write \"User shared\", \"The person shared\" or \"a link to\". " +
            "Don't add rows for the user, account or platform unless the details above name them.\n" +
            common(card, includeNote = false, source = card.note + "\n" + facts)
    }

    private fun voicePrompt(card: Card, transcript: String): String {
        val fmt = { ms: Long -> "%d:%02d".format(ms / 60000, (ms / 1000) % 60) }
        val windows = keyMomentWindows(card.marks, card.durationMs)
        val marks = if (windows.isEmpty()) "" else
            "\nThe person marked important moments while recording. Each key moment covers the audio from the mark " +
                "to ${Limits.KEY_MOMENT_WINDOW_MS / 1000} seconds after it (overlapping ones merged): " +
                windows.joinToString(", ") { (a, b) -> "${fmt(a)}-${fmt(b)}" } +
                ". Write one key_moments entry per range, summarising what was said in it."
        return """This is the machine transcript of a voice recording (a memo, a meeting or a conversation). It may contain recognition mistakes.
""" + "\"\"\"\n${transcript.take(9000)}\n\"\"\"\n" + """Also include:
- "topics": the main topics, a few words each.
- "key_moments": the moments worth remembering, one sentence each.$marks
""" + common(card, source = card.note + "\n" + transcript)
    }

    private fun schema(type: String): JSONObject {
        fun str() = JSONObject().put("type", "STRING")
        fun arr(item: JSONObject) = JSONObject().put("type", "ARRAY").put("items", item)
        fun obj(props: JSONObject, vararg req: String) = JSONObject().put("type", "OBJECT").put("properties", props)
            .put("required", JSONArray().apply { req.forEach { put(it) } })
        val props = JSONObject()
            .put("title", str())
            .put("summary", str())
            .put("tags", arr(str()))
            .put("details", arr(obj(JSONObject().put("label", str()).put("value", str()), "label", "value")))
            .put("tasks", arr(obj(JSONObject().put("title", str()).put("due", str()), "title")))
            .put("events", arr(obj(
                JSONObject().put("title", str()).put("start", str()).put("end", str())
                    .put("all_day", JSONObject().put("type", "BOOLEAN")).put("location", str()), "title", "start")))
            .put("collection_name", str())
        val req = mutableListOf("title", "summary", "tags", "details", "tasks", "events")
        if (type == TYPE_VOICE) {
            props.put("transcript", str()).put("topics", arr(str()))
                .put("action_items", arr(str())).put("key_moments", arr(str()))
            req += listOf("transcript", "topics", "action_items")
        }
        return obj(props, *req.toTypedArray())
    }

    // ---------- helpers ----------

    /**
     * Checks the model's dates against the dates written in the capture's own text (typed notes,
     * documents, voice transcripts, a photo's typed or spoken note). A small model sometimes fills
     * in a date that isn't there, often today's.
     * - A date the text also gives is kept (and gets the text's time when the model left it out).
     * - A date the text doesn't give is replaced by the text's date when there is exactly one, else dropped.
     * - With no date in the text: an event with no date written gets none; a task keeps none.
     * Pictures without their own text can't be checked this way, so the model's dates stay.
     */
    internal class DateCheck(
        private val found: List<WhenFinder.Found>?, private val zone: ZoneId, private val pictureOnly: Boolean,
        private val captureDay: LocalDate? = null,
    ) {
        private fun ms(f: WhenFinder.Found) = parseWhen(f.toIso(), zone)

        private fun check(model: String, fillWhenEmpty: Boolean): Pair<Long, Boolean>? {
            val m = parseWhen(model, zone)
            // A picture with no text of its own: the model's date can't be checked. A date on the
            // capture's own day is dropped: on the phone that was the model filling the gap
            // ("Save screenshot · Today 12:00 pm"), not a date in the picture.
            if (found == null) return m?.takeIf { v ->
                captureDay == null || java.time.Instant.ofEpochMilli(v.first).atZone(zone).toLocalDate() != captureDay
            }
            val modelDate = model.trim().take(10).let { s -> runCatching { LocalDate.parse(s) }.getOrNull() }
            val match = found.firstOrNull { it.date == modelDate }
            return when {
                match != null -> if (m != null && m.second && match.time != null) ms(match) else m
                // A picture's own words may carry a date the note doesn't: keep the model's then.
                pictureOnly && m != null -> m
                found.size == 1 && (m != null || fillWhenEmpty) -> ms(found[0])
                else -> null
            }
        }

        fun event(model: String) = check(model, fillWhenEmpty = true)
        fun task(model: String) = check(model, fillWhenEmpty = false)

        companion object {
            fun forCard(card: Card, transcript: String, zone: ZoneId): DateCheck {
                val text = (card.note + "\n" + transcript).trim()
                val now = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(card.created), zone)
                return when {
                    card.type == TYPE_IMAGE && text.isEmpty() -> DateCheck(null, zone, true, now.toLocalDate())
                    else -> DateCheck(WhenFinder.find(text, now), zone, card.type == TYPE_IMAGE)
                }
            }
        }
    }

    /** Parses "2026-11-20T14:30" or "2026-11-20"; second = all-day. */
    fun parseWhen(s: String?, zone: ZoneId): Pair<Long, Boolean>? {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return null
        return try {
            if (t.length <= 10) LocalDate.parse(t).atStartOfDay(zone).toInstant().toEpochMilli() to true
            else LocalDateTime.parse(t.take(16)).atZone(zone).toInstant().toEpochMilli() to false
        } catch (e: Exception) {
            null
        }
    }

    private fun fmtDate(ms: Long) = java.text.DateFormat.getDateInstance().format(java.util.Date(ms))

    /** Internal so the on-device rule measurement can run it. */
    internal fun jpegBase64(path: String): String {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > Limits.IMAGE_MAX_PX * 2) sample *= 2
        val bmp0 = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw AiException("The image couldn't be read.")
        val rot = try {
            when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) { 0f }
        val scale = minOf(1f, Limits.IMAGE_MAX_PX.toFloat() / maxOf(bmp0.width, bmp0.height))
        val m = Matrix().apply { postScale(scale, scale); postRotate(rot) }
        val bmp = Bitmap.createBitmap(bmp0, 0, 0, bmp0.width, bmp0.height, m, true)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** Decodes a picture, honours its EXIF rotation and scales it under [maxSide] and the hard cap; RGB bytes. */
    internal fun loadRgb(path: String, maxSide: Int): Triple<ByteArray, Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0) throw AiException("The image couldn't be read.")
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp0 = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw AiException("The image couldn't be read.")
        val rot = try {
            when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) { 0f }
        var scale = minOf(1f, maxSide.toFloat() / maxOf(bmp0.width, bmp0.height))
        val px = bmp0.width * scale * bmp0.height * scale
        if (px > ImagePolicy.HARD_CAP_PIXELS) scale *= Math.sqrt(ImagePolicy.HARD_CAP_PIXELS / px.toDouble()).toFloat()
        val m = Matrix().apply { postScale(scale, scale); postRotate(rot) }
        val bmp = Bitmap.createBitmap(bmp0, 0, 0, bmp0.width, bmp0.height, m, true)
        return rgb(bmp)
    }

    /** RGB bytes (no alpha, transparent areas on white) of a bitmap. */
    internal fun rgb(bmp: Bitmap): Triple<ByteArray, Int, Int> {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 3)
        for (i in px.indices) {
            val c = px[i]; val a = (c ushr 24) and 0xff
            fun ch(v: Int) = ((v * a + 255 * (255 - a)) / 255).toByte()
            out[i * 3] = ch((c shr 16) and 0xff); out[i * 3 + 1] = ch((c shr 8) and 0xff); out[i * 3 + 2] = ch(c and 0xff)
        }
        return Triple(out, w, h)
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).trim().takeIf { s -> s.isNotEmpty() } }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
