package com.bbro.kidtube.data.repo

import com.bbro.kidtube.core.ConnectivityChecker
import com.bbro.kidtube.data.dao.PlanWithItems
import com.bbro.kidtube.data.entity.PlanItemEntity
import com.bbro.kidtube.data.remote.ApiResult
import com.bbro.kidtube.data.remote.ChildPatch
import com.bbro.kidtube.data.remote.KidTubeJson
import com.bbro.kidtube.data.remote.KidTubeRemote
import com.bbro.kidtube.data.remote.PatchField
import com.bbro.kidtube.data.remote.PlanItemCreate
import com.bbro.kidtube.data.remote.PlanItemPatch
import com.bbro.kidtube.data.remote.PlanItemStatus
import com.bbro.kidtube.data.remote.PlanWrite
import com.bbro.kidtube.sync.SyncTrigger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** Why a plan edit failed, in terms the UI can word. [PlanFailure.INVALID] carries the server's own message when it sent one. */
enum class PlanFailureKind { NO_CONNECTION, SERVER_UNREACHABLE, ALREADY_IN_PLAN, NOT_FOUND, INVALID, RATE_LIMITED, UNKNOWN }

data class PlanFailure(val kind: PlanFailureKind, val serverMessage: String? = null)

sealed interface PlanAction<out T> {
    data class Success<T>(val value: T) : PlanAction<T>

    data class Failed(val failure: PlanFailure) : PlanAction<Nothing>
}

/** One half of the two-call "plan settings" save. */
sealed interface SavePart {
    /** Nothing to send: the value already matches what is stored. */
    data object Unchanged : SavePart

    data object Saved : SavePart

    data class Failed(val failure: PlanFailure) : SavePart

    /** Not tried because the step before it failed. */
    data object NotTried : SavePart
}

/** [plan] = PUT /children/{id}/plan, [limit] = PATCH /children/{id} (the daily app-time limit, which lives on the child). */
data class SettingsOutcome(val plan: SavePart, val limit: SavePart) {
    val allSaved: Boolean get() = plan.ok && limit.ok
    private val SavePart.ok get() = this is SavePart.Saved || this is SavePart.Unchanged
}

/** Pure helpers for the queue order. */
object PlanOrder {
    /**
     * The full id order after moving [itemId] one step up or down among the ACTIVE items. Items that are not active (completed or
     * dropped, hidden in the list) keep their places: only the two active neighbours swap. Returns null when it cannot move.
     * The server demands EVERY item of the plan in a reorder, so the result always lists all of them.
     */
    fun moved(items: List<PlanItemEntity>, itemId: String, up: Boolean): List<String>? {
        val ids = items.map { it.id }.toMutableList()
        val from = ids.indexOf(itemId)
        if (from < 0 || items[from].status != PlanItemStatus.ACTIVE) return null
        var to = from
        do {
            to += if (up) -1 else 1
        } while (to in ids.indices && items[to].status != PlanItemStatus.ACTIVE)
        if (to !in ids.indices) return null
        ids[from] = items[to].id
        ids[to] = itemId
        return ids
    }
}

/**
 * Parent-only plan editing over the network. Nothing is queued: every call needs a connection, and after a change the snapshot is
 * pulled so Room (the UI's source) shows it at once, then a normal sync is requested.
 */
@Singleton
class PlanEditRepository @Inject constructor(
    private val remote: KidTubeRemote,
    private val snapshots: SnapshotRepository,
    private val plans: PlanRepository,
    private val connectivity: ConnectivityChecker,
    private val syncTrigger: SyncTrigger,
) {
    suspend fun addItem(childId: String, videoId: String, required: Boolean = false, viewTarget: Int? = 1, dailyCap: Int? = null): PlanAction<Unit> =
        done(remote.addPlanItem(childId, PlanItemCreate(videoId = videoId, required = required, viewTarget = viewTarget, dailyCap = dailyCap)))

    /** Sends all three fields: a null target or cap clears it on the server ("unlimited" / "no cap"). */
    suspend fun editItem(itemId: String, required: Boolean, viewTarget: Int?, dailyCap: Int?): PlanAction<Unit> = done(
        remote.patchPlanItem(
            itemId,
            PlanItemPatch(required = PatchField.Value(required), viewTarget = PatchField.Value(viewTarget), dailyCap = PatchField.Value(dailyCap)),
        ),
    )

    suspend fun removeItem(itemId: String): PlanAction<Unit> = done(remote.deletePlanItem(itemId))

    /** Swaps [itemId] with its active neighbour. The reorder request lists every item of the plan (hidden ones included). */
    suspend fun move(childId: String, itemId: String, up: Boolean): PlanAction<Unit> {
        val plan: PlanWithItems = plans.plan(childId) ?: return PlanAction.Failed(PlanFailure(PlanFailureKind.NOT_FOUND))
        val order = PlanOrder.moved(plan.items, itemId, up) ?: return PlanAction.Success(Unit)
        return done(remote.reorderPlan(childId, order))
    }

    /**
     * PUT the plan, then PATCH the child's daily limit, each only when it differs from what is stored ([planChanged], [limitChanged]).
     * [write] must carry the current `max_extra_per_day`: a PUT that omits it resets it to the server default. The limit is not
     * tried when the plan step failed, and the outcome says exactly which part was saved.
     */
    suspend fun saveSettings(
        childId: String,
        write: PlanWrite,
        planChanged: Boolean,
        dailyMinutes: Int?,
        limitChanged: Boolean,
    ): SettingsOutcome {
        var planPart: SavePart = SavePart.Unchanged
        if (planChanged) {
            val result = remote.putPlan(childId, write)
            planPart = if (result is ApiResult.Success) SavePart.Saved else SavePart.Failed(failureOf(result))
        }
        var limitPart: SavePart = SavePart.Unchanged
        if (limitChanged) {
            limitPart = if (planPart is SavePart.Failed) {
                SavePart.NotTried
            } else {
                val result = remote.patchChild(childId, ChildPatch(dailyAppMinutes = PatchField.Value(dailyMinutes)))
                if (result is ApiResult.Success) SavePart.Saved else SavePart.Failed(failureOf(result))
            }
        }
        if (planPart is SavePart.Saved || limitPart is SavePart.Saved) refresh()
        return SettingsOutcome(planPart, limitPart)
    }

    private suspend fun done(result: ApiResult<*>): PlanAction<Unit> {
        if (result !is ApiResult.Success) return PlanAction.Failed(failureOf(result))
        refresh()
        return PlanAction.Success(Unit)
    }

    private suspend fun refresh() {
        snapshots.pull() // best effort: a failed pull only means the list updates with the next sync
        syncTrigger.syncNow()
    }

    private fun failureOf(result: ApiResult<*>): PlanFailure = when (result) {
        is ApiResult.NetworkError ->
            PlanFailure(if (connectivity.isOnline()) PlanFailureKind.SERVER_UNREACHABLE else PlanFailureKind.NO_CONNECTION)
        is ApiResult.RateLimited -> PlanFailure(PlanFailureKind.RATE_LIMITED)
        is ApiResult.HttpError -> when (result.code) {
            409 -> PlanFailure(PlanFailureKind.ALREADY_IN_PLAN)
            404 -> PlanFailure(PlanFailureKind.NOT_FOUND)
            422 -> PlanFailure(PlanFailureKind.INVALID, validationMessage(result.message))
            0, 502, 503, 504 -> PlanFailure(PlanFailureKind.SERVER_UNREACHABLE)
            else -> PlanFailure(PlanFailureKind.UNKNOWN)
        }
        is ApiResult.Success -> PlanFailure(PlanFailureKind.UNKNOWN)
    }

    /** FastAPI sends either a plain string (our own 422s) or a list of `{loc, msg}` objects (schema validation): take the first message. */
    private fun validationMessage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val parsed = runCatching { KidTubeJson.instance.parseToJsonElement(raw) }.getOrNull()
        val first = (parsed as? JsonArray)?.firstOrNull() as? JsonObject ?: return raw.takeIf { parsed == null || parsed is kotlinx.serialization.json.JsonPrimitive }
        return runCatching { first["msg"]?.jsonPrimitive?.content }.getOrNull()
    }
}
