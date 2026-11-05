package com.bbro.kidtube.di

import com.bbro.kidtube.core.Clock
import com.bbro.kidtube.core.DeviceClock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreModule {
    @Binds
    @Singleton
    abstract fun bindClock(impl: DeviceClock): Clock
}
