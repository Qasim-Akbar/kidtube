package com.bbro.kidtube.player

import java.net.URI
import java.net.URLEncoder

/**
 * Facebook has no player API; a video is shown through Facebook's official video plugin page in a locked-down WebView.
 * [url] builds the one URL that may load, [isAllowedNavigation] is the navigation lock: everything else is cancelled.
 */
object FacebookPlayback {
    private const val HOST = "www.facebook.com"
    private const val PATH = "/plugins/video.php"

    /** The plugin URL for the stored Facebook video link [videoUrl]. */
    fun url(videoUrl: String): String {
        val href = URLEncoder.encode(videoUrl, "UTF-8").replace("+", "%20")
        return "https://$HOST$PATH?href=$href&show_text=false&autoplay=true&allowfullscreen=false"
    }

    /** Only `https://www.facebook.com/plugins/video.php` (any query) may load: no other host, scheme, path or credentials. */
    fun isAllowedNavigation(url: String?): Boolean {
        if (url == null) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" &&
            uri.host == HOST &&
            (uri.port == -1 || uri.port == 443) &&
            uri.userInfo == null &&
            uri.rawPath == PATH
    }
}
