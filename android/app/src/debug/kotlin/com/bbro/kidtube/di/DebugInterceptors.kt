package com.bbro.kidtube.di

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

/** Debug builds only. BASIC level: method, URL, status and timing, never bodies or auth headers. */
fun OkHttpClient.Builder.addDebugInterceptors(): OkHttpClient.Builder =
    addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
