package com.bbro.kidtube.data.remote.auth

import com.bbro.kidtube.core.Clock
import com.bbro.kidtube.core.JwtClaims
import com.bbro.kidtube.core.SessionStore
import com.bbro.kidtube.data.remote.ApiResult
import com.bbro.kidtube.data.remote.AuthApi
import com.bbro.kidtube.data.remote.RefreshRequest
import com.bbro.kidtube.data.remote.apiCall
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration

/**
 * Renews the parent access token through POST /auth/refresh. SINGLE-FLIGHT: callers queue on one [Mutex]; each
 * caller re-reads the stored token after getting the lock and, if someone else already replaced the token it
 * saw fail, simply reuses the new one. N concurrent 401s therefore cause exactly one refresh request.
 *
 * Uses the bare [AuthApi] (no interceptor/authenticator), so a refresh can never recurse into itself.
 */
class TokenRefresher(
    private val authApi: AuthApi,
    private val store: SessionStore,
    private val clock: Clock,
) {
    sealed interface Result {
        data class Token(val accessToken: String) : Result

        /** The refresh token is missing or was rejected (401). [SessionStore] now reports NeedsLogin. */
        data object NeedsLogin : Result

        /** Network trouble, 429 or a server error: nothing learned about the credentials, try again later. */
        data object Failed : Result
    }

    private val mutex = Mutex()

    /** [staleAccessToken] is the token that was just rejected or is about to expire (null if there was none). */
    suspend fun refresh(staleAccessToken: String?): Result = mutex.withLock {
        val current = store.accessToken()
        if (current != null && current != staleAccessToken) return@withLock Result.Token(current)

        val refreshToken = store.refreshToken()
        if (refreshToken == null) {
            store.markNeedsLogin()
            return@withLock Result.NeedsLogin
        }
        when (val result = apiCall { authApi.refresh(RefreshRequest(refreshToken)) }) {
            is ApiResult.Success -> {
                store.updateAccessToken(result.value.accessToken)
                Result.Token(result.value.accessToken)
            }
            is ApiResult.HttpError -> if (result.code == 401) {
                store.markNeedsLogin()
                Result.NeedsLogin
            } else {
                Result.Failed
            }
            is ApiResult.RateLimited, is ApiResult.NetworkError -> Result.Failed
        }
    }

    /** True when the token's `exp` (read without verifying) is within [PROACTIVE_WINDOW] of now, or already past. */
    fun isNearExpiry(accessToken: String): Boolean {
        val exp = JwtClaims.parse(accessToken)?.expiresAt ?: return false // unreadable: let the server decide
        return Duration.between(clock.now(), exp) < PROACTIVE_WINDOW
    }

    companion object {
        val PROACTIVE_WINDOW: Duration = Duration.ofSeconds(60)
    }
}
