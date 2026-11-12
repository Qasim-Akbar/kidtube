package com.bbro.kidtube.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The children the parent last shared a video to, so the share sheet can pre-select them again when there is more than one
 * child. Convenience only: a stored id that no longer exists is ignored, and the sheet never saves without the parent pressing Save.
 */
@Singleton
class ShareChildStore @Inject constructor(private val dataStore: DataStore<Preferences>) {
    suspend fun last(): Set<String> =
        dataStore.data.first()[KEY].orEmpty().split(SEPARATOR).filter { it.isNotBlank() }.toSet()

    suspend fun remember(childIds: Collection<String>) {
        dataStore.edit { it[KEY] = childIds.joinToString(SEPARATOR) }
    }

    private companion object {
        val KEY = stringPreferencesKey("last_share_child_ids")
        const val SEPARATOR = ","
    }
}
