package com.bbro.kidtube.ui.plans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bbro.kidtube.data.entity.ChildEntity
import com.bbro.kidtube.data.remote.EventType
import com.bbro.kidtube.data.remote.PaceType
import com.bbro.kidtube.data.remote.PlanWrite
import com.bbro.kidtube.data.repo.ChildAction
import com.bbro.kidtube.data.repo.ChildSetupRepository
import com.bbro.kidtube.data.repo.ChildrenRepository
import com.bbro.kidtube.data.repo.EventPayloads
import com.bbro.kidtube.data.repo.EventRepository
import com.bbro.kidtube.data.repo.FeedRepository
import com.bbro.kidtube.data.repo.FeedState
import com.bbro.kidtube.data.repo.PlanAction
import com.bbro.kidtube.data.repo.PlanEditRepository
import com.bbro.kidtube.data.repo.PlanFailure
import com.bbro.kidtube.data.repo.PlanFailureKind
import com.bbro.kidtube.data.repo.PlanRepository
import com.bbro.kidtube.data.repo.SavePart
import com.bbro.kidtube.data.repo.SettingsOutcome
import com.bbro.kidtube.data.repo.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class PlansUiState(
    val loaded: Boolean = false,
    val children: List<ChildEntity> = emptyList(),
    val selectedChildId: String? = null,
    /** Null while loading or when there are no children. */
    val model: PlanScreenModel? = null,
    /** A network action is running: buttons are disabled and a progress bar shows. */
    val busy: Boolean = false,
)

/** What the plan settings form sends. */
data class SettingsInput(
    val paceType: PaceType,
    val paceValue: Int,
    val activeWeekdays: Int,
    val maxExtraPerDay: Int,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val dailyAppMinutes: Int?,
)

/** What the form says after a save that did not fully work: exactly which part is saved and which is not. */
data class SettingsMessage(val outcome: SettingsOutcome)

/** One-off things the screen reacts to. */
sealed interface PlansEvent {
    data class ExtraTimeAdded(val minutes: Int) : PlansEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlansViewModel @Inject constructor(
    private val childrenRepo: ChildrenRepository,
    private val planRepo: PlanRepository,
    private val videoRepo: VideoRepository,
    private val feeds: FeedRepository,
    private val edits: PlanEditRepository,
    private val childSetup: ChildSetupRepository,
    private val events: EventRepository,
) : ViewModel() {
    private val selected = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)

    private val _error = MutableStateFlow<PlanFailure?>(null)

    /** The failure of the last list/dialog action (shown as a banner until dismissed or the next action starts). */
    val error: StateFlow<PlanFailure?> = _error

    private val _settingsMessage = MutableStateFlow<SettingsMessage?>(null)
    val settingsMessage: StateFlow<SettingsMessage?> = _settingsMessage

    private val eventChannel = Channel<PlansEvent>(Channel.BUFFERED)
    val uiEvents: Flow<PlansEvent> = eventChannel.receiveAsFlow()

    private val modelFlow: Flow<Pair<String?, PlanScreenModel?>> = combine(childrenRepo.observeChildren(), selected) { children, sel ->
        children to (children.firstOrNull { it.id == sel } ?: children.firstOrNull())
    }.flatMapLatest { (_, child) ->
        if (child == null) return@flatMapLatest flowOf(null to null)
        val projection = feeds.observe(child.id, projectionDays = PROJECTION_DAYS)
            .map { (it as? FeedState.Ready)?.projection }
            .onStart { emit(null) }
        combine(planRepo.observePlan(child.id), videoRepo.observeVideos(), projection) { plan, videos, proj ->
            child.id to PlansMapper.map(child, plan, videos, proj)
        }
    }

    val state: StateFlow<PlansUiState> = combine(childrenRepo.observeChildren(), modelFlow, busy) { children, (id, model), isBusy ->
        PlansUiState(loaded = true, children = children, selectedChildId = id, model = model, busy = isBusy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlansUiState())

    fun selectChild(id: String) {
        selected.value = id
        _error.value = null
    }

    fun clearError() {
        _error.value = null
    }

    fun clearSettingsMessage() {
        _settingsMessage.value = null
    }

    // ---- item actions (all need a connection) -------------------------------------------------------------------------

    fun addVideo(childId: String, videoId: String, onDone: () -> Unit = {}) =
        runAction(onDone) { edits.addItem(childId, videoId) }

    fun editItem(itemId: String, required: Boolean, viewTarget: Int?, dailyCap: Int?, onDone: () -> Unit = {}) =
        runAction(onDone) { edits.editItem(itemId, required, viewTarget, dailyCap) }

    fun remove(itemId: String, onDone: () -> Unit = {}) = runAction(onDone) { edits.removeItem(itemId) }

    fun move(childId: String, itemId: String, up: Boolean) = runAction { edits.move(childId, itemId, up) }

    /** "Create default plan" for a child with none (the existing action). */
    fun createDefaultPlan(childId: String) {
        if (!begin()) return
        viewModelScope.launch {
            when (val r = childSetup.createDefaultPlan(childId)) {
                is ChildAction.Success -> Unit
                is ChildAction.Failed -> _error.value = PlanFailure(
                    when (r.reason) {
                        com.bbro.kidtube.data.repo.ChildFailure.NO_CONNECTION -> PlanFailureKind.NO_CONNECTION
                        com.bbro.kidtube.data.repo.ChildFailure.SERVER_UNREACHABLE -> PlanFailureKind.SERVER_UNREACHABLE
                        com.bbro.kidtube.data.repo.ChildFailure.INVALID_INPUT -> PlanFailureKind.INVALID
                        com.bbro.kidtube.data.repo.ChildFailure.RATE_LIMITED -> PlanFailureKind.RATE_LIMITED
                        com.bbro.kidtube.data.repo.ChildFailure.UNKNOWN -> PlanFailureKind.UNKNOWN
                    },
                )
            }
            busy.value = false
        }
    }

    // ---- plan settings: PUT plan, then PATCH the child's daily limit -----------------------------------------------------

    /**
     * Only the parts that differ from what is stored are sent, so a retry after a partial failure resends just the missing part.
     * [onSaved] runs when everything is stored; otherwise [settingsMessage] says what was and was not saved and the form stays open.
     */
    fun saveSettings(childId: String, input: SettingsInput, onSaved: () -> Unit) {
        if (!begin()) return
        _settingsMessage.value = null
        viewModelScope.launch {
            val storedPlan = planRepo.plan(childId)?.plan
            val storedLimit = childrenRepo.child(childId)?.dailyAppMinutes
            val write = PlanWrite(
                paceType = input.paceType, paceValue = input.paceValue, activeWeekdays = input.activeWeekdays,
                maxExtraPerDay = input.maxExtraPerDay, // ALWAYS sent: omitting it would reset the catch-up cap on the server
                startDate = input.startDate, endDate = input.endDate,
            )
            val planChanged = storedPlan == null || storedPlan.paceType != input.paceType || storedPlan.paceValue != input.paceValue ||
                storedPlan.activeWeekdays != input.activeWeekdays || storedPlan.maxExtraPerDay != input.maxExtraPerDay ||
                storedPlan.startDate != input.startDate || storedPlan.endDate != input.endDate
            val outcome = edits.saveSettings(childId, write, planChanged, input.dailyAppMinutes, storedLimit != input.dailyAppMinutes)
            busy.value = false
            if (outcome.allSaved) onSaved() else _settingsMessage.value = SettingsMessage(outcome)
        }
    }

    // ---- extra time ---------------------------------------------------------------------------------------------------

    /**
     * Grants [minutes] more app time today: a parent-only `time_extension` event for the child's local date, written locally (the
     * overlay applies it to the feed at once) and synced. Works offline.
     */
    fun giveExtraTime(childId: String, minutes: Int) {
        viewModelScope.launch {
            events.record(EventType.TIME_EXTENSION, childId, EventPayloads.timeExtension(minutes * 60.0))
            eventChannel.send(PlansEvent.ExtraTimeAdded(minutes))
        }
    }

    // ---- internals ----------------------------------------------------------------------------------------------------

    private fun begin(): Boolean {
        if (!busy.compareAndSet(expect = false, update = true)) return false
        _error.value = null
        return true
    }

    private fun runAction(onDone: () -> Unit = {}, block: suspend () -> PlanAction<Unit>) {
        if (!begin()) return
        viewModelScope.launch {
            when (val r = block()) {
                is PlanAction.Success -> onDone()
                is PlanAction.Failed -> _error.value = r.failure
            }
            busy.value = false
        }
    }
}

/** True when this outcome part is one the parent changed and that is not stored yet. */
val SavePart.notSaved: Boolean get() = this is SavePart.Failed || this is SavePart.NotTried
