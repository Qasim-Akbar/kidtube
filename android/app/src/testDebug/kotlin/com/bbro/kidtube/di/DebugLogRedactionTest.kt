package com.bbro.kidtube.di

import com.bbro.kidtube.core.FakeClock
import com.bbro.kidtube.data.remote.ApiResult
import com.bbro.kidtube.data.remote.ApiWiring
import com.bbro.kidtube.data.remote.KidTubeJson
import com.bbro.kidtube.testing.SessionHarness
import com.bbro.kidtube.testing.fakeJwt
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Debug builds log HTTP traffic; this proves no credential ever reaches the log output. */
class DebugLogRedactionTest {
    private lateinit var server: MockWebServer
    private lateinit var session: SessionHarness
    private val logs = CopyOnWriteArrayList<String>()
    private val clock = FakeClock(Instant.parse("2026-11-05T10:00:00Z"))

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        session = SessionHarness()
    }

    @After
    fun tearDown() {
        server.close()
        session.close()
    }

    private fun assertNoSecretInLogs(vararg secrets: String) {
        assertTrue("expected some log output", logs.isNotEmpty())
        for (secret in secrets) {
            assertTrue("secret leaked into the logs: $secret", logs.none { it.contains(secret) })
        }
    }

    @Test
    fun parentFlowNeverLogsPasswordsOrTokens() = runBlocking {
        val loginAccess = fakeJwt(clock.now().plusSeconds(900), unique = "LOGINACCESS")
        val refresh = fakeJwt(clock.now().plusSeconds(86_400), type = "refresh", unique = "REFRESHSECRET")
        val renewedAccess = fakeJwt(clock.now().plusSeconds(900), unique = "RENEWEDACCESS")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/auth/login" -> reply(200, """{"access_token":"$loginAccess","refresh_token":"$refresh","token_type":"bearer"}""")
                "/auth/refresh" -> reply(200, """{"access_token":"$renewedAccess","token_type":"bearer"}""")
                else -> if (request.headers["Authorization"] == "Bearer $renewedAccess") reply(200, "[]") else reply(401, "{}")
            }
        }
        val remote = ApiWiring.create(
            server.url("/").toString(), KidTubeJson.instance, session.store, clock,
        ) { it.addDebugInterceptors(HttpLoggingInterceptor.Logger { line -> logs += line }) }.remote

        val login = remote.login("mama@example.com", "hunter2-PASSWORD") as ApiResult.Success
        session.store.saveParentLogin(login.value.accessToken, login.value.refreshToken, "fam-1")
        // 401 with the login token -> refresh (request body carries the refresh token) -> retry with the renewed token
        assertTrue(remote.listChildren() is ApiResult.Success)

        assertNoSecretInLogs("hunter2-PASSWORD", loginAccess, refresh, renewedAccess, "LOGINACCESS", "REFRESHSECRET", "RENEWEDACCESS")
        assertTrue(
            "the Authorization header must be present but redacted",
            logs.any { it.startsWith("Authorization:") && it.contains("██") },
        )
    }

    @Test
    fun kidDeviceFlowNeverLogsTheDeviceToken() = runBlocking {
        session.store.saveDevicePairing("DEVICE-TOKEN-SECRET", "dev-1", "fam-1")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = reply(200, "[]")
        }
        val remote = ApiWiring.create(
            server.url("/").toString(), KidTubeJson.instance, session.store, clock,
        ) { it.addDebugInterceptors(HttpLoggingInterceptor.Logger { line -> logs += line }) }.remote

        assertTrue(remote.listChildren() is ApiResult.Success)

        assertNoSecretInLogs("DEVICE-TOKEN-SECRET")
        assertTrue(logs.any { it.startsWith("Authorization:") && it.contains("██") })
    }

    private fun reply(code: Int, body: String): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
}
