package com.bbro.kidtube.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Runs one [SyncEngine] cycle. All scheduling policy lives in [SyncScheduler]. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (val outcome = engine.runCycle()) {
        SyncOutcome.Success, is SyncOutcome.PartialFailure -> Result.success()
        // Offline / 5xx: WorkManager's exponential backoff.
        SyncOutcome.RetryLater -> Result.retry()
        // The delay comes from Retry-After, which backoff cannot express: schedule it ourselves and END this run with
        // success. Returning retry() as well would run the sync twice.
        is SyncOutcome.RateLimited -> {
            scheduler.scheduleAfterRateLimit(outcome.retryAfterSeconds)
            Result.success()
        }
        // Dead session: stop. The scheduler cancels everything while the session is dead and restarts when it changes.
        SyncOutcome.NeedsLogin, SyncOutcome.DeviceRevoked -> Result.success()
    }
}
