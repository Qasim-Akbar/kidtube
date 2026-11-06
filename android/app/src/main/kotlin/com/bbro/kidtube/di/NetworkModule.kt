package com.bbro.kidtube.di

import com.bbro.kidtube.BuildConfig
import com.bbro.kidtube.core.Clock
import com.bbro.kidtube.core.SessionStore
import com.bbro.kidtube.data.remote.ApiStack
import com.bbro.kidtube.data.remote.ApiWiring
import com.bbro.kidtube.data.remote.KidTubeJson
import com.bbro.kidtube.data.remote.KidTubeRemote
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun json(): Json = KidTubeJson.instance

    @Provides
    @Singleton
    fun apiStack(json: Json, store: SessionStore, clock: Clock): ApiStack =
        ApiWiring.create(BuildConfig.API_BASE_URL, json, store, clock) { it.addDebugInterceptors() }

    @Provides
    @Singleton
    fun kidTubeRemote(stack: ApiStack): KidTubeRemote = stack.remote
}
