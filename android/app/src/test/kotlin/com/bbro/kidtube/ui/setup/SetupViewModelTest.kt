package com.bbro.kidtube.ui.setup

import com.bbro.kidtube.core.AppLanguage
import com.bbro.kidtube.core.LanguageStore
import com.bbro.kidtube.core.LocaleApplier
import com.bbro.kidtube.testing.InMemoryPreferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Plain JVM test: the ViewModel is built directly, no Hilt, no Android runtime. */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

    private class RecordingApplier : LocaleApplier {
        val applied = mutableListOf<AppLanguage>()
        override fun current(): AppLanguage? = applied.lastOrNull()
        override fun apply(language: AppLanguage) {
            applied += language
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun choosingALanguage_isSavedAndApplied() = runTest {
        val store = LanguageStore(InMemoryPreferencesDataStore())
        val applier = RecordingApplier()
        val viewModel = SetupViewModel(store, applier)

        viewModel.onLanguageSelected(AppLanguage.URDU)

        assertEquals(AppLanguage.URDU, store.current())
        assertEquals(listOf(AppLanguage.URDU), applier.applied)

        viewModel.onLanguageSelected(AppLanguage.ENGLISH)

        assertEquals(AppLanguage.ENGLISH, store.current())
        assertEquals(listOf(AppLanguage.URDU, AppLanguage.ENGLISH), applier.applied)
    }
}
