package com.bbro.kidtube.player

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.bbro.kidtube.ui.kid.LockedFacebookChromeClient
import com.bbro.kidtube.ui.kid.LockedFacebookClient
import com.bbro.kidtube.ui.kid.lockDownForFacebook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The Facebook navigation lock: only the plugin URL may load; every other navigation is cancelled. */
@RunWith(RobolectricTestRunner::class)
class FacebookPlaybackTest {
    private val plugin = FacebookPlayback.url("https://www.facebook.com/watch/?v=1234567890123")

    @Test
    fun pluginUrlHasTheFixedHostPathAndTheEncodedVideoLink() {
        assertEquals(
            "https://www.facebook.com/plugins/video.php?href=https%3A%2F%2Fwww.facebook.com%2Fwatch%2F%3Fv%3D1234567890123" +
                "&show_text=false&autoplay=true&allowfullscreen=false",
            plugin,
        )
        assertTrue(FacebookPlayback.isAllowedNavigation(plugin))
    }

    @Test
    fun aLinkWithSpacesAndUnicodeStaysOnePluginUrl() {
        val url = FacebookPlayback.url("https://fb.watch/a b/é")
        assertTrue(FacebookPlayback.isAllowedNavigation(url))
        assertFalse(url.contains(' '))
    }

    @Test
    fun everyOtherNavigationIsRefused() {
        val refused = listOf(
            null, "", "not a url",
            "http://www.facebook.com/plugins/video.php?href=x", // not https
            "https://facebook.com/plugins/video.php?href=x", // other host
            "https://m.facebook.com/plugins/video.php?href=x",
            "https://web.facebook.com/plugins/video.php?href=x",
            "https://www.facebook.com.evil.example/plugins/video.php?href=x",
            "https://evil.example/https://www.facebook.com/plugins/video.php",
            "https://www.facebook.com@evil.example/plugins/video.php",
            "https://user:pw@www.facebook.com/plugins/video.php",
            "https://www.facebook.com:8443/plugins/video.php",
            "https://www.facebook.com/watch/?v=1", // the page itself
            "https://www.facebook.com/login.php",
            "https://www.facebook.com/plugins/video.php/../../login.php",
            "https://www.facebook.com/plugins/like.php",
            "https://www.facebook.com/",
            "https://www.youtube.com/watch?v=abc",
            "intent://www.facebook.com/#Intent;scheme=https;end",
            "fb://profile/1",
            "market://details?id=com.facebook.katana",
            "javascript:alert(1)",
            "file:///sdcard/x.html",
            "content://media/external/file/1",
            "about:blank",
            "data:text/html,hi",
        )
        for (url in refused) assertFalse("must be refused: $url", FacebookPlayback.isAllowedNavigation(url))
    }

    // ---- the WebView clients ----------------------------------------------------------------------------------------------

    private fun request(url: String, mainFrame: Boolean = true) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    @Test
    fun theWebViewClientCancelsEveryNavigationExceptThePlugin() {
        val view = WebView(RuntimeEnvironment.getApplication())
        val client = LockedFacebookClient()
        assertFalse(client.shouldOverrideUrlLoading(view, request(plugin))) // false = let it load
        for (url in listOf("https://www.facebook.com/watch/?v=1", "https://example.com/", "intent://x#Intent;end", "market://details?id=a")) {
            assertTrue("must be cancelled: $url", client.shouldOverrideUrlLoading(view, request(url)))
        }
    }

    @Test
    fun aBlockedMainFrameRequestGetsAnEmptyAnswerButSubResourcesPass() {
        val view = WebView(RuntimeEnvironment.getApplication())
        val client = LockedFacebookClient()
        assertNull(client.shouldInterceptRequest(view, request(plugin)))
        assertNull(client.shouldInterceptRequest(view, request("https://video.xx.fbcdn.net/v/video.mp4", mainFrame = false)))
        assertEquals(0, client.shouldInterceptRequest(view, request("https://evil.example/", mainFrame = true))!!.data?.available() ?: 0)
    }

    @Test
    fun theWebViewIsLockedDown() {
        val view = WebView(RuntimeEnvironment.getApplication())
        view.lockDownForFacebook()
        val s = view.settings
        assertTrue(s.javaScriptEnabled) // the plugin cannot run without it
        assertFalse(s.allowFileAccess)
        assertFalse(s.allowContentAccess)
        assertFalse(s.javaScriptCanOpenWindowsAutomatically)
        assertFalse(s.supportMultipleWindows())
        assertFalse(s.mediaPlaybackRequiresUserGesture) // autoplay
        assertTrue(view.webViewClient is LockedFacebookClient)
    }

    @Test
    fun popupsAreRefused() {
        val view = WebView(RuntimeEnvironment.getApplication())
        assertFalse(LockedFacebookChromeClient().onCreateWindow(view, false, true, null))
    }
}
