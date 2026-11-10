package com.bbro.kidtube.data.remote

import com.bbro.kidtube.core.Clock
import com.bbro.kidtube.core.SessionStore
import com.bbro.kidtube.data.remote.auth.AuthInterceptor
import com.bbro.kidtube.data.remote.auth.TokenAuthenticator
import com.bbro.kidtube.data.remote.auth.TokenRefresher
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

class ApiStack(val remote: KidTubeRemote, val refresher: TokenRefresher)

/**
 * Builds the networking stack. Shared by the Hilt module and the tests, so tests exercise the production wiring:
 *  - a BARE client (no auth) for login/register/refresh/pair/health, and
 *  - an AUTHED client (AuthInterceptor + TokenAuthenticator) for everything else.
 * [customize] is applied to both (the debug build adds a header-redacting logging interceptor there).
 */
object ApiWiring {
    fun create(
        baseUrl: String,
        json: Json,
        store: SessionStore,
        clock: Clock,
        baseUrlSource: BaseUrlSource? = null,
        customize: (OkHttpClient.Builder) -> OkHttpClient.Builder = { it },
    ): ApiStack {
        // With a [baseUrlSource], [baseUrl] is only the placeholder Retrofit is built with; every request (including the
        // token refresh) is redirected to the source's CURRENT address.
        fun base() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .apply { if (baseUrlSource != null) addInterceptor(BaseUrlInterceptor(baseUrl, baseUrlSource)) }

        val converter = json.asConverterFactory("application/json".toMediaType())

        fun retrofit(client: OkHttpClient) = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(converter)
            .build()

        val bareClient = customize(base()).build()
        val authApi = retrofit(bareClient).create(AuthApi::class.java)
        val refresher = TokenRefresher(authApi, store, clock)

        val authedClient = customize(base())
            .addInterceptor(AuthInterceptor(store, refresher))
            .authenticator(TokenAuthenticator(store, refresher))
            .build()
        val api = retrofit(authedClient).create(KidTubeApi::class.java)
        return ApiStack(KidTubeRemote(authApi, api), refresher)
    }
}
