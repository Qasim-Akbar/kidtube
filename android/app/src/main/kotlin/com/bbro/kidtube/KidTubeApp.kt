package com.bbro.kidtube

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.bbro.kidtube.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class KidTubeApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncScheduler: SyncScheduler

    // On-demand WorkManager init with the Hilt factory (default initializer removed in the manifest).
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Follows the session: periodic + "sync now" work and the network-regained trigger run while the session can
        // sync, and are cancelled while it is NeedsLogin / DeviceRevoked / Unauthenticated.
        syncScheduler.start()
        // Process came to the foreground (also fires once at app start): sync now.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                syncScheduler.syncNow()
            }
        })
    }
}
