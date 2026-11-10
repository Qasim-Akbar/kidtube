package com.bbro.kidtube.core

/** Languages the app is translated into. [tag] is the BCP-47 tag used by AppCompatDelegate and locales_config.xml. */
enum class AppLanguage(val tag: String) {
    ENGLISH("en"),
    URDU("ur"),
    ;

    val isRtl: Boolean get() = this == URDU

    companion object {
        /** Language for a locale tag such as "ur", "ur-PK" or "en-GB"; anything unknown falls back to English. */
        fun fromTag(tag: String?): AppLanguage {
            val language = tag?.substringBefore('-')?.substringBefore('_')?.lowercase()
            return entries.firstOrNull { it.tag == language } ?: ENGLISH
        }

        /** Like [fromTag] but null when there is no tag at all (nothing chosen yet). */
        fun fromTagOrNull(tag: String?): AppLanguage? = tag?.takeIf { it.isNotBlank() }?.let(::fromTag)
    }
}
