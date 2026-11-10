package com.bbro.kidtube.data.remote

import com.bbro.kidtube.core.ServerAddressStore
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/** Where requests should go right now (a base URL ending with "/"). Read for every request. */
fun interface BaseUrlSource {
    fun current(): String
}

/** Reads the address the user saved (or the build default) from [ServerAddressStore]. Called on OkHttp threads. */
class StoreBaseUrlSource(private val store: ServerAddressStore) : BaseUrlSource {
    override fun current(): String = runBlocking { store.current() }
}

/**
 * Retrofit needs a fixed base URL, so it is built with a placeholder; this interceptor swaps in the CURRENT server
 * address (scheme, host, port and any path prefix) on every request. Changing the address therefore needs no rebuild.
 */
class BaseUrlInterceptor(
    placeholderBaseUrl: String,
    private val source: BaseUrlSource,
) : Interceptor {
    private val placeholder: HttpUrl = placeholderBaseUrl.toHttpUrl()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = source.current().toHttpUrlOrNull() ?: return chain.proceed(request)

        val relative = request.url.encodedPath.removePrefix(placeholder.encodedPath.trimEnd('/')).trimStart('/')
        val prefix = target.encodedPath.trimEnd('/')
        val url = request.url.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            .encodedPath("$prefix/$relative")
            .build()
        return chain.proceed(request.newBuilder().url(url).build())
    }
}
