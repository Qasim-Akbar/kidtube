package com.bbro.kidtube.di

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Debug builds only. Logs method, URL, status and headers but NEVER bodies (they carry passwords and tokens), and
 * the Authorization / cookie header values are replaced by "██". It is a NETWORK interceptor so it sees the request
 * exactly as sent (with the bearer header added by AuthInterceptor), which is why the redaction matters.
 */
fun debugLoggingInterceptor(
    logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT,
): HttpLoggingInterceptor = HttpLoggingInterceptor(logger).apply {
    level = HttpLoggingInterceptor.Level.HEADERS
    redactHeader("Authorization")
    redactHeader("Proxy-Authorization")
    redactHeader("Cookie")
    redactHeader("Set-Cookie")
}

fun OkHttpClient.Builder.addDebugInterceptors(
    logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT,
): OkHttpClient.Builder = addNetworkInterceptor(debugLoggingInterceptor(logger))
