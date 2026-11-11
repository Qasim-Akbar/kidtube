package com.bbro.kidtube.ui.kid

import com.bbro.kidtube.data.entity.ChildEntity
import com.bbro.kidtube.data.repo.FeedState
import com.bbro.kidtube.engine.FeedStatus
import com.bbro.kidtube.engine.PaceType
import com.bbro.kidtube.ui.children.AvatarColor

/** The child as the kid screens show them. */
data class KidChild(val id: String, val name: String, val avatar: AvatarColor?)

fun ChildEntity.toKidChild() = KidChild(id, name, AvatarColor.fromKey(avatar))

/** One video tile. [title] is null when the video has none (the screens then use a generic description). */
data class KidTileModel(
    val itemId: String,
    val title: String?,
    val thumbnailUrl: String?,
    /** Picks the placeholder pastel so a video keeps its colour. */
    val seed: String,
    val viewedToday: Boolean,
)

data class TimeLeft(val secondsLeft: Int, val limitSeconds: Int)

enum class StatusKind { DONE, TIME_UP, DAY_OFF, NOTHING }

/** What the kid grid route shows. Pure data: every state is unit-tested without Android. */
sealed interface KidScreenState {
    /** Before the first answer (a few milliseconds from Room): just the background, no spinner. */
    data object Loading : KidScreenState

    /** The child is not in the stored snapshot any more: the route goes back to the picker. */
    data object Gone : KidScreenState

    data class Grid(
        val child: KidChild,
        /** Videos mode only: dots shown (the day's quota, capped) and how many are still allowed today. Null = no dots. */
        val dotsTotal: Int?,
        val dotsLeft: Int,
        /** The big "First" tile: only when the feed is locked to a required item. */
        val first: KidTileModel?,
        /** The 2-column tiles; empty while the feed is locked to [first]. */
        val tiles: List<KidTileModel>,
        /** Only when the child has a daily app-time limit. */
        val timeLeft: TimeLeft?,
    ) : KidScreenState

    data class Status(val child: KidChild, val kind: StatusKind) : KidScreenState
}

object KidScreenModel {
    /** The most dots drawn, however large the quota is (a row of dots is a glance, not a count). */
    const val MAX_DOTS = 8

    fun map(child: ChildEntity?, state: FeedState): KidScreenState {
        if (child == null) return KidScreenState.Gone
        val kid = child.toKidChild()
        return when (state) {
            FeedState.NoChild -> KidScreenState.Gone
            is FeedState.NoPlan, is FeedState.UnsupportedPlan -> KidScreenState.Status(kid, StatusKind.NOTHING)
            is FeedState.Ready -> mapReady(kid, state)
        }
    }

    private fun mapReady(kid: KidChild, s: FeedState.Ready): KidScreenState = when (s.feed.status) {
        FeedStatus.DONE_TODAY, FeedStatus.ALL_DONE -> KidScreenState.Status(kid, StatusKind.DONE)
        FeedStatus.TIME_UP -> KidScreenState.Status(kid, StatusKind.TIME_UP)
        FeedStatus.DAY_OFF, FeedStatus.OUTSIDE_DATES -> KidScreenState.Status(kid, StatusKind.DAY_OFF)
        FeedStatus.EMPTY -> KidScreenState.Status(kid, StatusKind.NOTHING)
        FeedStatus.OK -> {
            if (s.entries.isEmpty()) {
                KidScreenState.Status(kid, StatusKind.DONE) // cannot happen (OK always offers something); never show an empty grid
            } else {
                val tiles = s.entries.map {
                    KidTileModel(it.item.itemId, it.video.title, it.video.thumbnailUrl, it.video.id, it.item.itemId in s.viewedTodayItemIds)
                }
                val locked = s.entries.first().item.locked
                val videosMode = s.paceType == PaceType.VIDEOS_PER_DAY
                val dotsTotal = if (videosMode) s.feed.quotaToday.coerceIn(0, MAX_DOTS).takeIf { it > 0 } else null
                KidScreenState.Grid(
                    child = kid,
                    dotsTotal = dotsTotal,
                    dotsLeft = (s.feed.remainingVideos ?: 0).coerceIn(0, dotsTotal ?: 0),
                    first = if (locked) tiles.first() else null,
                    tiles = if (locked) emptyList() else tiles,
                    timeLeft = s.appTimeLimitSeconds?.let { TimeLeft((it - s.appTimeUsedSeconds).coerceAtLeast(0), it) },
                )
            }
        }
    }
}
