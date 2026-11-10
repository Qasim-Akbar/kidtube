package com.bbro.kidtube.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The language the user picked, persisted in the (non-secret part of the) app DataStore. Null = never chosen, so the
 * app follows the system language. Applying it to the running app is [LocaleApplier]'s job.
 */
@Singleton
class LanguageStore @Inject constructor(private val dataStore: DataStore<Preferences>) {

    val language: Flow<AppLanguage?> = dataStore.data
        .map { AppLanguage.fromTagOrNull(it[KEY]) }
        .distinctUntilChanged()

    suspend fun current(): AppLanguage? = language.first()

    suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit { it[KEY] = language.tag }
    }

    private companion object {
        val KEY = stringPreferencesKey("app_language")
    }
}
