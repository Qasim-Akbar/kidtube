package com.bbro.kidtube.sync

/** What data code calls to ask for a sync, without knowing about WorkManager. */
interface SyncTrigger {
    /** Something the user is waiting on changed (a shared link was saved): sync as soon as possible. */
    fun syncNow()

    /** Events were recorded: sync soon, batched (the first call schedules, later calls do not push it back). */
    fun syncSoon()

    object None : SyncTrigger {
        override fun syncNow() = Unit
        override fun syncSoon() = Unit
    }
}
