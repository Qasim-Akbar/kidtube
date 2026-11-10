package com.bbro.kidtube.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.bbro.kidtube.core.AndroidConnectivityChecker
import com.bbro.kidtube.core.AppCompatLocaleApplier
import com.bbro.kidtube.core.BuildDeviceNameSource
import com.bbro.kidtube.core.Clock
import com.bbro.kidtube.core.ConnectivityChecker
import com.bbro.kidtube.core.DeviceClock
import com.bbro.kidtube.core.DeviceNameSource
import com.bbro.kidtube.core.KeystoreCipher
import com.bbro.kidtube.core.LocaleApplier
import com.bbro.kidtube.core.SecretCipher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreModule {
    @Binds
    @Singleton
    abstract fun bindClock(impl: DeviceClock): Clock

    @Binds
    @Singleton
    abstract fun bindLocaleApplier(impl: AppCompatLocaleApplier): LocaleApplier

    @Binds
    abstract fun bindConnectivityChecker(impl: AndroidConnectivityChecker): ConnectivityChecker

    @Binds
    abstract fun bindDeviceNameSource(impl: BuildDeviceNameSource): DeviceNameSource
}

@Module
@InstallIn(SingletonComponent::class)
object SessionModule {
    @Provides
    @Singleton
    fun secretCipher(): SecretCipher = KeystoreCipher(SESSION_KEY_ALIAS)

    /** File `files/datastore/session.preferences_pb`; excluded from backup like all app files. */
    @Provides
    @Singleton
    fun sessionDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            context.preferencesDataStoreFile("session")
        }

    private const val SESSION_KEY_ALIAS = "kidtube_session_v1"
}
