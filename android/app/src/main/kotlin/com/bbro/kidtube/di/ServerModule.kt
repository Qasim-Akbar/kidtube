package com.bbro.kidtube.di

import com.bbro.kidtube.BuildConfig
import com.bbro.kidtube.core.ServerConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ServerModule {
    /** The default server address comes from BuildConfig (debug: Gradle property KIDTUBE_DEBUG_API_BASE_URL). */
    @Provides
    @Singleton
    fun serverConfig(): ServerConfig = ServerConfig(BuildConfig.API_BASE_URL, BuildConfig.ALLOW_CLEARTEXT_API)
}
