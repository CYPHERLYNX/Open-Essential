package com.craft.capturespace.data

import org.json.JSONArray
import org.json.JSONObject

const val TYPE_IMAGE = "image"
const val TYPE_VOICE = "voice"
const val TYPE_NOTE = "note"

/** Analysis states a capture moves through. */
object Status {
    const val PENDING = "pending"
    const val PROCESSING = "processing"
    /** Analysed before and being analysed again (after a rename or "Analyse again"): the old notes stay shown. */
    const val UPDATING = "updating"
    const val DONE = "done"
    const val FAILED = "failed"
    const val OFFLINE = "offline"
    const val NO_KEY = "no_key"
    const val OFF = "off"
}

data class Detail(val label: String, val value: String)

/** "Add to your X collection?" or "Create an X collection?" */
data class Suggestion(val kind: String, val collectionId: Long, val name: String)

data class Card(
    val id: Long,
    val type: String,
    val created: Long,
    val title: String,
    val note: String,
    val file: String?,
    val mime: String?,
    val sourceApp: String?,
    val status: String,
    val summary: String,
    val tags: List<String>,
    val details: List<Detail>,
    val transcript: String,
    val topics: List<String>,
    val actions: List<String>,
    val keyMoments: List<String>,
    val marks: List<Long>,
    val durationMs: Long,
    val error: String?,
    val deletedAt: Long?,
    val embedding: FloatArray?,
    val suggestion: Suggestion?,
    /** A voice note recorded with a photo or screenshot (hold the mic on the capture screen). */
    val audio: String? = null,
    val audioMs: Long = 0,
    /** The title was typed by the person; analyses keep it. */
    val userTitle: Boolean = false,
    /** Facts found by code (not the model), as JSON: see [Meta]. */
    val meta: String? = null,
) {
    /** Analysed: done, or being updated with the earlier notes still shown. */
    val hasNotes: Boolean get() = status == Status.DONE || status == Status.UPDATING

    val displayTitle: String
        get() = title.ifBlank {
            when (type) {
                TYPE_VOICE -> "Voice recording"
                TYPE_IMAGE -> "Image"
                else -> note.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: "Note"
            }
        }

    /** Everything a person might search for, in one string. */
    val searchText: String
        get() = buildString {
            append(title).append('\n').append(summary).append('\n')
            append(tags.joinToString(" ")).append('\n').append(note).append('\n')
            details.forEach { append(it.label).append(' ').append(it.value).append('\n') }
            append(topics.joinToString(" ")).append('\n')
            append(actions.joinToString(" ")).append('\n')
            append(transcript.take(6000))
            sourceApp?.let { append('\n').append(it) }
        }
}

data class CollectionInfo(
    val id: Long,
    val name: String,
    val created: Long,
    val count: Int,
    val coverFile: String?,
)

/** A task or an event found in a capture or added by hand. */
data class PlanItem(
    val id: Long,
    val cardId: Long?,
    val kind: String, // "task" or "event"
    val title: String,
    val start: Long?,
    val end: Long?,
    val allDay: Boolean,
    val location: String,
    val done: Boolean,
    val created: Long,
    /** Found by the AI in a capture; not a real task/event (and no reminder) until the person adds it. */
    val suggested: Boolean = false,
)

object Json {
    fun strings(list: List<String>): String = JSONArray().apply { list.forEach { put(it) } }.toString()
    fun longs(list: List<Long>): String = JSONArray().apply { list.forEach { put(it) } }.toString()

    fun parseStrings(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { a.optString(it).takeIf { v -> v.isNotBlank() } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun parseLongs(s: String?): List<Long> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { a.optLong(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun details(list: List<Detail>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("label", it.label).put("value", it.value)) }
    }.toString()

    fun parseDetails(s: String?): List<Detail> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull {
                val o = a.optJSONObject(it) ?: return@mapNotNull null
                val l = o.optString("label").trim()
                val v = o.optString("value").trim()
                if (l.isEmpty() || v.isEmpty()) null else Detail(l, v)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun suggestion(s: Suggestion?): String? = s?.let {
        JSONObject().put("kind", it.kind).put("collectionId", it.collectionId).put("name", it.name).toString()
    }

    fun parseSuggestion(s: String?): Suggestion? {
        if (s.isNullOrBlank()) return null
        return try {
            val o = JSONObject(s)
            Suggestion(o.getString("kind"), o.optLong("collectionId"), o.getString("name"))
        } catch (e: Exception) {
            null
        }
    }
}
