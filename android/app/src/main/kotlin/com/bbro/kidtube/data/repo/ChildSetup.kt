package com.bbro.kidtube.data.repo

import com.bbro.kidtube.core.Clock
import com.bbro.kidtube.core.ConnectivityChecker
import com.bbro.kidtube.core.FamilyTimeZone
import com.bbro.kidtube.core.SessionStore
import com.bbro.kidtube.core.today
import com.bbro.kidtube.data.remote.ApiResult
import com.bbro.kidtube.data.remote.ChildCreate
import com.bbro.kidtube.data.remote.ChildPatch
import com.bbro.kidtube.data.remote.KidTubeRemote
import com.bbro.kidtube.data.remote.PaceType
import com.bbro.kidtube.data.remote.PatchField
import com.bbro.kidtube.data.remote.PlanWrite
import com.bbro.kidtube.sync.SyncTrigger
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** The plan every child gets when it is added (and what "Create default plan" creates). One place for the numbers. */
data class DefaultPlanSpec(
    val paceType: PaceType,
    val paceValue: Int,
    /** Bitmask, Monday = bit 0 .. Sunday = bit 6. */
    val activeWeekdays: Int,
    val maxExtraPerDay: Int?,
) {
    /** Starts on [startDate] (the child's local date) and never ends. */
    fun toWrite(startDate: LocalDate) = PlanWrite(
        paceType = paceType, paceValue = paceValue, activeWeekdays = activeWeekdays, maxExtraPerDay = maxExtraPerDay,
        startDate = startDate, endDate = null,
    )
}

/** 3 videos a day, every weekday, catch-up cap left to the server default, no end date. */
val DEFAULT_PLAN = DefaultPlanSpec(PaceType.VIDEOS_PER_DAY, paceValue = 3, activeWeekdays = 0b1111111, maxExtraPerDay = null)

/** Why a child action (add, rename, create plan) failed, in terms the UI can word. */
enum class ChildFailure { NO_CONNECTION, SERVER_UNREACHABLE, INVALID_INPUT, RATE_LIMITED, UNKNOWN }

sealed interface ChildAction<out T> {
    data class Success<T>(val value: T) : ChildAction<T>

    data class Failed(val reason: ChildFailure) : ChildAction<Nothing>
}

/** [planCreated] false = the child exists but the default plan could not be created (offer "Create default plan"). */
data class AddedChild(val childId: String, val planCreated: Boolean)

/**
 * Parent-only child management over the network. Every call needs a connection; nothing is queued. After a change the
 * snapshot is pulled so Room (the UI's source) shows it at once, then a normal sync is requested for waiting placements.
 */
@Singleton
class ChildSetupRepository @Inject constructor(
    private val remote: KidTubeRemote,
    private val snapshots: SnapshotRepository,
    private val session: SessionStore,
    private val clock: Clock,
    private val connectivity: ConnectivityChecker,
    private val syncTrigger: SyncTrigger,
) {
    /** Creates the child, then its default plan. A failed plan step still reports the child as added. */
    suspend fun addChild(name: String, avatar: String?): ChildAction<AddedChild> {
        val created = remote.createChild(ChildCreate(name = name.trim(), avatar = avatar))
        val child = (created as? ApiResult.Success)?.value ?: return fail(created)
        val planCreated = remote.putPlan(child.id, DEFAULT_PLAN.toWrite(today())) is ApiResult.Success
        refresh()
        return ChildAction.Success(AddedChild(child.id, planCreated))
    }

    /** "Create default plan" for a child that has none. PUT replaces, so repeating it is harmless. */
    suspend fun createDefaultPlan(childId: String): ChildAction<Unit> {
        val result = remote.putPlan(childId, DEFAULT_PLAN.toWrite(today()))
        if (result !is ApiResult.Success) return fail(result)
        refresh()
        return ChildAction.Success(Unit)
    }

    suspend fun renameChild(childId: String, name: String): ChildAction<Unit> {
        val result = remote.patchChild(childId, ChildPatch(name = PatchField.Value(name.trim())))
        if (result !is ApiResult.Success) return fail(result)
        refresh()
        return ChildAction.Success(Unit)
    }

    private suspend fun refresh() {
        snapshots.pull() // best effort: a failed pull only means the list updates with the next sync
        syncTrigger.syncNow()
    }

    private suspend fun today(): LocalDate {
        val zone = session.currentInfo().familyTimezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: FamilyTimeZone.DEFAULT
        return clock.today(zone)
    }

    private fun fail(result: ApiResult<*>): ChildAction.Failed = ChildAction.Failed(
        when (result) {
            is ApiResult.NetworkError -> if (connectivity.isOnline()) ChildFailure.SERVER_UNREACHABLE else ChildFailure.NO_CONNECTION
            is ApiResult.RateLimited -> ChildFailure.RATE_LIMITED
            is ApiResult.HttpError -> when (result.code) {
                422 -> ChildFailure.INVALID_INPUT
                0, 404, 502, 503, 504 -> ChildFailure.SERVER_UNREACHABLE
                else -> ChildFailure.UNKNOWN
            }
            is ApiResult.Success -> ChildFailure.UNKNOWN
        },
    )
}
