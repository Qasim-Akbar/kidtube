package com.bbro.kidtube.core

import com.bbro.kidtube.data.remote.BaseUrlInterceptor
import com.bbro.kidtube.data.remote.BaseUrlSource
import com.bbro.kidtube.testing.AuthRig
import com.bbro.kidtube.testing.ContractFixtures
import com.bbro.kidtube.testing.InMemoryPreferencesDataStore
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {
    private val release = ServerAddressPolicy(allowCleartext = false)
    private val debug = ServerAddressPolicy(allowCleartext = true)

    private fun ServerAddressPolicy.valid(input: String) = (check(input) as ServerAddressCheck.Valid).url

    private fun ServerAddressPolicy.error(input: String) = (check(input) as ServerAddressCheck.Invalid).error

    // ---- validation --------------------------------------------------------------------------

    @Test
    fun https_isAcceptedEverywhere_andNormalised() {
        assertEquals("https://kidtube.example.com/", release.valid("https://kidtube.example.com"))
        assertEquals("https://kidtube.example.com/", debug.valid("  HTTPS://KidTube.Example.com/  "))
        assertEquals("https://example.com:8443/kidtube/", release.valid("https://example.com:8443/kidtube"))
    }

    @Test
    fun http_isAllowedInDebugOnly() {
        assertEquals("http://10.0.2.2:8001/", debug.valid("http://10.0.2.2:8001"))
        assertEquals(ServerAddressError.SCHEME, release.error("http://10.0.2.2:8001"))
        assertEquals(ServerAddressError.SCHEME, release.error("HTTP://example.com"))
    }

    @Test
    fun missingOrOtherSchemes_areSchemeErrors() {
        assertEquals(ServerAddressError.SCHEME, release.error("kidtube.example.com"))
        assertEquals(ServerAddressError.SCHEME, debug.error("kidtube.example.com"))
        assertEquals(ServerAddressError.SCHEME, debug.error("ftp://example.com"))
        assertEquals(ServerAddressError.SCHEME, release.error("//example.com"))
    }

    @Test
    fun emptyAndBlank() {
        assertEquals(ServerAddressError.EMPTY, release.error(""))
        assertEquals(ServerAddressError.EMPTY, release.error("   "))
    }

    @Test
    fun notAPlainAddress_isRejected() {
        assertEquals(ServerAddressError.NOT_A_URL, release.error("https://"))
        assertEquals(ServerAddressError.NOT_A_URL, release.error("https://exa mple.com"))
        assertEquals(ServerAddressError.NOT_A_URL, release.error("https://user:pw@example.com"))
        assertEquals(ServerAddressError.NOT_A_URL, release.error("https://example.com/?a=b"))
        assertEquals(ServerAddressError.NOT_A_URL, release.error("https://example.com/#x"))
    }

    // ---- the store ---------------------------------------------------------------------------

    private fun store(allowCleartext: Boolean, data: InMemoryPreferencesDataStore = InMemoryPreferencesDataStore()) =
        ServerAddressStore(data, ServerConfig("https://default.example.com/", allowCleartext))

    @Test
    fun defaultsToTheBuildConfigValue_untilSaved() = runBlocking {
        val store = store(allowCleartext = false)
        assertEquals("https://default.example.com/", store.current())

        assertTrue(store.save("https://mine.example.org") is ServerAddressCheck.Valid)
        assertEquals("https://mine.example.org/", store.current())
    }

    @Test
    fun anInvalidAddressIsNotSaved() = runBlocking {
        val store = store(allowCleartext = false)
        assertEquals(ServerAddressCheck.Invalid(ServerAddressError.SCHEME), store.save("http://mine.example.org"))
        assertEquals("https://default.example.com/", store.current())
    }

    @Test
    fun theSavedAddressSurvivesARestart() = runBlocking {
        val data = InMemoryPreferencesDataStore()
        store(false, data).save("https://mine.example.org")
        assertEquals("https://mine.example.org/", store(false, data).current())
    }

    @Test
    fun aStoredHttpAddressIsIgnoredWhereCleartextIsNotAllowed() = runBlocking {
        val data = InMemoryPreferencesDataStore()
        store(allowCleartext = true, data = data).save("http://10.0.2.2:8001")
        assertEquals("http://10.0.2.2:8001/", store(true, data).current())
        // the same data read by a release build falls back to the default
        assertEquals("https://default.example.com/", store(false, data).current())
    }

    // ---- the API client reads the CURRENT address --------------------------------------------

    @Test
    fun theInterceptorSendsEachRequestToTheCurrentAddress() {
        val a = MockWebServer().apply { start() }
        val b = MockWebServer().apply { start() }
        try {
            a.enqueue(MockResponse.Builder().body("A").build())
            b.enqueue(MockResponse.Builder().body("B").build())
            var target = a.url("/").toString()
            val client = OkHttpClient.Builder()
                .addInterceptor(BaseUrlInterceptor("http://placeholder.invalid/", BaseUrlSource { target }))
                .build()
            fun get(path: String) = client.newCall(Request.Builder().url("http://placeholder.invalid/$path").build())
                .execute().use { it.body.string() }

            assertEquals("A", get("auth/login"))
            assertEquals("/auth/login", a.takeRequest().url.encodedPath)

            target = b.url("/").toString() // the user saved a new address: no rebuild, no restart
            assertEquals("B", get("health"))
            assertEquals("/health", b.takeRequest().url.encodedPath)
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun aPathPrefixInTheAddressIsKept() {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse.Builder().body("ok").build())
            val client = OkHttpClient.Builder()
                .addInterceptor(
                    BaseUrlInterceptor("http://placeholder.invalid/", BaseUrlSource { server.url("/kidtube/").toString() }),
                ).build()
            client.newCall(Request.Builder().url("http://placeholder.invalid/children?x=1").build()).execute().close()
            val seen = server.takeRequest().url
            assertEquals("/kidtube/children", seen.encodedPath)
            assertEquals("1", seen.queryParameter("x"))
        } finally {
            server.close()
        }
    }

    @Test
    fun theProductionWiringFollowsTheSavedAddress() = runBlocking {
        val rig = AuthRig()
        val other = MockWebServer().apply { start() }
        try {
            // saved address -> a second server; the stack was built before and is not rebuilt
            assertTrue(rig.serverAddress.save(other.url("/").toString()) is ServerAddressCheck.Valid)
            val fx = ContractFixtures.load(6)
            other.enqueue(
                MockResponse.Builder().code(fx.status).addHeader("Content-Type", "application/json").body(fx.bodyText).build(),
            )

            rig.auth.login("mum@example.com", "correct-horse-battery")

            assertEquals("/auth/login", other.takeRequest().url.encodedPath)
            assertEquals(0, rig.server.requestCount)
        } finally {
            other.close()
            rig.close()
        }
    }
}
