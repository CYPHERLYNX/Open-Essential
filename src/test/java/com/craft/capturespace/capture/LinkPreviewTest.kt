package com.craft.capturespace.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkPreviewTest {
    @Test fun findsInstagramPostsAndReels() {
        assertEquals("p" to "C1a2B3c4D5e", LinkPreview.instagramCode("https://www.instagram.com/p/C1a2B3c4D5e/?igsh=abc"))
        assertEquals("reel" to "C9x_y-Z0", LinkPreview.instagramCode("https://instagram.com/reel/C9x_y-Z0/"))
        assertEquals("p" to "C1a2B3c4D5e", LinkPreview.instagramCode("https://www.instagram.com/someone/p/C1a2B3c4D5e/"))
        assertNull(LinkPreview.instagramCode("https://www.instagram.com/someone/"))
        assertNull(LinkPreview.instagramCode("https://example.com/p/C1a2B3c4D5e/"))
    }

    @Test fun picksPublicOembedProviders() {
        assertEquals("X", LinkPreview.provider("https://x.com/user/status/123")?.site)
        assertEquals("X", LinkPreview.provider("https://mobile.twitter.com/user/status/123")?.site)
        assertEquals("TikTok", LinkPreview.provider("https://vm.tiktok.com/ZM123/")?.site)
        assertEquals("Reddit", LinkPreview.provider("https://old.reddit.com/r/a/comments/1/x/")?.site)
        assertEquals("Spotify", LinkPreview.provider("https://open.spotify.com/track/1")?.site)
        assertNull(LinkPreview.provider("https://en.wikipedia.org/wiki/Kochi"))
    }

    @Test fun recognisesSharedAiChats() {
        assertEquals("ChatGPT", LinkPreview.aiChat("https://chatgpt.com/share/6789-abcd"))
        assertEquals("Claude", LinkPreview.aiChat("https://claude.ai/share/1234"))
        assertEquals("Grok", LinkPreview.aiChat("https://grok.com/share/abc"))
        assertEquals("Grok", LinkPreview.aiChat("https://x.com/i/grok/share/abc"))
        assertEquals("Gemini", LinkPreview.aiChat("https://g.co/gemini/share/abc"))
        assertNull(LinkPreview.aiChat("https://chatgpt.com/c/123"))
    }

    @Test fun dropsGenericAiChatPageText() {
        val generic = com.craft.capturespace.data.Meta.Link("https://claude.ai/share/1", state = "ok", title = "Claude",
            description = "Shared via Claude, an AI assistant from Anthropic")
        val g = LinkPreview.aiChatPage(generic, "Claude")
        assertNull(g.title); assertNull(g.description)
        val gpt = LinkPreview.aiChatPage(generic.copy(url = "https://chatgpt.com/share/1", title = "Check out this chat",
            description = "Here's a chat someone thought you'd want to see."), "ChatGPT")
        assertNull(gpt.title); assertNull(gpt.description)
        val real = LinkPreview.aiChatPage(generic.copy(title = "ChatGPT - Trip plan for Kochi", description = null), "ChatGPT")
        assertEquals("Trip plan for Kochi", real.title)
    }
}
