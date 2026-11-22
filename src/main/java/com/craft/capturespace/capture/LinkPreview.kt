package com.craft.capturespace.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.craft.capturespace.App
import com.craft.capturespace.ai.Pipeline
import com.craft.capturespace.data.Meta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

/**
 * Reads the public preview of ONE link the person shared, only when they allowed link previews:
 * the page's own title, description and preview picture (Open Graph tags), or YouTube's public
 * oEmbed endpoint for YouTube links. Nothing else is sent: no cookies, no account, none of the
 * person's captures or notes. No login, paywall or privacy setting is bypassed; a page that needs
 * a login is reported as "Preview not available: needs login or is private".
 */
object LinkPreview {
    private const val TAG = "OE-link"
    private const val TIMEOUT_MS = 8_000
    private const val MAX_HTML = 768 * 1024
    private const val MAX_IMAGE = 4 * 1024 * 1024
    private const val MAX_REDIRECTS = 5

    private val urlRx = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

    fun firstUrl(text: String): String? = urlRx.find(text)?.value?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"')
        ?.takeIf { it.length > 10 && runCatching { URL(it).host }.getOrNull()?.contains('.') == true }

    /** Fetch the preview (on a background thread), save it, then analyse the capture. */
    fun fetchThenAnalyse(ctx: Context, id: Long) {
        val app = App.instance
        app.scope.launch(Dispatchers.IO) {
            val c = app.repo.card(id) ?: return@launch
            val l = Meta.link(c.meta) ?: return@launch
            app.repo.setMeta(id, Meta.of(l.copy(state = "fetching")))
            val r = try { fetch(ctx, l.url) } catch (e: Exception) {
                l.copy(state = "error", message = "Preview not available: the page couldn't be read.")
            }
            app.repo.setMeta(id, Meta.of(r.copy(fetchedAt = System.currentTimeMillis())))
            Log.i(TAG, "preview state=${r.state} image=${r.image != null}") // no URL or content in the log
            Pipeline.reanalyse(ctx.applicationContext, id)
        }
    }

    fun fetch(ctx: Context, url: String): Meta.Link {
        val base = Meta.Link(url)
        if (!online(ctx)) return base.copy(state = "offline", message = "Preview not available: you're offline. Tap Fetch preview to try again.")
        return try {
            when {
                isYouTube(url) -> youtube(ctx, base)
                aiChat(url) != null -> aiChatPage(page(ctx, base), aiChat(url)!!)
                // Instagram's own embed page for public posts and reels; a private or removed post has none.
                instagramCode(url) != null -> instagram(base) ?: page(ctx, base)
                // Facebook sends its post pages to a login page; its public "embedded post" page
                // shows a post only when the post itself is public.
                isFacebook(url) -> page(ctx, base).let { p -> if (p.ok) p else facebook(base) ?: p }
                // Sites with a public oEmbed endpoint (no key, no login): fall back to the page's tags.
                else -> provider(url)?.let { oembed(base, it) }?.takeIf { it.ok }?.let { o ->
                    // No picture from the endpoint (X): the page's own preview picture, if it has one.
                    if (o.image != null) o else o.copy(image = runCatching { page(ctx, base) }.getOrNull()?.takeIf { it.ok }?.image)
                } ?: page(ctx, base)
            }
        } catch (e: SocketTimeoutException) {
            base.copy(state = "timeout", message = "Preview not available: the site took too long to answer.")
        } catch (e: UnknownHostException) {
            base.copy(state = "offline", message = "Preview not available: the site couldn't be reached.")
        } catch (e: javax.net.ssl.SSLException) {
            base.copy(state = "blocked", message = "Preview not available: the site's secure connection couldn't be checked.")
        } catch (e: IOException) {
            base.copy(state = "error", message = "Preview not available: the page couldn't be read.")
        }
    }

    // ---------- YouTube: its public oEmbed endpoint (title, channel, thumbnail) ----------

    private fun isYouTube(url: String): Boolean {
        val h = runCatching { URL(url).host.lowercase() }.getOrDefault("")
        return h == "youtu.be" || h == "youtube.com" || h.endsWith(".youtube.com")
    }

    private fun youtube(ctx: Context, base: Meta.Link): Meta.Link {
        val c = open("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(base.url, "UTF-8"), "application/json")
        try {
            when (c.responseCode) {
                200 -> {}
                401, 403 -> return base.copy(state = "login", message = "Preview not available: needs login or is private.")
                404 -> return base.copy(state = "notfound", message = "Preview not available: the video is private, removed or unavailable.")
                else -> return base.copy(state = "error", message = "Preview not available: YouTube didn't return details (${c.responseCode}).")
            }
            val o = JSONObject(String(c.inputStream.readCapped(64 * 1024), Charsets.UTF_8))
            val thumb = o.optString("thumbnail_url").takeIf { it.startsWith("https://") }?.let { saveImage(it) }
            return base.copy(state = "ok", site = "YouTube", title = o.optString("title").takeIf { it.isNotBlank() },
                author = o.optString("author_name").takeIf { it.isNotBlank() }, kind = "video", image = thumb)
        } finally { c.disconnect() }
    }

    // ---------- public oEmbed endpoints (the sites' own way to show a post elsewhere) ----------

    internal data class Provider(val site: String, val kind: String, val endpoint: String)

    /** Only sites whose oEmbed endpoint is public: no key, no account. */
    internal fun provider(url: String): Provider? {
        val h = runCatching { URL(url).host.lowercase().removePrefix("www.").removePrefix("m.").removePrefix("mobile.") }.getOrDefault("")
        return when {
            h == "x.com" || h == "twitter.com" -> Provider("X", "post", "https://publish.twitter.com/oembed?omit_script=1&dnt=true&url=")
            h == "tiktok.com" || h.endsWith(".tiktok.com") -> Provider("TikTok", "video", "https://www.tiktok.com/oembed?url=")
            h == "reddit.com" || h.endsWith(".reddit.com") || h == "redd.it" -> Provider("Reddit", "post", "https://www.reddit.com/oembed?url=")
            h == "vimeo.com" || h.endsWith(".vimeo.com") -> Provider("Vimeo", "video", "https://vimeo.com/api/oembed.json?url=")
            h == "open.spotify.com" -> Provider("Spotify", "music", "https://open.spotify.com/oembed?url=")
            h == "soundcloud.com" || h == "on.soundcloud.com" -> Provider("SoundCloud", "music", "https://soundcloud.com/oembed?format=json&url=")
            h == "dailymotion.com" || h == "dai.ly" -> Provider("Dailymotion", "video", "https://www.dailymotion.com/services/oembed?format=json&url=")
            h == "flickr.com" || h == "flic.kr" -> Provider("Flickr", "photo", "https://www.flickr.com/services/oembed/?format=json&url=")
            else -> null
        }
    }

    private fun oembed(base: Meta.Link, p: Provider): Meta.Link? {
        // The endpoint may redirect once or twice (X does), only to another https address.
        var url = p.endpoint + URLEncoder.encode(base.url, "UTF-8")
        var c = open(url, "application/json")
        repeat(3) {
            if (c.responseCode !in 300..399) return@repeat
            val next = c.getHeaderField("Location")?.let { PageMeta.resolve(url, it) }?.takeIf { it.startsWith("https://", true) }
            c.disconnect()
            if (next == null) return null
            url = next; c = open(url, "application/json")
        }
        try {
            when (c.responseCode) {
                200 -> {}
                401, 403 -> return base.copy(state = "login", site = p.site, message = "Preview not available: needs login or is private.")
                404 -> return base.copy(state = "notfound", site = p.site, message = "Preview not available: the post is private, removed or unavailable.")
                else -> return null
            }
            val o = JSONObject(String(c.inputStream.readCapped(256 * 1024), Charsets.UTF_8))
            // X sends the post itself as HTML ("<blockquote><p>text</p>— name"): its text is the description.
            val text = o.optString("html").takeIf { p.site == "X" }?.let { h ->
                Regex("""(?s)<p[^>]*>(.*?)</p>""").find(h)?.groupValues?.get(1)?.let { PageMeta.clean(stripTags(it)) }
            }
            val title = o.optString("title").takeIf { it.isNotBlank() }?.let { PageMeta.clean(it) }
            val author = o.optString("author_name").takeIf { it.isNotBlank() }
            if (title == null && text == null) return null
            val thumb = o.optString("thumbnail_url").takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { saveImage(it) }
            return base.copy(state = "ok", site = p.site, title = title ?: author?.let { "Post by $it" }, description = text,
                author = author, kind = p.kind, image = thumb)
        } finally { c.disconnect() }
    }

    // ---------- Instagram: the public embed page of a post or reel ----------

    internal fun instagramCode(url: String): Pair<String, String>? {
        val u = runCatching { URL(url) }.getOrNull() ?: return null
        if (!u.host.lowercase().let { it == "instagram.com" || it.endsWith(".instagram.com") || it == "instagr.am" }) return null
        val m = Regex("""^/(?:[\w.]+/)?(p|reel|reels|tv)/([\w-]{5,})""").find(u.path) ?: return null
        return (if (m.groupValues[1] == "p") "p" else if (m.groupValues[1] == "tv") "tv" else "reel") to m.groupValues[2]
    }

    /** The post's picture, caption and account from Instagram's embed page; null when it has none (private or removed). */
    private fun instagram(base: Meta.Link): Meta.Link? {
        val (kind, code) = instagramCode(base.url) ?: return null
        val c = open("https://www.instagram.com/$kind/$code/embed/captioned/", "text/html")
        try {
            if (c.responseCode != 200) return null
            val html = String(c.inputStream.readCapped(MAX_HTML, truncate = true), Charsets.UTF_8)
            val img = Regex("""class="EmbeddedMediaImage"[^>]*?src="([^"]+)"""").find(html)?.groupValues?.get(1)
                ?: Regex("""<img[^>]+class="[^"]*EmbeddedMediaImage[^"]*"[^>]*>""").find(html)?.value?.let { Regex("""src="([^"]+)"""").find(it)?.groupValues?.get(1) }
            val user = Regex("""class="UsernameText"[^>]*>([^<]+)<""").find(html)?.groupValues?.get(1)?.trim()
            val caption = Regex("""(?s)class="Caption"[^>]*>(.*?)<div class="CaptionComments""").find(html)?.groupValues?.get(1)
                ?.let { PageMeta.clean(stripTags(it.replace(Regex("""(?s)<a[^>]+class="CaptionUsername"[^>]*>.*?</a>"""), ""))) }
                ?.takeIf { it.isNotBlank() }
            if (img == null && caption == null) return null
            val image = img?.let { saveImage(android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString()) }
            val what = if (kind == "p") "post" else "reel"
            return base.copy(state = "ok", site = "Instagram", kind = what,
                title = caption?.lineSequence()?.firstOrNull()?.take(80) ?: user?.let { "Instagram $what by $it" },
                description = caption?.take(900), author = user, image = image)
        } finally { c.disconnect() }
    }

    // ---------- shared AI chats (ChatGPT, Claude, Grok, Gemini) ----------

    /** The assistant's name for a public shared-chat link, or null. */
    internal fun aiChat(url: String): String? {
        val u = runCatching { URL(url) }.getOrNull() ?: return null
        val h = u.host.lowercase().removePrefix("www."); val p = u.path.lowercase()
        return when {
            (h == "chatgpt.com" || h == "chat.openai.com") && p.startsWith("/share/") -> "ChatGPT"
            h == "claude.ai" && p.startsWith("/share/") -> "Claude"
            (h == "grok.com" && p.startsWith("/share/")) || ((h == "x.com" || h == "twitter.com") && p.startsWith("/i/grok/share/")) -> "Grok"
            (h == "gemini.google.com" && (p.startsWith("/share/") || p.startsWith("/gem/"))) || (h == "g.co" && p.startsWith("/gemini/share/")) -> "Gemini"
            else -> null
        }
    }

    // What these pages say about every chat (seen 2026-11-20): not about this one.
    private val aiBoilerplate = Regex("""(?i)^(chatgpt|claude|grok|gemini)$|^shared (grok|chatgpt|claude|gemini) conversation$|^gemini\s*-\s*direct access|""" +
        """^shared via claude|^open in grok|^created with gemini|^chatgpt is your ai|^a conversational ai|^claude is anthropic|""" +
        """^check out this (chat|conversation)|^here'?s a (chat|conversation) someone""")

    /** Keeps what the shared page says about this chat (its title) and drops the site's generic text. */
    internal fun aiChatPage(p: Meta.Link, name: String): Meta.Link {
        if (!p.ok) return p.copy(site = name, kind = "AI chat",
            message = "Preview not available: $name didn't show this shared chat to other apps. Copy the chat's text and share that instead.")
        val title = p.title?.trim()?.removePrefix("‎")?.replace(Regex("""(?i)^(chatgpt|claude|grok|gemini)\s*[-–|:]\s*"""), "")?.trim()
            ?.takeIf { it.isNotEmpty() && !aiBoilerplate.containsMatchIn(it) }
        val desc = p.description?.trim()?.takeIf { it.isNotEmpty() && !aiBoilerplate.containsMatchIn(it) }
        // ChatGPT and Grok draw a picture of each shared chat (its first messages); Claude and
        // Gemini only show their logo, which says nothing about this chat.
        val img = p.image?.takeIf { title != null || desc != null || name == "ChatGPT" || name == "Grok" }
        if (img == null) p.image?.let { java.io.File(it).delete() }
        // No real title: left empty, so the notes (read from the picture) or "Shared … chat" name it.
        return p.copy(site = name, kind = "AI chat", title = title, description = desc, image = img, author = null)
    }

    // ---------- Facebook: the public embedded-post page ----------

    internal fun isFacebook(url: String): Boolean {
        val h = runCatching { URL(url).host.lowercase() }.getOrDefault("")
        return h == "facebook.com" || h.endsWith(".facebook.com") || h == "fb.watch" || h == "fb.me"
    }

    private fun facebook(base: Meta.Link): Meta.Link? {
        val c = open("https://www.facebook.com/plugins/post.php?href=" + URLEncoder.encode(base.url, "UTF-8"), "text/html")
        try {
            if (c.responseCode != 200) return null
            val html = String(c.inputStream.readCapped(MAX_HTML, truncate = true), Charsets.UTF_8)
            val text = Regex("""(?s)data-testid="post_message"[^>]*>(.*?)</div>\s*</div>""").find(html)?.groupValues?.get(1)
                ?.let { PageMeta.clean(stripTags(it)) }?.takeIf { it.isNotBlank() }
            val img = Regex("""<img[^>]+src="(https://scontent[^"]+)"""").findAll(html).map { it.groupValues[1] }
                .firstOrNull { !it.contains("/p50x50/") && !it.contains("s50x50") }
            val author = Regex("""(?s)<span class="_2_79[^"]*"[^>]*>\s*<a[^>]*>([^<]{2,80})</a>""").find(html)?.groupValues?.get(1)?.trim()
            if (text == null && img == null) return null
            val image = img?.let { saveImage(android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString()) }
            return base.copy(state = "ok", site = "Facebook", kind = "post", author = author,
                title = text?.lineSequence()?.firstOrNull()?.take(80) ?: author?.let { "Facebook post by $it" } ?: "Facebook post",
                description = text?.take(900), image = image, message = null)
        } finally { c.disconnect() }
    }

    private fun stripTags(s: String): String =
        android.text.Html.fromHtml(s.replace(Regex("""(?i)<br\s*/?>"""), "\n"), android.text.Html.FROM_HTML_MODE_LEGACY).toString()

    // ---------- any other public page: its own preview tags ----------

    private fun page(ctx: Context, base: Meta.Link): Meta.Link {
        var url = base.url
        repeat(MAX_REDIRECTS + 1) {
            if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return base.copy(state = "blocked", message = "Preview not available: the link points outside the web.")
            // Plain http pages are read over https only (the app allows no unencrypted traffic).
            val target = if (url.startsWith("http://", true)) "https://" + url.substring(7) else url
            val c = open(target, "text/html,application/xhtml+xml")
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return base.copy(state = "error", message = "Preview not available: the page redirected nowhere.")
                    url = PageMeta.resolve(target, loc) ?: return base.copy(state = "blocked", message = "Preview not available: the page redirected outside the web.")
                    if (PageMeta.looksLikeLoginWall(PageMeta.Page(null, null, null, null, null, null), url) &&
                        Regex("""(?i)/(login|signin|accounts/login|authwall|checkpoint)""").containsMatchIn(url)) {
                        return base.copy(state = "login", finalUrl = url, message = "Preview not available: needs login or is private.")
                    }
                    return@repeat
                }
                when (code) {
                    200, 203 -> {}
                    401 -> return base.copy(state = "login", message = "Preview not available: needs login or is private.")
                    403 -> return base.copy(state = "blocked", message = "Preview not available: the site didn't allow it (it may need a login or block previews).")
                    404, 410 -> return base.copy(state = "notfound", message = "Preview not available: the page wasn't found (it may be deleted or private).")
                    429 -> return base.copy(state = "blocked", message = "Preview not available: the site is limiting requests. Try again later.")
                    else -> return base.copy(state = "error", message = "Preview not available: the site answered with an error ($code).")
                }
                val type = c.contentType.orEmpty().lowercase()
                if (type.startsWith("image/")) {
                    val img = saveImage(target)
                    return base.copy(state = if (img != null) "ok" else "error", finalUrl = target, kind = "image", image = img,
                        message = if (img == null) "Preview not available: the picture couldn't be read." else null)
                }
                if (!type.contains("html") && type.isNotEmpty()) {
                    return base.copy(state = "error", finalUrl = target, message = "Preview not available: the link is a file (${type.substringBefore(';')}), not a web page.")
                }
                val bytes = c.inputStream.readCapped(MAX_HTML, truncate = true)
                val html = String(bytes, charset(type, bytes))
                val p = PageMeta.parse(html, target)
                if (PageMeta.looksLikeLoginWall(p, target)) return base.copy(state = "login", finalUrl = target, site = p.site, message = "Preview not available: needs login or is private.")
                if (p.title.isNullOrBlank() && p.description.isNullOrBlank()) return base.copy(state = "error", finalUrl = target, message = "Preview not available: the page doesn't publish a title or description.")
                val img = p.image?.let { saveImage(it) }
                return base.copy(state = "ok", finalUrl = target, site = p.site, title = p.title, description = p.description, author = p.author, kind = p.type, image = img)
            } finally { c.disconnect() }
        }
        return base.copy(state = "error", message = "Preview not available: too many redirects.")
    }

    // ---------- helpers ----------

    private fun open(url: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.useCaches = false
        // An honest, generic client name; no cookies or account are ever sent.
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) OpenEssential link preview")
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("Accept-Language", "en")
        c.setRequestProperty("Cookie", "")
        return c
    }

    /** Downloads the page's preview picture (an image only, capped), scaled down, into the app's storage. */
    private fun saveImage(src: String): String? = try {
        saveImageOnce(src, TIMEOUT_MS)
    } catch (_: SocketTimeoutException) {
        // Some sites draw the preview picture on the first request (seen: ChatGPT's shared-chat
        // picture): one more try with a longer limit.
        try { saveImageOnce(src, 20_000) } catch (_: Exception) { null }
    } catch (_: Exception) { null }

    private fun saveImageOnce(src: String, timeoutMs: Int): String? {
        val c = open(src.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://"), "image/*")
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        return try {
            if (c.responseCode != 200 || !c.contentType.orEmpty().startsWith("image/")) null
            else {
                val bytes = c.inputStream.readCapped(MAX_IMAGE)
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var sample = 1
                while (maxOf(o.outWidth, o.outHeight) / sample > 1600) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                bmp?.let {
                    val f = App.instance.repo.newFile("jpg")
                    f.outputStream().use { out -> it.compress(Bitmap.CompressFormat.JPEG, 88, out) }
                    it.recycle(); f.absolutePath
                }
            }
        } finally { c.disconnect() }
    }

    private fun charset(contentType: String, bytes: ByteArray): java.nio.charset.Charset {
        val fromHeader = Regex("""charset=([\w-]+)""").find(contentType)?.groupValues?.get(1)
        val fromMeta = Regex("""<meta[^>]+charset=["']?([\w-]+)""", RegexOption.IGNORE_CASE).find(String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1))?.groupValues?.get(1)
        return listOfNotNull(fromHeader, fromMeta).firstNotNullOfOrNull { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
    }

    private fun InputStream.readCapped(max: Int, truncate: Boolean = false): ByteArray = use { s ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            if (out.size() + n > max) {
                if (truncate) { out.write(buf, 0, max - out.size()); break }
                throw IOException("too large")
            }
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }

    private fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
