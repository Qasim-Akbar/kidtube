package com.bbro.kidtube.core

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import javax.inject.Inject

/** Puts a language into effect for the whole app (recreates the visible activity). Main thread only. */
interface LocaleApplier {
    /** The per-app language currently in effect, or null when the app follows the system language. */
    fun current(): AppLanguage?

    fun apply(language: AppLanguage)
}

class AppCompatLocaleApplier @Inject constructor() : LocaleApplier {
    override fun current(): AppLanguage? =
        AppLanguage.fromTagOrNull(AppCompatDelegate.getApplicationLocales().toLanguageTags())

    override fun apply(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    }
}
