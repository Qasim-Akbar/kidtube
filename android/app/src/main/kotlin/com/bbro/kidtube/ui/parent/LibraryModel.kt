package com.bbro.kidtube.ui.parent

import com.bbro.kidtube.data.dao.PlanWithItems
import com.bbro.kidtube.data.entity.ChildEntity
import com.bbro.kidtube.data.entity.OutboxPlacementEntity
import com.bbro.kidtube.data.entity.OutboxVideoState
import com.bbro.kidtube.data.entity.PlacementState
import com.bbro.kidtube.data.entity.VideoEntity
import com.bbro.kidtube.data.remote.Platform
import com.bbro.kidtube.data.remote.PlanItemStatus
import com.bbro.kidtube.data.remote.VideoStatus
import com.bbro.kidtube.share.OutboxItem
import com.bbro.kidtube.share.SharePreset
import com.bbro.kidtube.share.SharedLinks
import com.bbro.kidtube.share.LinkCheck
import com.bbro.kidtube.sync.PlacementErrors
import com.bbro.kidtube.ui.components.BadgeStatus

/** What is shown under a row's badge when the row is not in a plain settled state. */
enum class LibraryNote { WAITING_TO_SEND, WAITING_FOR_PLAN, NOT_ADDED }

/**
 * Why a shared video (or one of its placements) did not reach a plan. [Server] carries the backend's own words
 * (e.g. a 422 detail); [Unknown] is a failure with no readable reason (e.g. only an HTTP status).
 */
sealed interface NotAddedReason {
    data object Unavailable : NotAddedReason
    data class Server(val text: String) : NotAddedReason
    data object Unknown : NotAddedReason
}

/** One child a video is for. [preset] is null for a plan item that matches none of the presets (a custom one). */
data class Assignment(val childId: String, val childName: String, val preset: SharePreset?)

/** A row of the library list: a video already on the server, or a link still in the local outbox. */
data class LibraryItem(
    /** Stable list key. */
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val status: BadgeStatus,
    val assignments: List<Assignment>,
    val note: LibraryNote? = null,
    /** True for links that only exist in the outbox so far. */
    val fromOutbox: Boolean = false,
    /** A Facebook video: kids cannot watch these for now (KIDS_FACEBOOK_ENABLED), the row says so. */
    val facebook: Boolean = false,
    /** Set together with [LibraryNote.NOT_ADDED]: why the plan placement was dropped. */
    val notAddedReason: NotAddedReason? = null,
    /** Outbox rows the parent can clear with "Remove" (only rows that failed for good; never pending ones). */
    val dismissibleOutboxIds: List<String> = emptyList(),
    /** True when "Remove" deletes the whole row (an outbox-only link that was rejected) rather than just the failed placements. */
    val removesRow: Boolean = false,
) {
    val canDismiss: Boolean get() = dismissibleOutboxIds.isNotEmpty()
    val libraryOnly: Boolean get() = assignments.isEmpty()
}

/** Pure mapping from the stored data to the library list (verified / pending / unavailable / outbox). */
object LibraryMapper {
    fun map(
        videos: List<VideoEntity>,
        plans: List<PlanWithItems>,
        children: List<ChildEntity>,
        outbox: List<OutboxItem>,
    ): List<LibraryItem> {
        val names = children.associate { it.id to it.name }
        val libraryIds = videos.map { it.id }.toSet()
        val plansByChild = plans.associateBy { it.plan.childId }

        val fromServer = videos.sortedByDescending { it.addedAt }.map { video ->
            val queued = outbox.filter { it.video.serverVideoId == video.id }
                .flatMap { item -> item.placements.filter { it.state == PlacementState.PENDING } }
            val planned = children.mapNotNull { child ->
                plansByChild[child.id]?.items
                    ?.firstOrNull { it.videoId == video.id && it.status != PlanItemStatus.DROPPED }
                    ?.let { Assignment(child.id, child.name, SharePreset.describe(it.required, it.viewTarget, it.dailyCap)) }
            }
            val failed = outbox.filter { it.video.serverVideoId == video.id }.flatMap { item ->
                item.placements.filter { it.state == PlacementState.FAILED }.map { item.video.id to it }
            }.filter { (_, p) -> p.childId == null || planned.none { it.childId == p.childId } }
            val waiting = queued.filter { q -> q.childId == null || planned.none { it.childId == q.childId } }
            LibraryItem(
                id = "video:${video.id}",
                title = video.title?.takeIf { it.isNotBlank() } ?: displayUrl(video.url),
                thumbnailUrl = video.thumbnailUrl,
                facebook = video.platform == Platform.FACEBOOK,
                status = badge(video.status),
                assignments = planned + waiting.flatMap { assignmentsFor(it, children, names) }
                    .filter { w -> planned.none { it.childId == w.childId } },
                note = when {
                    waiting.isEmpty() -> null
                    waiting.any { it.reason == PlacementErrors.REASON_NO_PLAN } -> LibraryNote.WAITING_FOR_PLAN
                    else -> LibraryNote.WAITING_TO_SEND
                } ?: if (failed.isNotEmpty()) LibraryNote.NOT_ADDED else null,
                notAddedReason = if (waiting.isEmpty() && failed.isNotEmpty()) {
                    reasonFor(failed.first().second.reason, videoUnavailable = video.status == VideoStatus.UNAVAILABLE)
                } else {
                    null
                },
                dismissibleOutboxIds = if (waiting.isEmpty()) failed.map { it.first }.distinct() else emptyList(),
            )
        }

        val onlyLocal = outbox
            .filter { item ->
                // Once the video is in the snapshot the server row above represents it.
                item.video.serverVideoId == null || item.video.serverVideoId !in libraryIds
            }
            .sortedByDescending { it.video.createdAt }
            .map { item ->
                val rejected = item.video.state == OutboxVideoState.REJECTED
                LibraryItem(
                    id = "outbox:${item.video.id}",
                    title = displayUrl(item.video.rawUrl),
                    thumbnailUrl = null,
                    status = if (rejected) BadgeStatus.UNAVAILABLE else BadgeStatus.PENDING,
                    assignments = item.placements.filter { it.state != PlacementState.FAILED }
                        .flatMap { assignmentsFor(it, children, names) }.distinctBy { it.childId },
                    note = if (rejected) LibraryNote.NOT_ADDED else LibraryNote.WAITING_TO_SEND,
                    notAddedReason = if (rejected) reasonFor(item.video.rejectReason, videoUnavailable = false) else null,
                    dismissibleOutboxIds = if (rejected) listOf(item.video.id) else emptyList(),
                    removesRow = rejected,
                    fromOutbox = true,
                    facebook = (SharedLinks.check(item.video.rawUrl) as? LinkCheck.Supported)?.platform == Platform.FACEBOOK,
                )
            }
        return onlyLocal + fromServer
    }

    /** The list for one child chip; `null` = every video ("All"). Library-only videos belong to no child. */
    fun filter(items: List<LibraryItem>, childId: String?): List<LibraryItem> =
        if (childId == null) items else items.filter { item -> item.assignments.any { it.childId == childId } }

    private fun assignmentsFor(p: OutboxPlacementEntity, children: List<ChildEntity>, names: Map<String, String>): List<Assignment> {
        val preset = SharePreset.describe(p.required, p.viewTarget, p.dailyCap)
        return if (p.childId == null) {
            children.map { Assignment(it.id, it.name, preset) }
        } else {
            listOf(Assignment(p.childId, names[p.childId] ?: return emptyList(), preset))
        }
    }

    /** Readable reason: the video is unavailable, the server's own words, or none (stored codes like http_422 are not readable). */
    internal fun reasonFor(stored: String?, videoUnavailable: Boolean): NotAddedReason {
        val text = stored?.trim().orEmpty()
        return when {
            videoUnavailable || text.contains("unavailable", ignoreCase = true) -> NotAddedReason.Unavailable
            text.isEmpty() || text == PlacementErrors.REASON_VIDEO_REJECTED || Regex("^http_\\d+$").matches(text) || text == "rejected" -> NotAddedReason.Unknown
            else -> NotAddedReason.Server(text)
        }
    }

    private fun badge(status: VideoStatus): BadgeStatus = when (status) {
        VideoStatus.VERIFIED -> BadgeStatus.VERIFIED
        VideoStatus.UNAVAILABLE -> BadgeStatus.UNAVAILABLE
        VideoStatus.PENDING, VideoStatus.UNKNOWN -> BadgeStatus.PENDING
    }

    private fun displayUrl(url: String): String = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
}
