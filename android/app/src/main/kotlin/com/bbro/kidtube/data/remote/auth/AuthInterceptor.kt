package com.bbro.kidtube.data.remote.auth

import com.bbro.kidtube.core.DeviceRole
import com.bbro.kidtube.core.SessionStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer ...` for the current device role: the parent access token on a PARENT device (in both
 * parent and kid mode), the device token on a KID device. A parent token that expires within 60 s is refreshed
 * first. Runs on OkHttp's dispatcher threads, never the main thread, so blocking on DataStore is fine.
 */
class AuthInterceptor(
    private val store: SessionStore,
    private val refresher: TokenRefresher,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { currentToken() }
        val request = if (token == null) {
            chain.request()
        } else {
            chain.request().newBuilder().header("Authorization", "Bearer $token").build()
        }
        return chain.proceed(request)
    }

    private suspend fun currentToken(): String? = when (store.currentInfo().deviceRole) {
        DeviceRole.KID -> store.deviceToken()
        DeviceRole.PARENT -> {
            val access = store.accessToken()
            if (access == null || refresher.isNearExpiry(access)) {
                // On failure keep the old token (may still be valid for a few seconds); the Authenticator handles a 401.
                (refresher.refresh(access) as? TokenRefresher.Result.Token)?.accessToken ?: access
            } else {
                access
            }
        }
        null -> null
    }
}
