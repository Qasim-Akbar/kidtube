package com.bbro.kidtube.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Build-time facts about the server address. [defaultBaseUrl] ends with "/". Cleartext (http) is a debug-only allowance. */
data class ServerConfig(val defaultBaseUrl: String, val allowCleartext: Boolean)

enum class ServerAddressError {
    EMPTY,

    /** Missing scheme, or http:// where only https:// is allowed (or any other scheme). */
    SCHEME,

    /** Not a plain base address (no host, or it carries a user name, query or fragment). */
    NOT_A_URL,
}

sealed interface ServerAddressCheck {
    /** [url] is normalised: lower-case scheme and host, trailing slash. */
    data class Valid(val url: String) : ServerAddressCheck

    data class Invalid(val error: ServerAddressError) : ServerAddressCheck
}

/** Pure validation of what the user typed. https:// always; http:// only when [allowCleartext] (debug builds). */
class ServerAddressPolicy(val allowCleartext: Boolean) {
    fun check(input: String): ServerAddressCheck {
        val text = input.trim()
        if (text.isEmpty()) return ServerAddressCheck.Invalid(ServerAddressError.EMPTY)

        val scheme = text.substringBefore("://", missingDelimiterValue = "").lowercase()
        val schemeOk = scheme == "https" || (scheme == "http" && allowCleartext)
        if (!schemeOk) return ServerAddressCheck.Invalid(ServerAddressError.SCHEME)

        val url = text.toHttpUrlOrNull()
            ?: return ServerAddressCheck.Invalid(ServerAddressError.NOT_A_URL)
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
            return ServerAddressCheck.Invalid(ServerAddressError.NOT_A_URL)
        }
        val normalised = url.toString().let { if (it.endsWith("/")) it else "$it/" }
        return ServerAddressCheck.Valid(normalised)
    }
}

/**
 * The server the app talks to: what the user saved, else the build default. Stored in the app DataStore (non-secret).
 * The API client reads [current] for every request, so a change applies at once. A stored value that no longer passes the
 * policy (for example an http:// address after switching to a release build) is ignored in favour of the default.
 */
@Singleton
class ServerAddressStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val config: ServerConfig,
) {
    val policy = ServerAddressPolicy(config.allowCleartext)

    val address: Flow<String> = dataStore.data.map { resolve(it[KEY]) }.distinctUntilChanged()

    suspend fun current(): String = address.first()

    fun defaultAddress(): String = config.defaultBaseUrl

    fun check(input: String): ServerAddressCheck = policy.check(input)

    /** Validates and, when valid, persists the address. */
    suspend fun save(input: String): ServerAddressCheck {
        val result = policy.check(input)
        if (result is ServerAddressCheck.Valid) dataStore.edit { it[KEY] = result.url }
        return result
    }

    private fun resolve(stored: String?): String =
        (stored?.let { policy.check(it) } as? ServerAddressCheck.Valid)?.url ?: config.defaultBaseUrl

    private companion object {
        val KEY = stringPreferencesKey("server_address")
    }
}
