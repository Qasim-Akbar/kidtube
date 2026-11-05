package com.bbro.kidtube.di

import okhttp3.OkHttpClient

/** Release builds: no logging interceptor on the classpath, so this is a no-op. */
@Suppress("unused")
fun OkHttpClient.Builder.addDebugInterceptors(): OkHttpClient.Builder = this
