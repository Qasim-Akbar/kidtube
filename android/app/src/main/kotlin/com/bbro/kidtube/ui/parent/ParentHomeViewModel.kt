package com.bbro.kidtube.ui.parent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bbro.kidtube.core.ActiveMode
import com.bbro.kidtube.core.SessionStore
import com.bbro.kidtube.data.entity.ChildEntity
import com.bbro.kidtube.data.repo.ChildrenRepository
import com.bbro.kidtube.data.repo.OutboxRepository
import com.bbro.kidtube.data.repo.PlanRepository
import com.bbro.kidtube.data.repo.VideoRepository
import com.bbro.kidtube.sync.SyncStatus
import com.bbro.kidtube.sync.SyncStatusProvider
import com.bbro.kidtube.sync.SyncTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Everything the Library tab draws. [items] is already filtered by [selectedChildId]. */
data class LibraryUiState(
    val loaded: Boolean = false,
    val children: List<ChildEntity> = emptyList(),
    val selectedChildId: String? = null,
    val items: List<LibraryItem> = emptyList(),
    /** Number of videos before the child filter: 0 means the library is really empty. */
    val totalItems: Int = 0,
    val refreshing: Boolean = false,
)

private data class LibraryData(val children: List<ChildEntity>, val items: List<LibraryItem>)

@HiltViewModel
class ParentHomeViewModel @Inject constructor(
    statusProvider: SyncStatusProvider,
    private val trigger: SyncTrigger,
    private val session: SessionStore,
    childrenRepo: ChildrenRepository,
    videoRepo: VideoRepository,
    planRepo: PlanRepository,
    outboxRepo: OutboxRepository,
) : ViewModel() {
    val status: Flow<SyncStatus> = statusProvider.status

    private val selectedChild = MutableStateFlow<String?>(null)
    private val refreshing = MutableStateFlow(false)

    private val data: Flow<LibraryData> = combine(
        childrenRepo.observeChildren(), videoRepo.observeVideos(), planRepo.observeAll(), outboxRepo.observeItems(),
    ) { children, videos, plans, outbox ->
        LibraryData(children, LibraryMapper.map(videos, plans, children, outbox))
    }

    val library: StateFlow<LibraryUiState> = combine(data, selectedChild, refreshing) { d, selected, isRefreshing ->
        val chosen = selected?.takeIf { id -> d.children.any { it.id == id } }
        LibraryUiState(
            loaded = true,
            children = d.children,
            selectedChildId = chosen,
            items = LibraryMapper.filter(d.items, chosen),
            totalItems = d.items.size,
            refreshing = isRefreshing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun selectChild(id: String?) {
        selectedChild.value = id
    }

    /** Pull-to-refresh: ask for a sync now and show the spinner until it has finished (or a safety timeout). */
    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        trigger.syncNow()
        viewModelScope.launch {
            delay(MIN_SPINNER_MS)
            withTimeoutOrNull(MAX_SPINNER_MS) { status.first { it.phase == SyncStatus.Phase.IDLE } }
            refreshing.value = false
        }
    }

    fun syncNow() = trigger.syncNow()

    /** Switch this parent phone into kid mode (persisted). [onDone] runs once it is stored. */
    fun enterKidMode(onDone: () -> Unit) {
        viewModelScope.launch { if (session.setActiveMode(ActiveMode.KID)) onDone() }
    }

    private companion object {
        const val MIN_SPINNER_MS = 600L
        const val MAX_SPINNER_MS = 20_000L
    }
}
