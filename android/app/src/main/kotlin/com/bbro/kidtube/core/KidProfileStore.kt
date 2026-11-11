package com.bbro.kidtube.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The child who watched last on this device, persisted only so the kid picker can highlight that tile. It is never used to
 * skip the picker: kid mode always starts at "Who is watching?".
 */
@Singleton
class KidProfileStore @Inject constructor(private val dataStore: DataStore<Preferences>) {
    val lastChildId: Flow<String?> = dataStore.data.map { it[KEY] }.distinctUntilChanged()

    suspend fun setLastChild(childId: String) {
        dataStore.edit { it[KEY] = childId }
    }

    private companion object {
        val KEY = stringPreferencesKey("last_kid_id")
    }
}
