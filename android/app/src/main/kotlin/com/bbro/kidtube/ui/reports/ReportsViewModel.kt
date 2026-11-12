package com.bbro.kidtube.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bbro.kidtube.core.Clock
import com.bbro.kidtube.core.FamilyTimeZone
import com.bbro.kidtube.core.SessionStore
import com.bbro.kidtube.core.today
import com.bbro.kidtube.data.entity.ChildEntity
import com.bbro.kidtube.data.remote.ReportDto
import com.bbro.kidtube.data.repo.ChildrenRepository
import com.bbro.kidtube.data.repo.ReportFailureKind
import com.bbro.kidtube.data.repo.ReportKey
import com.bbro.kidtube.data.repo.ReportRange
import com.bbro.kidtube.data.repo.ReportRepository
import com.bbro.kidtube.data.repo.ReportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class ReportsUiState(
    val loaded: Boolean = false,
    val children: List<ChildEntity> = emptyList(),
    val selectedChildId: String? = null,
    val range: ReportRange = ReportRange.WEEK,
    /** A date inside the period shown; null until the first load has worked out the child's local today. */
    val anchor: LocalDate? = null,
    /** The child's local date when the period was last set; the limit for "next". */
    val today: LocalDate? = null,
    /** The report for the current child, range and anchor: the last good one (from memory) while refreshing, or after a failed refresh. */
    val report: ReportDto? = null,
    val refreshing: Boolean = false,
    val failure: ReportFailureKind? = null,
) {
    val canGoNext: Boolean get() = anchor != null && today != null && ReportPeriods.canGoNext(range, anchor, today)
}

/**
 * Reports tab state. Online only: every period change, child change, pull-to-refresh and retry asks the server; the last successful
 * report of each period is shown from [ReportRepository]'s memory cache while the new one loads. "Today" = the injected Clock in the
 * family timezone (the same zone every event's local_date uses), recomputed at each load.
 */
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val children: ChildrenRepository,
    private val reports: ReportRepository,
    private val session: SessionStore,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(ReportsUiState())
    val state: StateFlow<ReportsUiState> = _state

    private var job: Job? = null

    init {
        viewModelScope.launch { children.observeChildren().collect(::onChildren) }
    }

    private fun onChildren(list: List<ChildEntity>) {
        val before = _state.value.selectedChildId
        val selected = before?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
        _state.update { it.copy(loaded = true, children = list, selectedChildId = selected) }
        if (selected != before) {
            if (selected == null) {
                job?.cancel()
                _state.update { it.copy(report = null, refreshing = false, failure = null) }
            } else {
                load()
            }
        }
    }

    fun selectChild(id: String) {
        if (id == _state.value.selectedChildId) return
        _state.update { it.copy(selectedChildId = id) }
        load()
    }

    fun selectRange(range: ReportRange) {
        if (range == _state.value.range) return
        _state.update { it.copy(range = range) }
        load()
    }

    fun previous() = move(-1)

    fun next() {
        if (_state.value.canGoNext) move(1)
    }

    /** Pull-to-refresh and the retry button. */
    fun refresh() = load()

    private fun move(steps: Int) {
        val s = _state.value
        val anchor = s.anchor ?: return
        val today = s.today ?: return
        _state.update { it.copy(anchor = ReportPeriods.shift(s.range, anchor, steps, today)) }
        load()
    }

    private fun load() {
        job?.cancel()
        val childId = _state.value.selectedChildId ?: return
        job = viewModelScope.launch {
            val today = clock.today(zone())
            val s = _state.value
            val anchor = minOf(s.anchor ?: today, today)
            val key = ReportKey(childId, s.range, anchor)
            _state.update { it.copy(today = today, anchor = anchor, report = reports.cached(key), refreshing = true, failure = null) }
            val result = reports.fetch(key)
            _state.update { current ->
                if (current.currentKey() != key) return@update current // the parent already moved on
                when (result) {
                    is ReportResult.Loaded -> current.copy(report = result.report, refreshing = false, failure = null)
                    is ReportResult.Failed -> current.copy(refreshing = false, failure = result.kind)
                }
            }
        }
    }

    private fun ReportsUiState.currentKey(): ReportKey? =
        if (selectedChildId != null && anchor != null) ReportKey(selectedChildId, range, anchor) else null

    private suspend fun zone(): ZoneId {
        val id = session.currentInfo().familyTimezone ?: return FamilyTimeZone.DEFAULT
        return runCatching { ZoneId.of(id) }.getOrDefault(FamilyTimeZone.DEFAULT)
    }
}
