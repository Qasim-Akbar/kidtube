package com.bbro.kidtube.player

import com.bbro.kidtube.core.Clock
import com.bbro.kidtube.core.today
import com.bbro.kidtube.data.remote.EventType
import com.bbro.kidtube.data.repo.EventPayloads
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// Pure tracking logic for the kid player (SPEC sections 7 and 8): no Android, no Room. All time comes from the injected Clock
// (monotonic for durations, wall clock for timestamps and the child's local date), events leave through an EventSink.

/**
 * How many seconds on screen make a Facebook view. Facebook gives no player API, so a view is counted by time while the video
 * is visible and the app is resumed. Will become a parent setting later (CLAUDE.md).
 */
const val FACEBOOK_VIEW_SECONDS = 180

/** A watch_progress event is written after this many seconds of playing (and on pause / leave). */
const val WATCH_FLUSH_SECONDS = 30

/** An app_session chunk is written at least this often, so a killed process loses at most this much (SPEC section 8). */
const val APP_SESSION_FLUSH_SECONDS = 60

/** One event ready to be written with `EventRepository.record`. [localDate] is the child's local day the event belongs to. */
data class TrackedEvent(
    val type: EventType,
    val childId: String,
    val payload: JsonObject,
    val localDate: LocalDate,
    val occurredAt: Instant,
)

fun interface EventSink {
    fun emit(event: TrackedEvent)
}

/** Commands the app sends to the player. Implemented by the YouTube adapter; tests use a fake. */
interface PlayerBridge {
    fun play()
    fun pause()
}

/** What the player reports back. [PlaybackTracker] is the one implementation that matters; the screen forwards the library's callbacks. */
interface PlayerEvents {
    fun onPlaying()
    fun onPaused()
    fun onBuffering()
    fun onEnded()
    fun onPosition(seconds: Double)
    fun onDuration(seconds: Double)
    fun onError(code: Int)
}

/** When a play session earns a view. */
sealed interface ViewRule {
    /** YouTube: position >= [fraction] of the duration (the plan item's watch threshold, default 0.9), or the video ends. */
    data class Threshold(val fraction: Double) : ViewRule

    /** Facebook: [seconds] of playing time (time on screen while visible and resumed). */
    data class TimeOnScreen(val seconds: Int) : ViewRule
}

/**
 * Tracks ONE play session of one video for one child (a session = one visit to the player screen).
 *
 *  - **watch_progress**: only time spent in the playing state (monotonic clock), so seeking, pausing and buffering add
 *    nothing. Whole seconds; the sub-second remainder is carried to the next flush (no gaps, no overlaps), and the final
 *    flush on [leave] rounds to the nearest second. Flushed after [flushSeconds] of playing and on pause, end, background and leave.
 *    The event's local_date is the day the seconds were played: a flush is forced when the day changes (checked on every
 *    [tick] and event, so the error is at most one tick).
 *  - **view_complete**: at most once per session, when the [ViewRule] is met (or the video ends, SPEC section 7). Never after an error.
 *  - **playback_error**: once per session, with the embed's error code.
 *
 * Thread-safe (the player callbacks and the tick may come from different threads).
 */
class PlaybackTracker(
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val childId: String,
    /** The SERVER video id (the backend validates watch_progress.video_id as a UUID), not the platform video id. */
    private val videoId: String,
    private val planItemId: String,
    private val rule: ViewRule,
    private val sink: EventSink,
    private val flushSeconds: Int = WATCH_FLUSH_SECONDS,
) : PlayerEvents {
    private var playing = false
    private var playingSince = 0L
    private var bankedMs = 0L
    private var emittedSeconds = 0L
    private var pendingDate: LocalDate = clock.today(zone())
    private var position = 0.0
    private var duration = 0.0
    private var closed = false

    var viewRecorded: Boolean = false
        @Synchronized get
        private set

    var errored: Boolean = false
        @Synchronized get
        private set

    @get:Synchronized
    val isPlaying: Boolean get() = playing

    /** Seconds played so far in this session (including the part not written yet). */
    @get:Synchronized
    val playedSeconds: Int get() = (totalMs() / 1000).toInt()

    // ---- player callbacks -----------------------------------------------------------------------------------------

    @Synchronized
    override fun onPlaying() = ifOpen {
        if (!playing) {
            playing = true
            playingSince = clock.elapsedRealtimeMillis()
        }
    }

    @Synchronized
    override fun onPaused() = ifOpen { stopPlaying(); flush(final = false) }

    /** Buffering is not playing: the clock stops, but nothing is written (it can flicker). */
    @Synchronized
    override fun onBuffering() = ifOpen { stopPlaying() }

    @Synchronized
    override fun onEnded() = ifOpen {
        stopPlaying()
        flush(final = false)
        // SPEC section 7: a view is recorded at the threshold "or when the video ends".
        if (rule is ViewRule.Threshold && !errored && totalMs() > 0) recordView(1.0)
    }

    @Synchronized
    override fun onPosition(seconds: Double) = ifOpen {
        position = seconds
        checkThreshold()
    }

    @Synchronized
    override fun onDuration(seconds: Double) = ifOpen {
        duration = seconds
        checkThreshold()
    }

    @Synchronized
    override fun onError(code: Int) = ifOpen {
        stopPlaying()
        flush(final = false)
        if (!errored) {
            errored = true
            sink.emit(event(EventType.PLAYBACK_ERROR, EventPayloads.playbackError(videoId, code)))
        }
    }

    // ---- driven by the screen ----------------------------------------------------------------------------------------

    /** The app left the foreground / the screen turned off: counting stops and what was played is written. */
    @Synchronized
    fun onBackground() = ifOpen { stopPlaying(); flush(final = false) }

    /** Call about once a second while the screen is open: day rollover, the 30 s flush, and the time-based view rule. */
    @Synchronized
    fun tick() = ifOpen {
        rollDate()
        if (playing && totalMs() - emittedSeconds * 1000 >= flushSeconds * 1000L) flush(final = false)
        if (rule is ViewRule.TimeOnScreen && !errored && !viewRecorded && totalMs() >= rule.seconds * 1000L) recordView(1.0)
    }

    /** Writes what is pending and closes the session (further callbacks are ignored). Safe to call twice. */
    @Synchronized
    fun leave() {
        if (closed) return
        stopPlaying()
        flush(final = true)
        closed = true
    }

    // ---- internals ---------------------------------------------------------------------------------------------------

    private inline fun ifOpen(block: () -> Unit) {
        if (closed) return
        rollDate()
        block()
    }

    private fun totalMs(): Long = bankedMs + if (playing) clock.elapsedRealtimeMillis() - playingSince else 0L

    private fun stopPlaying() {
        if (playing) {
            bankedMs += clock.elapsedRealtimeMillis() - playingSince
            playing = false
        }
    }

    /** The child's day changed: what was played before belongs to the old date, so write it under that date first. */
    private fun rollDate() {
        val today = clock.today(zone())
        if (today != pendingDate) {
            flush(final = false)
            pendingDate = today
        }
    }

    private fun flush(final: Boolean) {
        val total = totalMs()
        val target = if (final) (total + 500) / 1000 else total / 1000
        val delta = target - emittedSeconds
        if (delta > 0) {
            emittedSeconds = target
            sink.emit(event(EventType.WATCH_PROGRESS, EventPayloads.watchProgress(videoId, delta.toDouble()), pendingDate))
        }
    }

    private fun checkThreshold() {
        if (rule !is ViewRule.Threshold || viewRecorded || errored || duration <= 0.0) return
        if (position >= rule.fraction * duration) recordView((position / duration).coerceIn(0.0, 1.0))
    }

    private fun recordView(watchedPct: Double) {
        if (viewRecorded || errored) return
        viewRecorded = true
        sink.emit(event(EventType.VIEW_COMPLETE, EventPayloads.viewComplete(planItemId, watchedPct)))
    }

    private fun event(type: EventType, payload: JsonObject, date: LocalDate = clock.today(zone())) =
        TrackedEvent(type, childId, payload, date, clock.now().truncatedTo(ChronoUnit.MILLIS))
}

/**
 * Foreground ("app") time of ONE child's kid screens, written as `app_session` events (SPEC sections 8 and 11).
 *
 * The session is cut into chunks: a chunk is written at least every [flushSeconds] (so a killed process loses at most that
 * much), when the child's day changes, and when the session stops. Chunk boundaries sit on whole seconds from the start of
 * the session and each chunk starts where the previous one ended, so the server (which truncates every event to whole
 * seconds) loses nothing and nothing overlaps. A chunk that crosses the child's local midnight is SPLIT in two events, one per
 * local date, because the server attributes a whole app_session to a single `local_date` (SPEC section 9: everything is grouped
 * by the event's local date). Durations use the monotonic clock; the payload times are wall time at the start plus those durations.
 *
 * Not thread-safe by itself: the owner ([KidSessionManager]) serialises calls.
 */
class AppSessionTracker(
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val sink: EventSink,
    private val flushSeconds: Int = APP_SESSION_FLUSH_SECONDS,
) {
    private var childId: String? = null
    private var chunkStart: Instant = Instant.EPOCH
    private var chunkStartElapsed = 0L

    val isRunning: Boolean get() = childId != null

    fun start(childId: String) {
        if (isRunning) stop()
        this.childId = childId
        chunkStart = clock.now().truncatedTo(ChronoUnit.SECONDS)
        chunkStartElapsed = clock.elapsedRealtimeMillis()
    }

    /** Writes the last chunk (rounded to the nearest second) and stops. */
    fun stop() {
        if (!isRunning) return
        flush(final = true)
        childId = null
    }

    /** Flush when the interval has passed or the child's day changed. Call every few seconds while running. */
    fun tick() {
        if (!isRunning) return
        val elapsed = clock.elapsedRealtimeMillis() - chunkStartElapsed
        val z = zone()
        val crossedMidnight = chunkStart.plusMillis(elapsed).atZone(z).toLocalDate() != chunkStart.atZone(z).toLocalDate()
        if (elapsed >= flushSeconds * 1000L || crossedMidnight) flush(final = false)
    }

    /** Writes the whole seconds elapsed so far (the remainder stays in the running chunk). */
    fun flush(final: Boolean = false) {
        val child = childId ?: return
        val elapsed = clock.elapsedRealtimeMillis() - chunkStartElapsed
        val whole = if (final) (elapsed + 500) / 1000 else elapsed / 1000
        if (whole <= 0) return
        val end = chunkStart.plusSeconds(whole)
        val z = zone()
        var cursor = chunkStart
        while (cursor < end) {
            val date = cursor.atZone(z).toLocalDate()
            val midnight = date.plusDays(1).atStartOfDay(z).toInstant()
            val partEnd = if (midnight < end) midnight else end
            sink.emit(TrackedEvent(EventType.APP_SESSION, child, EventPayloads.appSession(cursor, partEnd), date, partEnd))
            cursor = partEnd
        }
        chunkStart = end
        chunkStartElapsed += whole * 1000
    }

    /** Whole seconds of the running chunk that have not been written yet (for live limit checks). */
    fun unflushedSeconds(): Int =
        if (!isRunning) 0 else ((clock.elapsedRealtimeMillis() - chunkStartElapsed) / 1000).toInt().coerceAtLeast(0)
}
