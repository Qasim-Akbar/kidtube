package com.bbro.kidtube.data.repo

import androidx.room.withTransaction
import com.bbro.kidtube.core.Clock
import com.bbro.kidtube.core.FamilyTimeZone
import com.bbro.kidtube.core.SessionStore
import com.bbro.kidtube.core.today
import com.bbro.kidtube.data.KidTubeDatabase
import com.bbro.kidtube.data.entity.VideoEntity
import com.bbro.kidtube.engine.FeedItem
import com.bbro.kidtube.engine.FeedResult
import com.bbro.kidtube.engine.PaceType
import com.bbro.kidtube.engine.Projection
import com.bbro.kidtube.engine.buildFeed
import com.bbro.kidtube.engine.project
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One item the kid may watch now: the engine's [FeedItem] plus what the player needs. */
data class FeedEntry(
    val item: FeedItem,
    val video: VideoEntity,
    /** The plan item's own threshold (a view counts at >= this fraction watched; SPEC section 7). */
    val watchThreshold: Double,
)

/** The computed answer for one child. */
sealed interface FeedState {
    /** No such child in the stored snapshot (not synced yet, or removed). */
    data object NoChild : FeedState

    /** The child exists but has no plan yet (the server's 404 "no plan"). Nothing is invented. */
    data class NoPlan(val today: LocalDate) : FeedState

    /** The plan uses something this app version does not understand (a newer server). */
    data class UnsupportedPlan(val today: LocalDate) : FeedState

    data class Ready(
        val childId: String,
        val today: LocalDate,
        val paceType: PaceType,
        val feed: FeedResult,
        /** [feed]'s items in order, joined with their video rows. */
        val entries: List<FeedEntry>,
        /** Active plan items whose video is `unavailable`: withheld from the kid and from the engine; show to the parent. */
        val unavailableItemIds: List<String>,
        val projection: Projection,
    ) : FeedState
}

/**
 * The child's feed and projection, computed offline by the Kotlin engine (package `engine`) exactly as the backend does for
 * `GET /children/{id}/feed` (`engine_input.py`): the stored snapshot (plan, items, videos, history) plus the local overlay of
 * events the snapshot does not count yet (`EventRepository.overlayEvents` semantics, SPEC section 9). A view recorded locally
 * therefore changes the feed at once, and is counted exactly once after it syncs.
 *
 * Video status: like the backend, only `unavailable` videos are withheld (listed in [FeedState.Ready.unavailableItemIds]).
 * `pending` videos stay in the feed (a Facebook video is `pending` for good, and a YouTube one while oEmbed was unreachable);
 * each entry carries the video, so the player can handle an unplayable embed (playback_error) instead of this layer
 * hiding the item.
 */
@Singleton
class FeedRepository @Inject constructor(
    private val db: KidTubeDatabase,
    private val clock: Clock,
    private val session: SessionStore,
) {
    /** One-shot computation for "now" (the injected [Clock] in the family timezone). */
    suspend fun current(childId: String, projectionDays: Int = DEFAULT_PROJECTION_DAYS): FeedState = load(childId, projectionDays)

    /**
     * Re-emits (only when the result changes) when ANY of the inputs change: snapshot rows, local events and their sync state,
     * or the child's local date (checked every [dayCheckInterval], and at once on the first collection).
     *
     * All Room inputs are read in ONE transaction per emission, so a pull that replaces the history while pruning the
     * acknowledged overlay events is never seen half-applied (that would count a view twice for an instant).
     */
    fun observe(
        childId: String,
        projectionDays: Int = DEFAULT_PROJECTION_DAYS,
        dayCheckInterval: Duration = 30.seconds,
    ): Flow<FeedState> {
        val roomChanges = db.invalidationTracker.createFlow(*WATCHED_TABLES).map { }
        val dayChanges = flow {
            var last: Pair<ZoneId, LocalDate>? = null
            while (true) {
                val zone = familyZone()
                val now = zone to clock.today(zone)
                if (now != last) {
                    last = now
                    emit(Unit)
                }
                delay(dayCheckInterval)
            }
        }
        return merge(roomChanges, dayChanges)
            .conflate()
            .map { load(childId, projectionDays) }
            .distinctUntilChanged()
    }

    private suspend fun load(childId: String, projectionDays: Int): FeedState {
        val zone = familyZone()
        val today = clock.today(zone)
        val rows = db.withTransaction {
            val child = db.childDao().get(childId) ?: return@withTransaction null
            Rows(
                child = child,
                plan = db.planDao().getForChild(childId),
                videos = db.videoDao().getAll(),
                historyViews = db.historyDao().views(childId),
                historySeconds = db.historyDao().seconds(childId),
                overlay = db.localEventDao().overlay(childId),
            )
        } ?: return FeedState.NoChild
        val plan = rows.plan ?: return FeedState.NoPlan(today)

        val input = EngineInputs.build(rows.child, plan, rows.videos, rows.historyViews, rows.historySeconds, rows.overlay, today)
            ?: return FeedState.UnsupportedPlan(today)

        val feed = buildFeed(input.plan, input.items, input.views, input.seconds, input.appSeconds, input.limits, today)
        val projection = project(input.plan, input.items, input.views, input.seconds, today, projectionDays)
        val entries = feed.items.map { FeedEntry(it, input.videosByItem.getValue(it.itemId), input.watchThresholdByItem.getValue(it.itemId)) }
        return FeedState.Ready(childId, today, input.plan.paceType, feed, entries, input.unavailableItemIds, projection)
    }

    private suspend fun familyZone(): ZoneId {
        val id = session.currentInfo().familyTimezone ?: return FamilyTimeZone.DEFAULT
        return runCatching { ZoneId.of(id) }.getOrDefault(FamilyTimeZone.DEFAULT)
    }

    private class Rows(
        val child: com.bbro.kidtube.data.entity.ChildEntity,
        val plan: com.bbro.kidtube.data.dao.PlanWithItems?,
        val videos: List<VideoEntity>,
        val historyViews: List<com.bbro.kidtube.data.entity.HistoryViewEntity>,
        val historySeconds: List<com.bbro.kidtube.data.entity.HistorySecondsEntity>,
        val overlay: List<com.bbro.kidtube.data.entity.LocalEventEntity>,
    )

    companion object {
        /** The engine always simulates 91 days; this only truncates what is returned. */
        const val DEFAULT_PROJECTION_DAYS = 31

        /** Every table [load] reads (local_event + sync_meta = the overlay rule). */
        private val WATCHED_TABLES = arrayOf(
            "child", "video", "plan", "plan_item", "history_view", "history_seconds", "local_event", "sync_meta",
        )
    }
}
