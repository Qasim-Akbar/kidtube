package com.bbro.kidtube.ui.kid

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.bbro.kidtube.player.FacebookPlayback
import com.bbro.kidtube.player.PlayerBridge
import com.bbro.kidtube.player.PlayerEvents
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * A frame that swallows every touch: the YouTube WebView inside never receives one, so a tap cannot open YouTube, "watch later"
 * or the channel. (The compose overlay alone would also work; this is the native belt to that brace.)
 */
private class TouchBlockingFrame(context: Context) : FrameLayout(context) {
    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean = true
}

/** The embed's error code. The library folds 101 and 150 into one case; both mean "not embeddable", reported as 101. */
internal fun youTubeErrorCode(error: PlayerConstants.PlayerError): Int = when (error) {
    PlayerConstants.PlayerError.INVALID_PARAMETER_IN_REQUEST -> 2
    PlayerConstants.PlayerError.HTML_5_PLAYER -> 5
    PlayerConstants.PlayerError.VIDEO_NOT_FOUND -> 100
    PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER -> 101
    PlayerConstants.PlayerError.REQUEST_MISSING_HTTP_REFERER -> 153
    PlayerConstants.PlayerError.UNKNOWN -> 0
}

private class YouTubeBridge(private val player: YouTubePlayer) : PlayerBridge {
    override fun play() = player.play()
    override fun pause() = player.pause()
}

/**
 * The official YouTube IFrame player (android-youtube-player) with no controls, related videos, fullscreen, annotations or
 * captions, autoplaying when ready. [events] receives the player's callbacks, [onBridge] its play/pause commands.
 */
@Composable
fun YouTubeSurface(
    videoId: String,
    events: PlayerEvents,
    onBridge: (PlayerBridge?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val holder = remember(videoId) { arrayOfNulls<YouTubePlayerView>(1) }
    DisposableEffect(videoId) {
        onDispose {
            onBridge(null)
            holder[0]?.release()
            holder[0] = null
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val view = YouTubePlayerView(ctx).apply { enableAutomaticInitialization = false }
            holder[0] = view
            val options = IFramePlayerOptions.Builder(ctx)
                .controls(0).rel(0).fullscreen(0).ivLoadPolicy(3).ccLoadPolicy(0).modestBranding(1)
                .build()
            view.initialize(
                object : AbstractYouTubePlayerListener() {
                    override fun onReady(youTubePlayer: YouTubePlayer) {
                        onBridge(YouTubeBridge(youTubePlayer))
                        youTubePlayer.loadVideo(videoId, 0f) // loads and plays: autoplay on open
                    }

                    override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                        when (state) {
                            PlayerConstants.PlayerState.PLAYING -> events.onPlaying()
                            PlayerConstants.PlayerState.PAUSED -> events.onPaused()
                            PlayerConstants.PlayerState.BUFFERING -> events.onBuffering()
                            PlayerConstants.PlayerState.ENDED -> events.onEnded()
                            else -> Unit
                        }
                    }

                    override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) = events.onPosition(second.toDouble())

                    override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) = events.onDuration(duration.toDouble())

                    override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) =
                        events.onError(youTubeErrorCode(error))
                },
                false, // handleNetworkEvents: no automatic reload on network changes; a failed load ends in the "cannot play" tile
                options,
            )
            TouchBlockingFrame(ctx).apply {
                addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        },
    )
}

/** The WebViewClient/WebChromeClient that implement the Facebook navigation lock. Separate from the composable so it is testable. */
internal class LockedFacebookClient : WebViewClient() {
    /** Called for every main-frame navigation (links, redirects, scripted): cancel anything but the plugin URL. */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !FacebookPlayback.isAllowedNavigation(request.url.toString())

    /** Belt and braces: a blocked main-frame request gets an empty answer instead of a network fetch. */
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        if (request.isForMainFrame && !FacebookPlayback.isAllowedNavigation(request.url.toString())) {
            WebResourceResponse("text/plain", "utf-8", null)
        } else {
            null
        }

    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean = true
}

internal class LockedFacebookChromeClient : WebChromeClient() {
    // No popups or new windows, no file pickers, no camera/mic/location prompts.
    override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false

    override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
        filePathCallback?.onReceiveValue(null)
        return true
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        request?.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) {
        callback?.invoke(origin, false, false)
    }
}

/** Applies the lock-down settings. JavaScript is on because the plugin cannot run without it; everything else is off. */
@SuppressLint("SetJavaScriptEnabled")
internal fun WebView.lockDownForFacebook() {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true // the plugin player needs it
        mediaPlaybackRequiresUserGesture = false // autoplay
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        setGeolocationEnabled(false)
        cacheMode = WebSettings.LOAD_DEFAULT
    }
    webViewClient = LockedFacebookClient()
    webChromeClient = LockedFacebookChromeClient()
    setDownloadListener { _, _, _, _, _ -> } // no downloads
}

/** Facebook's video plugin in the locked-down WebView. Touches stay on: the plugin's own play button is the only control there is. */
@Composable
fun FacebookSurface(pluginUrl: String, modifier: Modifier = Modifier) {
    val holder = remember(pluginUrl) { arrayOfNulls<WebView>(1) }
    DisposableEffect(pluginUrl) {
        onDispose {
            holder[0]?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            holder[0] = null
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                lockDownForFacebook()
                holder[0] = this
                loadUrl(pluginUrl)
            }
        },
    )
}
