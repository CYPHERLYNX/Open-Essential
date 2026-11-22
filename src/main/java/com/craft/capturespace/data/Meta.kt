package com.craft.capturespace.data

import org.json.JSONObject

/**
 * Facts about a capture found by code, not by the model, stored as JSON in the card's `meta` column:
 * a document's file type, name, title and page count, or a shared link's public preview.
 */
object Meta {
    /** [cover]: a picture of the file's first page (PDF) or the thumbnail saved inside it (Office), on this phone. */
    data class Doc(val name: String?, val kind: String, val label: String, val pages: Int?, val unit: String, val title: String?, val cover: String? = null)

    /**
     * A shared link. [state]: "new" (not fetched), "asked" (waiting for the person's choice),
     * "skipped" (they chose Not now / previews off), "ok", or a failure: "login", "offline",
     * "timeout", "blocked", "notfound", "error". [message] is the reason shown for a failure.
     */
    data class Link(
        val url: String,
        val state: String = "new",
        val finalUrl: String? = null,
        val site: String? = null,
        val title: String? = null,
        val description: String? = null,
        val author: String? = null,
        val kind: String? = null,
        val image: String? = null,
        val message: String? = null,
        val fetchedAt: Long = 0,
    ) {
        val ok get() = state == "ok"
        val failed get() = state in setOf("login", "offline", "timeout", "blocked", "notfound", "error")
        val host: String get() = runCatching { java.net.URI(finalUrl ?: url).host?.removePrefix("www.") }.getOrNull() ?: url
    }

    /** "1 page", "12 pages", "1 slide"... */
    fun count(n: Int, unit: String): String = "$n " + if (n == 1) unit.removeSuffix("s") else unit

    fun doc(json: String?): Doc? = parse(json)?.optJSONObject("doc")?.let { o ->
        Doc(o.str("name"), o.optString("kind"), o.optString("label"), o.optInt("pages").takeIf { it > 0 }, o.optString("unit", "pages"), o.str("title"), o.str("cover"))
    }

    fun link(json: String?): Link? = parse(json)?.optJSONObject("link")?.let { o ->
        Link(o.optString("url"), o.optString("state", "new"), o.str("final_url"), o.str("site"), o.str("title"), o.str("description"),
            o.str("author"), o.str("kind"), o.str("image"), o.str("message"), o.optLong("fetched_at"))
    }

    fun of(doc: Doc): String = JSONObject().put("doc", JSONObject().apply {
        doc.name?.let { put("name", it) }; put("kind", doc.kind); put("label", doc.label)
        doc.pages?.let { put("pages", it) }; put("unit", doc.unit); doc.title?.let { put("title", it) }; doc.cover?.let { put("cover", it) }
    }).toString()

    fun of(l: Link): String = JSONObject().put("link", JSONObject().apply {
        put("url", l.url); put("state", l.state)
        l.finalUrl?.let { put("final_url", it) }; l.site?.let { put("site", it) }; l.title?.let { put("title", it) }
        l.description?.let { put("description", it) }; l.author?.let { put("author", it) }; l.kind?.let { put("kind", it) }
        l.image?.let { put("image", it) }; l.message?.let { put("message", it) }; if (l.fetchedAt > 0) put("fetched_at", l.fetchedAt)
    }).toString()

    private fun parse(json: String?): JSONObject? = if (json.isNullOrBlank()) null else try { JSONObject(json) } catch (_: Exception) { null }
    private fun JSONObject.str(k: String): String? = optString(k).trim().takeIf { has(k) && it.isNotEmpty() }
}
