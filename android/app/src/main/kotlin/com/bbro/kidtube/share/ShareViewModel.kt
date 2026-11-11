package com.bbro.kidtube.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bbro.kidtube.core.ConnectivityChecker
import com.bbro.kidtube.core.DeviceRole
import com.bbro.kidtube.core.PinResult
import com.bbro.kidtube.core.PinStore
import com.bbro.kidtube.core.SessionStore
import com.bbro.kidtube.data.dao.PlanWithItems
import com.bbro.kidtube.data.entity.ChildEntity
import com.bbro.kidtube.data.entity.VideoEntity
import com.bbro.kidtube.data.remote.Platform
import com.bbro.kidtube.data.repo.ChildAction
import com.bbro.kidtube.data.repo.ChildSetupRepository
import com.bbro.kidtube.data.repo.ChildrenRepository
import com.bbro.kidtube.data.repo.OutboxRepository
import com.bbro.kidtube.data.repo.PlanRepository
import com.bbro.kidtube.data.repo.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SharePhase { LOADING, NOT_PARENT, UNSUPPORTED, NO_CHILDREN, FORM, SAVED }

/** The link being shared; [title] and [thumbnailUrl] are only known when the video is already in the library. */
data class ShareLink(val url: String, val key: String, val platform: Platform, val title: String?, val thumbnailUrl: String?)

sealed interface ShareNote {
    /** Every selected child already has this video (in their plan or waiting in the outbox). */
    data class AlreadySaved(val names: List<String>) : ShareNote

    /** "Library only", but the video is already in the library or the outbox. */
    data object AlreadyInLibrary : ShareNote
}

/** Shown when a selected child has no plan: offer to create the default one (the PIN was already checked). */
data class PlanOffer(val childNames: List<String>)

/** What the confirmation says after saving. */
data class SavedInfo(
    val libraryOnly: Boolean,
    /** Children that already had it and were left out. */
    val skippedNames: List<String> = emptyList(),
    /** Children whose placement is still waiting for a plan (no plan yet, or none could be created). */
    val waitingForPlanNames: List<String> = emptyList(),
)

data class ShareUiState(
    val phase: SharePhase = SharePhase.LOADING,
    val link: ShareLink? = null,
    val children: List<ChildEntity> = emptyList(),
    val selected: Set<String> = emptySet(),
    val preset: SharePreset = SharePreset.ONCE_A_DAY,
    val pin: String = "",
    val attemptsLeft: Int? = null,
    val lockedSeconds: Long = 0,
    val busy: Boolean = false,
    val offline: Boolean = false,
    val note: ShareNote? = null,
    val planOffer: PlanOffer? = null,
    val saved: SavedInfo? = null,
) {
    val allSelected: Boolean get() = children.isNotEmpty() && selected.containsAll(children.map { it.id })

    val selectedNames: List<String> get() = children.filter { it.id in selected }.map { it.name }

    /** What "Save" needs: a full PIN, and someone to save it for unless the preset is "Library only". */
    val canSave: Boolean
        get() = phase == SharePhase.FORM && !busy && pin.length == PinStore.PIN_LENGTH && lockedSeconds == 0L &&
            (!preset.addsPlanItem || selected.isNotEmpty())
}

private data class ShareData(
    val children: List<ChildEntity> = emptyList(),
    val videos: List<VideoEntity> = emptyList(),
    val plans: List<PlanWithItems> = emptyList(),
    val outbox: List<OutboxItem> = emptyList(),
)

private data class PendingSave(val targets: List<String>, val skippedNames: List<String>, val noPlan: List<String>)

@HiltViewModel
class ShareViewModel @Inject constructor(
    private val session: SessionStore,
    private val pinStore: PinStore,
    private val childrenRepo: ChildrenRepository,
    private val videoRepo: VideoRepository,
    private val planRepo: PlanRepository,
    private val outbox: OutboxRepository,
    private val childSetup: ChildSetupRepository,
    private val connectivity: ConnectivityChecker,
) : ViewModel() {
    private val _state = MutableStateFlow(ShareUiState())
    val state: StateFlow<ShareUiState> = _state

    private var started = false
    private var link: LinkCheck.Supported? = null
    private var data = ShareData()
    private var pending: PendingSave? = null

    /** Called once by the Activity with the shared text; repeated calls (rotation) are ignored. */
    fun start(sharedText: String?) {
        if (started) return
        started = true
        viewModelScope.launch {
            if (session.currentInfo().deviceRole != DeviceRole.PARENT) {
                _state.update { it.copy(phase = SharePhase.NOT_PARENT) }
                return@launch
            }
            when (val check = SharedLinks.check(sharedText)) {
                is LinkCheck.Supported -> link = check
                is LinkCheck.Unsupported, LinkCheck.NoLink -> {
                    _state.update { it.copy(phase = SharePhase.UNSUPPORTED) }
                    return@launch
                }
            }
            _state.update { it.copy(offline = !connectivity.isOnline()) }
            combine(childrenRepo.observeChildren(), videoRepo.observeVideos(), planRepo.observeAll(), outbox.observeItems()) { c, v, p, o ->
                ShareData(c, v, p, o)
            }.collect { onData(it) }
        }
    }

    private fun onData(latest: ShareData) {
        data = latest
        val supported = link ?: return
        val known = latest.videos.firstOrNull { SharedLinks.keyOf(it.platform, it.platformVideoId, it.url) == supported.key }
        _state.update { s ->
            val ids = latest.children.map { it.id }.toSet()
            // The only child is pre-selected the first time children appear; after that the parent's choice is kept.
            val firstTime = s.phase == SharePhase.LOADING || s.phase == SharePhase.NO_CHILDREN
            val selected = if (firstTime && ids.size == 1) ids else s.selected intersect ids
            val phase = when {
                s.phase == SharePhase.SAVED || s.phase == SharePhase.NOT_PARENT || s.phase == SharePhase.UNSUPPORTED -> s.phase
                latest.children.isEmpty() -> SharePhase.NO_CHILDREN
                else -> SharePhase.FORM
            }
            s.copy(
                phase = phase,
                children = latest.children,
                selected = selected,
                link = ShareLink(supported.url, supported.key, supported.platform, known?.title, known?.thumbnailUrl),
            )
        }
    }

    fun onToggleChild(id: String) = update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }

    fun onToggleAll() = update { it.copy(selected = if (it.allSelected) emptySet() else it.children.map { c -> c.id }.toSet()) }

    fun onPreset(preset: SharePreset) = update { it.copy(preset = preset) }

    fun onPinChange(value: String) {
        val s = _state.value
        if (s.busy) return
        val pin = value.filter(Char::isDigit).take(PinStore.PIN_LENGTH)
        _state.update { it.copy(pin = if (it.lockedSeconds > 0) "" else pin, note = null, planOffer = null) }
        if (s.lockedSeconds > 0) {
            viewModelScope.launch {
                val left = pinStore.lockRemainingSeconds()
                _state.update { it.copy(lockedSeconds = left, pin = if (left > 0) "" else pin) }
            }
        }
    }

    /** Edits that change what would be saved forget an earlier note or offer. */
    private fun update(change: (ShareUiState) -> ShareUiState) {
        if (_state.value.busy) return
        _state.update { change(it).copy(note = null, planOffer = null) }
    }

    /** "Save": check the PIN, then duplicates, then plans, then write to the outbox. */
    fun save() {
        val s = _state.value
        if (!s.canSave) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, note = null, offline = !connectivity.isOnline()) }
            when (val result = pinStore.verify(s.pin)) {
                PinResult.Success -> proceed(s)
                is PinResult.Wrong -> _state.update { it.copy(busy = false, pin = "", attemptsLeft = result.attemptsLeft) }
                is PinResult.Locked -> _state.update { it.copy(busy = false, pin = "", attemptsLeft = null, lockedSeconds = result.remainingSeconds) }
                PinResult.NotSet -> _state.update { it.copy(busy = false, pin = "") }
            }
        }
    }

    private suspend fun proceed(s: ShareUiState) {
        val supported = link ?: return
        val d = data
        _state.update { it.copy(attemptsLeft = null, lockedSeconds = 0) }
        if (!s.preset.addsPlanItem) {
            if (ShareDuplicates.inLibraryOrOutbox(supported.key, d.videos, d.outbox)) {
                _state.update { it.copy(busy = false, note = ShareNote.AlreadyInLibrary) }
                return
            }
            writeToOutbox(s.preset, emptyList(), SavedInfo(libraryOnly = true))
            return
        }
        val chosen = d.children.filter { it.id in s.selected }
        val have = ShareDuplicates.childrenWithVideo(supported.key, chosen.map { it.id }, d.videos, d.plans, d.outbox)
        val targets = chosen.filter { it.id !in have }
        val skipped = chosen.filter { it.id in have }.map { it.name }
        if (targets.isEmpty()) {
            _state.update { it.copy(busy = false, note = ShareNote.AlreadySaved(skipped)) }
            return
        }
        val withoutPlan = targets.filter { child -> d.plans.none { it.plan.childId == child.id } }
        if (withoutPlan.isNotEmpty()) {
            pending = PendingSave(targets.map { it.id }, skipped, withoutPlan.map { it.id })
            _state.update { it.copy(busy = false, planOffer = PlanOffer(withoutPlan.map { c -> c.name })) }
            return
        }
        writeToOutbox(s.preset, targets.map { it.id }, SavedInfo(libraryOnly = false, skippedNames = skipped))
    }

    /** The parent's answer to "Ali has no plan yet": create the default plan first, or save and let the video wait. */
    fun answerPlanOffer(createPlans: Boolean) {
        val p = pending ?: return
        val s = _state.value
        if (s.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, planOffer = null) }
            val stillWaiting = p.noPlan.toMutableList()
            if (createPlans) {
                p.noPlan.forEach { id ->
                    if (childSetup.createDefaultPlan(id) is ChildAction.Success) stillWaiting -= id
                }
            }
            val names = data.children.associate { it.id to it.name }
            pending = null
            writeToOutbox(
                s.preset, p.targets,
                SavedInfo(libraryOnly = false, skippedNames = p.skippedNames, waitingForPlanNames = stillWaiting.mapNotNull { names[it] }),
            )
        }
    }

    fun cancelPlanOffer() {
        pending = null
        _state.update { it.copy(planOffer = null) }
    }

    private suspend fun writeToOutbox(preset: SharePreset, childIds: List<String>, info: SavedInfo) {
        val supported = link ?: return
        outbox.enqueue(supported.url, SharePreset.placements(preset, childIds)) // also asks for a sync
        _state.update { it.copy(busy = false, phase = SharePhase.SAVED, saved = info, pin = "") }
    }
}
