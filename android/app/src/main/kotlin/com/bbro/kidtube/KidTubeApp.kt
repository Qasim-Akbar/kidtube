package com.bbro.kidtube

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.bbro.kidtube.core.LanguageStore
import com.bbro.kidtube.core.LocaleApplier
import com.bbro.kidtube.player.KidSessionManager
import com.bbro.kidtube.share.ShareTargetManager
import com.bbro.kidtube.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class KidTubeApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncScheduler: SyncScheduler

    @Inject
    lateinit var languageStore: LanguageStore

    @Inject
    lateinit var localeApplier: LocaleApplier

    @Inject
    lateinit var shareTargetManager: ShareTargetManager

    @Inject
    lateinit var kidSessions: KidSessionManager

    // On-demand WorkManager init with the Hilt factory (default initializer removed in the manifest).
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Follows the session: periodic + "sync now" work and the network-regained trigger run while the session can
        // sync, and are cancelled while it is NeedsLogin / DeviceRevoked / Unauthenticated.
        syncScheduler.start()
        // DataStore holds the chosen language; make sure the running app agrees with it (AppCompat also restores it).
        MainScope().launch {
            languageStore.current()?.let { if (localeApplier.current() != it) localeApplier.apply(it) }
        }
        // "Share to KidTube" is listed on parent phones only.
        MainScope().launch { shareTargetManager.keepInSync() }
        // App time follows the process lifecycle, so moving between kid screens never ends the session.
        kidSessions.bindForeground(ProcessLifecycleOwner.get().lifecycle)
        // Process came to the foreground (also fires once at app start): sync now.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                syncScheduler.syncNow()
            }
        })
    }
}
