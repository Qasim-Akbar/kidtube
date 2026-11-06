package com.bbro.kidtube.sync

import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bbro.kidtube.core.SessionState
import com.bbro.kidtube.core.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns every piece of sync work in WorkManager (all UNIQUE, so nothing stacks up):
 *  - [PERIODIC]: every 15 min on any connected network.
 *  - [NOW]: one-time, ExistingWorkPolicy.KEEP, expedited where the OS allows it. Used for app start/foreground, an
 *    outbox enqueue and network regained.
 *  - [DEBOUNCED]: one-time, 30 s delay, KEEP, NOT expedited (it has a delay). The first event schedules it; later events
 *    do not push it back, so a continuous viewing session still syncs about every 30 s.
 *  - [DELAYED]: one-time after a 429, delayed by Retry-After.
 * Everything is cancelled while the session is NeedsLogin / DeviceRevoked / Unauthenticated and restarted when it
 * changes, so a dead session never burns battery retrying.
 */
@Singleton
class SyncScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val session: SessionStore,
    private val network: NetworkMonitor,
) : SyncTrigger {
    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var active = false

    /** Starts following the session. Call once from Application.onCreate. */
    fun start(scope: CoroutineScope = ownScope): Job = scope.launch {
        session.sessionState
            .map { it.canSync() }
            .distinctUntilChanged()
            .collect { canSync -> if (canSync) activate() else deactivate() }
    }

    private fun SessionState.canSync(): Boolean = this is SessionState.ParentDevice || this == SessionState.KidDevice

    private fun activate() {
        active = true
        workManager.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(CONNECTED)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
        syncNow()
        network.register { syncNow() }
    }

    private fun deactivate() {
        active = false
        network.unregister()
        listOf(PERIODIC, NOW, DEBOUNCED, DELAYED).forEach { workManager.cancelUniqueWork(it) }
    }

    override fun syncNow() {
        if (!active) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(CONNECTED)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .apply {
                // Before Android 12 expedited work runs as a foreground service (notification + permission), which a
                // background sync does not justify: there it is an ordinary one-time request.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .build()
        workManager.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    override fun syncSoon() {
        if (!active) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(DEBOUNCE.toMillis(), TimeUnit.MILLISECONDS) // a delayed request cannot be expedited
            .setConstraints(CONNECTED)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(DEBOUNCED, ExistingWorkPolicy.KEEP, request)
    }

    /** After a 429: one more run after [retryAfterSeconds] (null = [DEFAULT_RETRY_AFTER_SECONDS]), clamped to 1 s..1 h. */
    fun scheduleAfterRateLimit(retryAfterSeconds: Long?) {
        if (!active) return
        val delay = (retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS).coerceIn(1, MAX_RETRY_AFTER_SECONDS)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delay, TimeUnit.SECONDS)
            .setConstraints(CONNECTED)
            .build()
        // APPEND_OR_REPLACE: this is also called from inside a DELAYED run, which must not cancel itself.
        workManager.enqueueUniqueWork(DELAYED, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object {
        const val PERIODIC = "kidtube-sync-periodic"
        const val NOW = "kidtube-sync-now"
        const val DEBOUNCED = "kidtube-sync-debounced"
        const val DELAYED = "kidtube-sync-delayed"

        val DEBOUNCE: Duration = Duration.ofSeconds(30)
        const val DEFAULT_RETRY_AFTER_SECONDS = 60L
        const val MAX_RETRY_AFTER_SECONDS = 3600L
        private const val BACKOFF_SECONDS = 30L

        private val CONNECTED: Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}
