package com.bbro.kidtube.ui.parent

import androidx.lifecycle.ViewModel
import com.bbro.kidtube.sync.SyncScheduler
import com.bbro.kidtube.sync.SyncStatus
import com.bbro.kidtube.sync.SyncStatusProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Only the debug sync panel uses this for now. */
@HiltViewModel
class ParentHomeViewModel @Inject constructor(
    statusProvider: SyncStatusProvider,
    private val scheduler: SyncScheduler,
) : ViewModel() {
    val status: Flow<SyncStatus> = statusProvider.status

    fun syncNow() = scheduler.syncNow()
}
