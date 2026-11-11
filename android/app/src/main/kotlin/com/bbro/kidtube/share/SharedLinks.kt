package com.bbro.kidtube.share

import com.bbro.kidtube.data.remote.Platform
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What a shared text turned out to be. Local, pattern-only: the server does the real verification later. */
sealed interface LinkCheck {
    /**
     * A YouTube or Facebook link we can save. [url] is the extracted link, [key] identifies the video for duplicate
     * detection (`youtube:<id>`, `facebook:<host/path>`); the same key is derived for videos already in the library.
     */
    data class Supported(val platform: Platform, val url: String, val key: String) : LinkCheck

    /** There was a link, but not one we support (other site, YouTube Music, a channel page, ...). */
    data class Unsupported(val url: String) : LinkCheck

    /** The shared text has no http(s) link at all. */
    data object NoLink : LinkCheck
}

object SharedLinks {
    private val URL_REGEX = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}>'”’。"
    private val YOUTUBE_ID = Regex("""[A-Za-z0-9_-]{11}""")

    // music.youtube.com is deliberately absent: it is not a video the kids can play in the embed.
    private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "www.youtu.be")
    private val FACEBOOK_SHORT_HOSTS = setOf("fb.watch", "fb.me")

    /** The first http(s) URL in [text] (WhatsApp messages carry extra words), without trailing punctuation. */
    fun extractFirstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = URL_REGEX.find(text) ?: return null
        val url = match.value.trimEnd { it in TRAILING_PUNCTUATION }.takeIf { it.length > "https://".length } ?: return null
        // the scheme is case-insensitive; keep the rest exactly as shared (paths and ids are case-sensitive)
        return url.substringBefore("://").lowercase() + "://" + url.substringAfter("://")
    }

    fun check(text: String?): LinkCheck {
        val url = extractFirstUrl(text) ?: return LinkCheck.NoLink
        val parsed = url.toHttpUrlOrNull() ?: return LinkCheck.Unsupported(url)
        val host = parsed.host.lowercase()
        return when {
            host in YOUTUBE_HOSTS -> youtubeId(parsed, host)?.let { LinkCheck.Supported(Platform.YOUTUBE, url, "youtube:$it") }
            host in FACEBOOK_SHORT_HOSTS || host == "facebook.com" || host.endsWith(".facebook.com") ->
                facebookKey(parsed, host)?.let { LinkCheck.Supported(Platform.FACEBOOK, url, it) }
            else -> null
        } ?: LinkCheck.Unsupported(url)
    }

    /** The duplicate key of a video that is already known (library row): same shape as [check] produces. */
    fun keyOf(platform: Platform, platformVideoId: String, url: String): String? = when (platform) {
        Platform.YOUTUBE -> "youtube:$platformVideoId"
        Platform.FACEBOOK -> (check(url) as? LinkCheck.Supported)?.key ?: "facebook:$platformVideoId"
        Platform.UNKNOWN -> null
    }

    private fun youtubeId(url: HttpUrl, host: String): String? {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val candidate = when {
            host.endsWith("youtu.be") -> segments.firstOrNull()
            segments.firstOrNull() == "watch" -> url.queryParameter("v")
            segments.firstOrNull() in setOf("shorts", "embed", "live", "v") -> segments.getOrNull(1)
            else -> null
        }
        return candidate?.takeIf { YOUTUBE_ID.matches(it) }
    }

    private fun facebookKey(url: HttpUrl, host: String): String? {
        val path = url.pathSegments.filter { it.isNotEmpty() }
        if (path.isEmpty()) return null
        val bare = host.removePrefix("www.").removePrefix("m.").removePrefix("web.").removePrefix("mbasic.")
        val video = if (path.first() == "watch") url.queryParameter("v")?.let { "?v=$it" }.orEmpty() else ""
        return "facebook:" + bare + "/" + path.joinToString("/").lowercase() + video
    }
}
