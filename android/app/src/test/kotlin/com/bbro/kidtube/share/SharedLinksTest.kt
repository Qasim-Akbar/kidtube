package com.bbro.kidtube.share

import com.bbro.kidtube.data.remote.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedLinksTest {
    private fun supported(text: String) = SharedLinks.check(text) as LinkCheck.Supported

    // ---- URL extraction ----------------------------------------------------------------------------------------

    @Test
    fun extract_picksTheLinkOutOfAWhatsAppMessage() {
        val text = "Look at this one! https://youtu.be/dQw4w9WgXcQ?si=abc123 my kids love it :)"
        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=abc123", SharedLinks.extractFirstUrl(text))
    }

    @Test
    fun extract_takesTheFirstOfSeveralLinks() {
        val text = "first https://youtu.be/AAAAAAAAAAA then https://youtu.be/BBBBBBBBBBB"
        assertEquals("https://youtu.be/AAAAAAAAAAA", SharedLinks.extractFirstUrl(text))
    }

    @Test
    fun extract_dropsTrailingPunctuation() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ", SharedLinks.extractFirstUrl("(see https://youtu.be/dQw4w9WgXcQ)."))
        assertEquals("https://youtu.be/dQw4w9WgXcQ", SharedLinks.extractFirstUrl("https://youtu.be/dQw4w9WgXcQ, thanks!"))
    }

    @Test
    fun extract_handlesNewlinesAndUppercaseSchemes() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ", SharedLinks.extractFirstUrl("Salam\nHTTPS://youtu.be/dQw4w9WgXcQ\nShukriya"))
    }

    @Test
    fun extract_garbageGivesNull() {
        assertNull(SharedLinks.extractFirstUrl(null))
        assertNull(SharedLinks.extractFirstUrl(""))
        assertNull(SharedLinks.extractFirstUrl("   "))
        assertNull(SharedLinks.extractFirstUrl("no link in here at all"))
        assertNull(SharedLinks.extractFirstUrl("youtube.com/watch?v=dQw4w9WgXcQ")) // no scheme: not an http(s) URL
        assertNull(SharedLinks.extractFirstUrl("https://"))
        assertNull(SharedLinks.extractFirstUrl("ftp://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun check_withoutALink_isNoLink() {
        assertEquals(LinkCheck.NoLink, SharedLinks.check("just words"))
        assertEquals(LinkCheck.NoLink, SharedLinks.check(null))
    }

    // ---- YouTube ------------------------------------------------------------------------------------------------

    @Test
    fun youtube_allCommonShapesAreSupported_withTheSameKey() {
        val id = "dQw4w9WgXcQ"
        listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?v=$id&t=42s",
            "https://m.youtube.com/watch?v=$id",
            "https://youtu.be/$id",
            "https://youtu.be/$id?si=xyz",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/embed/$id",
            "https://www.youtube.com/live/$id",
            "http://youtube.com/watch?v=$id",
        ).forEach { link ->
            val result = supported("Watch $link now")
            assertEquals(link, Platform.YOUTUBE, result.platform)
            assertEquals(link, "youtube:$id", result.key)
        }
    }

    @Test
    fun youtube_theUrlIsKeptAsShared() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=xyz", supported("https://youtu.be/dQw4w9WgXcQ?si=xyz").url)
    }

    @Test
    fun youtube_musicAndOtherSubdomainsAreNotSupported() {
        listOf(
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://studio.youtube.com/video/dQw4w9WgXcQ",
            "https://gaming.youtube.com/watch?v=dQw4w9WgXcQ",
        ).forEach { assertTrue(it, SharedLinks.check(it) is LinkCheck.Unsupported) }
    }

    @Test
    fun youtube_pagesWithoutAVideoAreNotSupported() {
        listOf(
            "https://www.youtube.com/",
            "https://www.youtube.com/@somechannel",
            "https://www.youtube.com/playlist?list=PL1234567890A",
            "https://www.youtube.com/watch",
            "https://www.youtube.com/watch?v=short",
            "https://youtu.be/",
            "https://www.youtube.com/shorts/",
        ).forEach { assertTrue(it, SharedLinks.check(it) is LinkCheck.Unsupported) }
    }

    @Test
    fun lookAlikeHostsAreNotSupported() {
        listOf(
            "https://evilyoutube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ",
            "https://example.com/youtube.com/watch?v=dQw4w9WgXcQ",
            "https://notfacebook.com/watch?v=123",
            "https://facebook.com.evil.example/watch?v=123",
            "https://vimeo.com/123456",
        ).forEach { assertTrue(it, SharedLinks.check(it) is LinkCheck.Unsupported) }
    }

    // ---- Facebook -----------------------------------------------------------------------------------------------

    @Test
    fun facebook_supportedHosts() {
        listOf(
            "https://www.facebook.com/watch?v=1234567890",
            "https://facebook.com/watch/?v=1234567890",
            "https://m.facebook.com/watch?v=1234567890",
            "https://web.facebook.com/someone/videos/1234567890",
            "https://fb.watch/abcDEF123/",
            "https://fb.me/abcDEF123",
        ).forEach { link ->
            val result = supported("Funny $link")
            assertEquals(link, Platform.FACEBOOK, result.platform)
            assertTrue(link, result.key.startsWith("facebook:"))
        }
    }

    @Test
    fun facebook_sameVideoOnDifferentSubdomainsHasTheSameKey() {
        assertEquals(
            supported("https://www.facebook.com/watch?v=1234567890").key,
            supported("https://m.facebook.com/watch/?v=1234567890").key,
        )
    }

    @Test
    fun facebook_homePageIsNotSupported() {
        assertTrue(SharedLinks.check("https://www.facebook.com/") is LinkCheck.Unsupported)
        assertTrue(SharedLinks.check("https://fb.watch/") is LinkCheck.Unsupported)
    }

    // ---- keys of videos already in the library ------------------------------------------------------------------

    @Test
    fun keyOf_matchesWhatCheckProduces() {
        assertEquals(supported("https://youtu.be/dQw4w9WgXcQ").key, SharedLinks.keyOf(Platform.YOUTUBE, "dQw4w9WgXcQ", "whatever"))
        val fb = "https://www.facebook.com/watch?v=1234567890"
        assertEquals(supported(fb).key, SharedLinks.keyOf(Platform.FACEBOOK, "1234567890", fb))
        assertNull(SharedLinks.keyOf(Platform.UNKNOWN, "x", "https://example.com"))
    }
}
