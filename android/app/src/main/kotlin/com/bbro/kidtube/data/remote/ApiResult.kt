package com.bbro.kidtube.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.Response
import java.io.IOException

/** Every remote call returns one of these; no raw exception leaks to callers (except coroutine cancellation). */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>

    /**
     * Non-2xx answer. [code] is 0 when a 2xx body could not be decoded (message says so).
     * [message] is the backend's `detail` when it sent one. A 401 here means the single refresh-and-retry
     * already happened; session consequences (NeedsLogin / DeviceRevoked) are recorded by the Authenticator.
     */
    data class HttpError(val code: Int, val message: String?) : ApiResult<Nothing>

    /** HTTP 429. [retryAfterSeconds] is the `Retry-After` header when it was a number of seconds. */
    data class RateLimited(val retryAfterSeconds: Long?) : ApiResult<Nothing>

    /** No usable answer: offline, DNS, timeout, TLS, connection reset. Safe to retry later. */
    data class NetworkError(val cause: IOException) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(value))
    is ApiResult.HttpError -> this
    is ApiResult.RateLimited -> this
    is ApiResult.NetworkError -> this
}

fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Success)?.value

/** Runs a Retrofit call and maps every outcome to an [ApiResult]. */
internal suspend fun <T> apiCall(block: suspend () -> Response<T>): ApiResult<T> = guarded { mapResponse(block()) }

/** Converts the exceptions a call can throw into [ApiResult] values. Cancellation is rethrown. */
internal suspend fun <T> guarded(block: suspend () -> ApiResult<T>): ApiResult<T> =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        ApiResult.NetworkError(e)
    } catch (e: SerializationException) {
        ApiResult.HttpError(0, "Malformed response from server")
    }

internal fun <T> mapResponse(response: Response<T>): ApiResult<T> {
    val code = response.code()
    if (code == 429) {
        return ApiResult.RateLimited(response.headers()["Retry-After"]?.trim()?.toLongOrNull())
    }
    if (response.isSuccessful) {
        val body = response.body()
        if (body != null) return ApiResult.Success(body)
        // Retrofit gives a null body for 204/205 even for Response<Unit>; only the DELETE endpoints (T = Unit) answer so.
        if (code == 204 || code == 205) {
            @Suppress("UNCHECKED_CAST")
            return ApiResult.Success(Unit as T)
        }
        return ApiResult.HttpError(code, "Empty response body")
    }
    return ApiResult.HttpError(code, errorDetail(response))
}

private fun errorDetail(response: Response<*>): String? = try {
    val raw = response.errorBody()?.string()?.takeIf { it.isNotBlank() }
    raw?.let {
        val detail = (KidTubeJson.instance.parseToJsonElement(it) as? JsonObject)?.get("detail")
        (detail as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: detail?.toString() ?: it
    }?.take(500)
} catch (_: Exception) {
    null
}
