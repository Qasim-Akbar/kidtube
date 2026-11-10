package com.bbro.kidtube.core

import app.cash.turbine.test
import com.bbro.kidtube.testing.InMemoryPreferencesDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageStoreTest {

    @Test
    fun nothingChosenYet_isNull() = runBlocking {
        assertNull(LanguageStore(InMemoryPreferencesDataStore()).current())
    }

    @Test
    fun chosenLanguageSurvivesAProcessRestart() = runBlocking {
        val backing = InMemoryPreferencesDataStore()
        LanguageStore(backing).setLanguage(AppLanguage.URDU)

        // A new store over the same backing = the app started again.
        assertEquals(AppLanguage.URDU, LanguageStore(backing).current())
    }

    @Test
    fun changesAreEmitted() = runBlocking {
        val store = LanguageStore(InMemoryPreferencesDataStore())
        store.language.test {
            assertNull(awaitItem())
            store.setLanguage(AppLanguage.URDU)
            assertEquals(AppLanguage.URDU, awaitItem())
            store.setLanguage(AppLanguage.ENGLISH)
            assertEquals(AppLanguage.ENGLISH, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun languageSettingDoesNotDisturbTheSession() = runBlocking {
        val backing = InMemoryPreferencesDataStore()
        val session = SessionStore(backing, com.bbro.kidtube.testing.FakeSecretCipher())
        session.saveParentLogin("a", "r", "fam-1")

        LanguageStore(backing).setLanguage(AppLanguage.URDU)

        assertEquals(SessionState.ParentDevice(ActiveMode.PARENT), session.currentState())
        assertEquals("fam-1", session.currentInfo().familyId)
    }

    @Test
    fun tagParsing() {
        assertEquals(AppLanguage.URDU, AppLanguage.fromTag("ur-PK"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-GB"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("fr"))
        assertNull(AppLanguage.fromTagOrNull(""))
        assertNull(AppLanguage.fromTagOrNull(null))
    }
}
