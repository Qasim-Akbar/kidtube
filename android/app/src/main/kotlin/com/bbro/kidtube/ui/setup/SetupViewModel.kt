package com.bbro.kidtube.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bbro.kidtube.core.AppLanguage
import com.bbro.kidtube.core.LanguageStore
import com.bbro.kidtube.core.LocaleApplier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Setup screen logic. Only the language is handled here; the role cards navigate straight to the (still placeholder)
 * parent-login / kid-pairing screens, which will persist the role once they exist.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val languageStore: LanguageStore,
    private val localeApplier: LocaleApplier,
) : ViewModel() {

    /** Saves the choice first, then switches the running app (which recreates the activity). */
    fun onLanguageSelected(language: AppLanguage) {
        viewModelScope.launch {
            languageStore.setLanguage(language)
            localeApplier.apply(language)
        }
    }
}
