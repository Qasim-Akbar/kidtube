package com.bbro.kidtube.data.remote.auth

import com.bbro.kidtube.core.DeviceRole
import com.bbro.kidtube.core.SessionStore
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Handles a 401 on an authenticated call:
 *  - parent device: single-flight refresh, then retry ONCE with the new token. If the refresh itself is rejected
 *    the session becomes NeedsLogin (local data is kept).
 *  - kid device: the device token was rejected, so the session becomes DeviceRevoked. No retry.
 */
class TokenAuthenticator(
    private val store: SessionStore,
    private val refresher: TokenRefresher,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val sentHeader = response.request.header("Authorization") ?: return null // anonymous call: nothing to renew
        if (response.priorResponse != null) return null // this was already the retry; give up (retry once only)
        val sentToken = sentHeader.removePrefix("Bearer ").trim()

        return runBlocking {
            when (store.currentInfo().deviceRole) {
                DeviceRole.KID -> {
                    // Only if the rejected token is still the current one (not an in-flight call from before a re-pair).
                    if (store.deviceToken() == sentToken) store.markDeviceRevoked()
                    null
                }
                DeviceRole.PARENT -> when (val result = refresher.refresh(sentToken)) {
                    is TokenRefresher.Result.Token ->
                        response.request.newBuilder().header("Authorization", "Bearer ${result.accessToken}").build()
                    TokenRefresher.Result.NeedsLogin, TokenRefresher.Result.Failed -> null
                }
                null -> null
            }
        }
    }
}
