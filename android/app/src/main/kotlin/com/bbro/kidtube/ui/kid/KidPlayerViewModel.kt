package com.bbro.kidtube.ui.kid

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bbro.kidtube.core.Clock
import com.bbro.kidtube.core.FamilyTimeZone
import com.bbro.kidtube.core.SessionStore
import com.bbro.kidtube.data.remote.Platform
import com.bbro.kidtube.data.remote.VideoStatus
import com.bbro.kidtube.data.repo.FeedRepository
import com.bbro.kidtube.data.repo.FeedState
import com.bbro.kidtube.data.repo.PlanRepository
import com.bbro.kidtube.data.repo.VideoRepository
import com.bbro.kidtube.engine.FeedStatus
import com.bbro.kidtube.player.FACEBOOK_VIEW_SECONDS
import com.bbro.kidtube.player.FacebookPlayback
import com.bbro.kidtube.player.KidEventWriter
import com.bbro.kidtube.player.KidSessionManager
import com.bbro.kidtube.player.PlaybackTracker
import com.bbro.kidtube.player.PlayerBridge
import com.bbro.kidtube.player.PlayerEvents
import com.bbro.kidtube.player.ViewRule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What to put on the video area. */
sealed interface PlayerSurface {
    data class YouTube(val videoId: String) : PlayerSurface
    data class Facebook(val pluginUrl: String) : PlayerSurface
}

sealed interface PlayerUiState {
    /** Looking the video up in Room (milliseconds): just the background. */
    data object Loading : PlayerUiState

    /** The plan item is gone (a pull removed it): the screen leaves at once. */
    data object Gone : PlayerUiState

    /** The video cannot play (embed error, unavailable, no network for too long): a friendly tile and a way back. */
    data class Error(val title: String?) : PlayerUiState

    data class Ready(
        val title: String?,
        val surface: PlayerSurface,
        val isPlaying: Boolean,
        /** 0..1 through the video; only YouTube reports it. */
        val progress: Float,
    ) : PlayerUiState {
        /** Our own Play/Pause button exists for YouTube only (the Facebook plugin cannot be controlled from outside). */
        val canTogglePlay: Boolean get() = surface is PlayerSurface.YouTube
    }
}

data class PlayerTiming(
    /** How often the view model ticks the tracker, checks the live limits and the load timeout. */
    val tickMs: Long = 1_000,
    /** A YouTube video that has shown no sign of life after this long ends in the "cannot play" tile (no playback_error: it is not the video's fault). */
    val loadTimeoutSeconds: Int = 30,
    /** How often the feed Flow checks for a new local day. */
    val feedCheckInterval: Duration = 30.seconds,
)

/**
 * One visit to the player for ([childId], [itemId]). It owns the [PlaybackTracker], turns the player's callbacks into state and
 * enforces the live limits:
 *  - app-time limit reached (recorded app time from the feed + the running session's unwritten seconds): the video stops, everything
 *    is written, and the screen leaves to the grid, which then shows "Time's up";
 *  - YouTube ends: leave to the grid (it shows the next videos or "done for today"); a view counted earlier in the video never cuts it short;
 *  - Facebook (counted by time, no end signal): as soon as the counted view makes the item unavailable today (daily cap, quota, target) leave.
 * [leave] (All videos, system back, limits) pauses, writes the pending events, waits until they are in Room and only then emits the exit,
 * so the grid evaluates the feed with everything included.
 */
@HiltViewModel
class KidPlayerViewModel internal constructor(
    savedStateHandle: SavedStateHandle,
    private val plans: PlanRepository,
    private val videos: VideoRepository,
    private val feeds: FeedRepository,
    private val session: SessionStore,
    private val clock: Clock,
    private val writer: KidEventWriter,
    private val sessions: KidSessionManager,
    private val timing: PlayerTiming,
) : ViewModel(), PlayerEvents {
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        plans: PlanRepository,
        videos: VideoRepository,
        feeds: FeedRepository,
        session: SessionStore,
        clock: Clock,
        writer: KidEventWriter,
        sessions: KidSessionManager,
    ) : this(savedStateHandle, plans, videos, feeds, session, clock, writer, sessions, PlayerTiming())

    val childId: String = checkNotNull(savedStateHandle[ARG_CHILD_ID]) { "missing $ARG_CHILD_ID" }
    private val itemId: String = checkNotNull(savedStateHandle[ARG_ITEM_ID]) { "missing $ARG_ITEM_ID" }

    private val _state = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val state: StateFlow<PlayerUiState> = _state

    private val exitChannel = Channel<Unit>(Channel.CONFLATED)

    /** Emits once when the screen should go back to the grid (after everything was flushed). */
    val exits: Flow<Unit> = exitChannel.receiveAsFlow()

    @Volatile private var tracker: PlaybackTracker? = null
    @Volatile private var bridge: PlayerBridge? = null
    @Volatile private var resumed = false
    @Volatile private var latestFeed: FeedState.Ready? = null
    @Volatile private var signOfLife = false
    private var duration = 0.0
    private var openedElapsed = 0L
    private val leaving = AtomicBoolean(false)

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            while (isActive) {
                delay(timing.tickMs)
                onTick()
            }
        }
        viewModelScope.launch {
            feeds.observe(childId, projectionDays = 0, dayCheckInterval = timing.feedCheckInterval).collect { onFeed(it) }
        }
    }

    private suspend fun load() {
        val zoneId = session.currentInfo().familyTimezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: FamilyTimeZone.DEFAULT
        val item = plans.plan(childId)?.items?.firstOrNull { it.id == itemId }
        val video = item?.let { videos.video(it.videoId) }
        if (item == null || video == null) {
            _state.value = PlayerUiState.Gone
            return
        }
        val surface: PlayerSurface
        val rule: ViewRule
        when {
            video.status == VideoStatus.UNAVAILABLE -> return fail(video.title)
            video.platform == Platform.YOUTUBE -> {
                surface = PlayerSurface.YouTube(video.platformVideoId)
                rule = ViewRule.Threshold(item.watchThreshold) // the plan item's threshold applies to YouTube only
            }
            video.platform == Platform.FACEBOOK -> {
                surface = PlayerSurface.Facebook(FacebookPlayback.url(video.url))
                rule = ViewRule.TimeOnScreen(FACEBOOK_VIEW_SECONDS)
            }
            else -> return fail(video.title)
        }
        tracker = PlaybackTracker(clock, { zoneId }, childId, video.id, item.id, rule, writer)
        openedElapsed = clock.elapsedRealtimeMillis()
        _state.value = PlayerUiState.Ready(video.title, surface, isPlaying = false, progress = 0f)
        if (surface is PlayerSurface.Facebook && resumed) startTimed()
    }

    // ---- screen / lifecycle ----------------------------------------------------------------------------------------

    fun attachBridge(bridge: PlayerBridge?) {
        this.bridge = bridge
    }

    /** The screen is resumed (foreground, screen on). A Facebook video counts time from now on; YouTube stays as it was. */
    fun onForeground() {
        resumed = true
        if ((_state.value as? PlayerUiState.Ready)?.surface is PlayerSurface.Facebook && !leaving.get()) startTimed()
    }

    /** The app went to the background or the screen turned off: pause, write what was played, stop counting. */
    fun onBackground() {
        resumed = false
        bridge?.pause()
        tracker?.onBackground()
        setPlaying(false)
    }

    fun onTogglePlay() {
        val s = _state.value as? PlayerUiState.Ready ?: return
        if (!s.canTogglePlay) return
        if (s.isPlaying) bridge?.pause() else bridge?.play()
    }

    /** All videos / system back / a limit: pause, write everything, wait until it is in Room, then ask the screen to leave. */
    fun leave() {
        if (!leaving.compareAndSet(false, true)) return
        viewModelScope.launch {
            bridge?.pause()
            tracker?.leave()
            sessions.flushNow()
            exitChannel.send(Unit)
        }
    }

    override fun onCleared() {
        tracker?.leave() // synchronous hand-over to the app-scoped writer; a no-op when leave() already ran
        super.onCleared()
    }

    // ---- player callbacks (PlayerEvents) -----------------------------------------------------------------------------

    override fun onPlaying() {
        val t = tracker ?: return
        signOfLife = true
        t.onPlaying()
        setPlaying(true)
    }

    override fun onPaused() {
        val t = tracker ?: return
        t.onPaused()
        setPlaying(false)
    }

    override fun onBuffering() {
        tracker?.onBuffering() // the clock stops; the button keeps its state so it does not flicker
    }

    override fun onEnded() {
        val t = tracker ?: return
        t.onEnded()
        setPlaying(false)
        leave()
    }

    override fun onPosition(seconds: Double) {
        val t = tracker ?: return
        signOfLife = true
        t.onPosition(seconds)
        if (duration > 0) {
            val p = (seconds / duration).toFloat().coerceIn(0f, 1f)
            _state.update { s -> if (s is PlayerUiState.Ready && kotlin.math.abs(s.progress - p) >= 0.002f) s.copy(progress = p) else s }
        }
    }

    override fun onDuration(seconds: Double) {
        val t = tracker ?: return
        signOfLife = true
        duration = seconds
        t.onDuration(seconds)
    }

    override fun onError(code: Int) {
        val t = tracker ?: return
        t.onError(code) // writes the playback_error event; no view can follow
        bridge?.pause()
        fail((_state.value as? PlayerUiState.Ready)?.title)
    }

    // ---- internals -------------------------------------------------------------------------------------------------------

    private fun startTimed() {
        tracker?.onPlaying()
        setPlaying(true)
    }

    private fun setPlaying(playing: Boolean) {
        _state.update { s -> if (s is PlayerUiState.Ready && s.isPlaying != playing) s.copy(isPlaying = playing) else s }
    }

    private fun fail(title: String?) {
        _state.value = PlayerUiState.Error(title)
    }

    private fun onTick() {
        val t = tracker ?: return
        t.tick()
        val s = _state.value
        if (s is PlayerUiState.Ready && s.surface is PlayerSurface.YouTube && !signOfLife &&
            clock.elapsedRealtimeMillis() - openedElapsed >= timing.loadTimeoutSeconds * 1000L
        ) {
            bridge?.pause()
            fail(s.title) // not the video's fault (probably no network): no playback_error
        }
        enforceAppTimeLimit()
    }

    private fun onFeed(state: FeedState) {
        val ready = state as? FeedState.Ready
        latestFeed = ready
        enforceAppTimeLimit()
        // Facebook counts by time and never "ends": once a counted view means the item is not offered any more, go back.
        val t = tracker
        val onFacebook = (_state.value as? PlayerUiState.Ready)?.surface is PlayerSurface.Facebook
        if (ready != null && t != null && onFacebook && t.viewRecorded &&
            (ready.feed.status != FeedStatus.OK || ready.entries.none { it.item.itemId == itemId })
        ) {
            leave()
        }
    }

    /** Recorded app time (feed) + the session's unwritten seconds >= the limit: stop and leave; the grid then shows "Time's up". */
    private fun enforceAppTimeLimit() {
        val feed = latestFeed ?: return
        val limit = feed.appTimeLimitSeconds ?: return
        if (feed.appTimeUsedSeconds + sessions.unflushedSeconds() >= limit) leave()
    }

    companion object {
        const val ARG_CHILD_ID = "childId"
        const val ARG_ITEM_ID = "itemId"
    }
}
