package com.bbro.kidtube.di

import android.content.Context
import androidx.work.WorkManager
import com.bbro.kidtube.sync.AndroidNetworkMonitor
import com.bbro.kidtube.sync.NetworkMonitor
import com.bbro.kidtube.sync.SyncScheduler
import com.bbro.kidtube.sync.SyncTrigger
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncBindingsModule {
    @Binds
    abstract fun bindSyncTrigger(impl: SyncScheduler): SyncTrigger

    @Binds
    abstract fun bindNetworkMonitor(impl: AndroidNetworkMonitor): NetworkMonitor
}

@Module
@InstallIn(SingletonComponent::class)
object SyncProvidesModule {
    @Provides
    @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
}
