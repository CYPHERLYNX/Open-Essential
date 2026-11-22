package com.craft.capturespace.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMetaTest {
    @Test fun readsOpenGraphTagsInAnyAttributeOrder() {
        val html = """<html><head><title>Fallback</title>
            <meta content="Rust 2.0 &amp; beyond" property="og:title">
            <meta property='og:description' content='A long read about the &quot;next&quot; version.'>
            <meta property="og:image" content="/img/cover.jpg"/>
            <meta property="og:site_name" content="The Blog">
            <meta name="author" content="Asha Rao">
            </head><body><meta property="og:title" content="ignored"></body></html>"""
        val p = PageMeta.parse(html, "https://example.com/posts/rust")
        assertEquals("Rust 2.0 & beyond", p.title)
        assertEquals("A long read about the \"next\" version.", p.description)
        assertEquals("https://example.com/img/cover.jpg", p.image)
        assertEquals("The Blog", p.site)
        assertEquals("Asha Rao", p.author)
    }

    @Test fun fallsBackToTitleTagAndDescription() {
        val p = PageMeta.parse("<head><title> Plain   page </title><meta name=\"description\" content=\"Hello\"></head>", "https://a.org/")
        assertEquals("Plain page", p.title); assertEquals("Hello", p.description); assertNull(p.image)
    }

    @Test fun loginWallsAreRecognised() {
        val bare = PageMeta.Page("Instagram", null, null, "Instagram", null, null)
        assertTrue(PageMeta.looksLikeLoginWall(bare, "https://www.instagram.com/p/abc/"))
        assertTrue(PageMeta.looksLikeLoginWall(PageMeta.Page("Profile", "x", null, null, null, null), "https://www.instagram.com/accounts/login/?next=%2Fp%2Fabc"))
        assertTrue(PageMeta.looksLikeLoginWall(PageMeta.Page("Log in or sign up to view", null, null, null, null, null), "https://site.com/x"))
        assertFalse(PageMeta.looksLikeLoginWall(PageMeta.Page("Reel by @chef: 10-minute pasta", "Quick pasta recipe", "https://cdn/x.jpg", "Instagram", null, "video"), "https://www.instagram.com/reel/abc/"))
        // A page about logging in is not a login wall when it has a description.
        assertFalse(PageMeta.looksLikeLoginWall(PageMeta.Page("How to fix sign in errors", "Steps to fix it", null, "Docs", null, null), "https://docs.example.com/signin-help"))
    }

    @Test fun rejectsNonWebImageUrls() {
        assertNull(PageMeta.resolve("https://a.com/", "javascript:alert(1)"))
        assertNull(PageMeta.resolve("https://a.com/", "file:///etc/hosts"))
        assertEquals("https://cdn.a.com/x.png", PageMeta.resolve("https://a.com/p", "//cdn.a.com/x.png"))
    }
}
