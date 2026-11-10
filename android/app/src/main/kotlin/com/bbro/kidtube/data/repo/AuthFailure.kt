package com.bbro.kidtube.data.repo

import com.bbro.kidtube.data.remote.ApiResult

/** What went wrong on a sign-in, account-creation or pairing attempt, in terms the screens can explain. */
sealed interface AuthFailure {
    /** Login 401. */
    data object WrongCredentials : AuthFailure

    /** Pairing: the code is wrong, expired or malformed (400 / 422). */
    data object WrongCode : AuthFailure

    /** 429. [retryAfterSeconds] is the server's Retry-After, when it sent one. */
    data class RateLimited(val retryAfterSeconds: Long?) : AuthFailure

    /** The phone has no network. */
    data object NoConnection : AuthFailure

    /** The phone is online but the server did not answer properly (wrong address, server down, not a KidTube server). */
    data object ServerUnreachable : AuthFailure

    /** Register 409. */
    data object EmailTaken : AuthFailure

    /** Register 422: the server rejected the email or password. */
    data object InvalidInput : AuthFailure

    /** Register 403 or 404: this server does not allow creating accounts. */
    data object RegistrationClosed : AuthFailure

    /** Anything else. */
    data class Unknown(val code: Int) : AuthFailure
}

/** Maps a failed [ApiResult] (backend error shapes: see the contract fixtures) to an [AuthFailure]. */
object AuthFailures {
    /** @param isOnline asked only for a [ApiResult.NetworkError], to tell "no connection" from "server unreachable". */
    fun fromLogin(result: ApiResult<*>, isOnline: () -> Boolean): AuthFailure =
        common(result, isOnline) { code ->
            when (code) {
                401 -> AuthFailure.WrongCredentials
                422 -> AuthFailure.InvalidInput
                else -> null
            }
        }

    fun fromRegister(result: ApiResult<*>, isOnline: () -> Boolean): AuthFailure =
        common(result, isOnline) { code ->
            when (code) {
                403, 404 -> AuthFailure.RegistrationClosed
                409 -> AuthFailure.EmailTaken
                422 -> AuthFailure.InvalidInput
                else -> null
            }
        }

    fun fromPair(result: ApiResult<*>, isOnline: () -> Boolean): AuthFailure =
        common(result, isOnline) { code ->
            when (code) {
                400, 422 -> AuthFailure.WrongCode
                else -> null
            }
        }

    private fun common(
        result: ApiResult<*>,
        isOnline: () -> Boolean,
        specific: (Int) -> AuthFailure?,
    ): AuthFailure = when (result) {
        is ApiResult.RateLimited -> AuthFailure.RateLimited(result.retryAfterSeconds)
        is ApiResult.NetworkError -> if (isOnline()) AuthFailure.ServerUnreachable else AuthFailure.NoConnection
        is ApiResult.HttpError -> specific(result.code) ?: when (result.code) {
            // code 0 = the body was not what a KidTube server sends; 404 / 502-504 = wrong address or server down.
            0, 404, 502, 503, 504 -> AuthFailure.ServerUnreachable
            else -> AuthFailure.Unknown(result.code)
        }
        is ApiResult.Success -> error("not a failure")
    }
}
