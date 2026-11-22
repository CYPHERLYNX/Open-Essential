package com.craft.capturespace.capture

import java.net.URI

/**
 * Reads the public preview tags a page publishes about itself (Open Graph, Twitter card, <title>,
 * description), the same details a messaging app shows for a link. Pure code, no network.
 */
object PageMeta {
    data class Page(
        val title: String?,
        val description: String?,
        val image: String?,
        val site: String?,
        val author: String?,
        val type: String?,
    )

    private val metaTag = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val attr = Regex("""([a-zA-Z:_-]+)\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'>]+))""")
    private val titleTag = Regex("""<title[^>]*>([\s\S]{0,600}?)</title>""", RegexOption.IGNORE_CASE)

    fun parse(html: String, pageUrl: String): Page {
        // Only the <head> matters; stop at <body> so a long page isn't scanned.
        val head = html.substringBefore("<body", html).take(400_000)
        val m = HashMap<String, String>()
        metaTag.findAll(head).forEach { tag ->
            val a = attr.findAll(tag.value).associate { it.groupValues[1].lowercase() to (it.groupValues[3].ifEmpty { it.groupValues[4] }.ifEmpty { it.groupValues[5] }) }
            val key = (a["property"] ?: a["name"] ?: a["itemprop"])?.lowercase()?.trim() ?: return@forEach
            val content = a["content"]?.let { clean(it) }?.takeIf { it.isNotEmpty() } ?: return@forEach
            m.putIfAbsent(key, content)
        }
        val title = m["og:title"] ?: m["twitter:title"] ?: titleTag.find(head)?.groupValues?.get(1)?.let { clean(it) }?.takeIf { it.isNotEmpty() }
        val desc = m["og:description"] ?: m["twitter:description"] ?: m["description"]
        val image = (m["og:image:secure_url"] ?: m["og:image"] ?: m["og:image:url"] ?: m["twitter:image"] ?: m["twitter:image:src"])?.let { resolve(pageUrl, it) }
        val site = m["og:site_name"] ?: m["application-name"] ?: m["twitter:site"]?.takeIf { !it.startsWith("@") }
        val author = (m["author"] ?: m["article:author"] ?: m["twitter:creator"])?.takeIf { !it.startsWith("http") }
        return Page(title?.take(300), desc?.take(1200), image, site?.take(80), author?.take(120), m["og:type"]?.take(40))
    }

    /**
     * A page that shows only a sign-in screen or a bare site name to visitors who aren't logged in
     * (private posts, login walls). Such a page is reported as "needs login or is private".
     */
    fun looksLikeLoginWall(p: Page, finalUrl: String): Boolean {
        val path = runCatching { URI(finalUrl).path.orEmpty() + "?" + URI(finalUrl).query.orEmpty() }.getOrDefault("").lowercase()
        if (Regex("""(^|/)(login|log-in|signin|sign-in|sign_in|auth|authwall|checkpoint|accounts/login|consent|uas/login)(/|\?|$|\.)""").containsMatchIn(path)) return true
        val t = p.title.orEmpty().trim()
        if (Regex("""(?i)\b(log ?in|sign ?in|sign up|create an account)\b""").containsMatchIn(t) && p.description.isNullOrBlank()) return true
        if (Regex("""(?i)(log ?in|sign ?in) (to|with) (see|view|continue)|this (content|account|post) is (private|not available)|content isn'?t available|page isn'?t available""")
                .containsMatchIn(t + " " + p.description.orEmpty())) return true
        // Only the site's own name and nothing else: what most social sites show to logged-out visitors.
        val bare = t.isEmpty() || t.equals(p.site, true) || t.lowercase() in GENERIC_TITLES
        return bare && p.description.isNullOrBlank() && p.image == null
    }

    private val GENERIC_TITLES = setOf("instagram", "facebook", "x", "twitter", "threads", "tiktok", "linkedin", "reddit", "pinterest", "snapchat",
        "tiktok - make your day", "x. it's what's happening / x", "threads • log in", "youtube", "whatsapp")

    fun resolve(base: String, href: String): String? = try {
        val u = URI(base).resolve(href.trim().replace(" ", "%20"))
        if (u.scheme == "https" || u.scheme == "http") u.toString() else null
    } catch (_: Exception) { null }

    /** Decodes the HTML entities used in tags and squeezes whitespace. */
    fun clean(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi in (i + 2)..(i + 10)) {
                    val ent = s.substring(i + 1, semi)
                    val v: String? = when {
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { if (Character.isValidCodePoint(it)) String(Character.toChars(it)) else null }
                        else -> ENTITIES[ent.lowercase()]
                    }
                    if (v != null) { out.append(v); i = semi + 1; continue }
                }
            }
            out.append(c); i++
        }
        return out.toString().replace(Regex("""<[^>]{0,200}>"""), " ").replace(Regex("""\s+"""), " ").trim()
    }

    private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "#39" to "'",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "middot" to "·", "bull" to "•")
}
