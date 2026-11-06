package com.bbro.kidtube.data.remote

import com.bbro.kidtube.core.ActiveMode
import com.bbro.kidtube.core.FakeClock
import com.bbro.kidtube.core.SessionState
import com.bbro.kidtube.data.repo.AuthRepository
import com.bbro.kidtube.testing.SessionHarness
import com.bbro.kidtube.testing.fakeJwt
import com.bbro.kidtube.testing.resourceText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The real production wiring (ApiWiring) against a MockWebServer. */
class AuthPlumbingTest {
    private data class Seen(val method: String, val path: String, val auth: String?, val body: String)

    private lateinit var server: MockWebServer
    private lateinit var session: SessionHarness
    private lateinit var stack: ApiStack
    private val clock = FakeClock(Instant.parse("2026-11-05T10:00:00Z"))
    private val seen = ConcurrentLinkedQueue<Seen>()

    private val remote get() = stack.remote
    private val store get() = session.store

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        session = SessionHarness()
        stack = ApiWiring.create(server.url("/").toString(), KidTubeJson.instance, store, clock)
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        session.close()
    }

    private fun json(code: Int, body: String, vararg headers: Pair<String, String>): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body)
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()

    private fun serve(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += Seen(request.method.orEmpty(), request.url.encodedPath, request.headers["Authorization"], request.body?.utf8().orEmpty())
                return handler(request)
            }
        }
    }

    private fun count(path: String) = seen.count { it.path == path }

    private suspend fun parentLoggedIn(access: String = "old-access", refresh: String = "refresh-1") =
        store.saveParentLogin(access, refresh, "fam-1")

    // ---- Authorization per role -------------------------------------------------------------------

    @Test
    fun parentCallsCarryTheParentAccessToken() = runBlocking {
        parentLoggedIn("access-1")
        serve { json(200, "[]") }

        assertTrue(remote.listChildren() is ApiResult.Success)
        assertEquals("Bearer access-1", seen.single().auth)
    }

    @Test
    fun kidDeviceCallsCarryTheDeviceToken() = runBlocking {
        store.saveDevicePairing("device-token-1", "dev-1", "fam-1")
        serve { json(200, "[]") }

        assertTrue(remote.listChildren() is ApiResult.Success)
        assertEquals("Bearer device-token-1", seen.single().auth)
    }

    @Test
    fun parentDeviceInKidModeStillSendsTheParentToken() = runBlocking {
        parentLoggedIn("access-1")
        store.setActiveMode(ActiveMode.KID)
        serve { json(200, "[]") }

        remote.listChildren()
        assertEquals("Bearer access-1", seen.single().auth)
    }

    @Test
    fun loginAndPairAreAnonymousEvenWhenALoggedInParentCallsThem() = runBlocking {
        parentLoggedIn("access-1")
        serve { json(401, """{"detail":"Incorrect email or password"}""") }

        remote.login("a@b.c", "pw")
        remote.pair("123456", "tablet")
        assertEquals(listOf<String?>(null, null), seen.map { it.auth })
        assertEquals(setOf("/auth/login", "/devices/pair"), seen.map { it.path }.toSet())
    }

    @Test
    fun aWrongPasswordIs401WithoutTouchingTheSession() = runBlocking {
        serve { json(401, """{"detail":"Incorrect email or password"}""") }

        val result = remote.login("a@b.c", "wrong")
        assertEquals(ApiResult.HttpError(401, "Incorrect email or password"), result)
        assertEquals(0, count("/auth/refresh"))
        assertEquals(SessionState.Unauthenticated, store.currentState())
    }

    // ---- 401 -> refresh -> retry -----------------------------------------------------------------------

    @Test
    fun a401IsRefreshedOnceAndTheCallRetriedWithTheNewToken() = runBlocking {
        parentLoggedIn("old-access", "refresh-1")
        serve { request ->
            when (request.url.encodedPath) {
                "/auth/refresh" -> json(200, """{"access_token":"new-access","token_type":"bearer"}""")
                else -> if (request.headers["Authorization"] == "Bearer new-access") json(200, "[]") else json(401, """{"detail":"expired"}""")
            }
        }

        assertTrue(remote.listChildren() is ApiResult.Success)

        assertEquals(listOf("/children", "/auth/refresh", "/children"), seen.map { it.path })
        assertEquals(listOf<String?>("Bearer old-access", null, "Bearer new-access"), seen.map { it.auth })
        assertTrue(seen.first { it.path == "/auth/refresh" }.body.contains("refresh-1"))
        assertNull(seen.first { it.path == "/auth/refresh" }.auth)
        assertEquals("new-access", store.accessToken())
        assertEquals(SessionState.ParentDevice(ActiveMode.PARENT), store.currentState())
    }

    @Test
    fun concurrent401sTriggerExactlyOneRefresh() = runBlocking {
        parentLoggedIn("old-access", "refresh-1")
        serve { request ->
            when (request.url.encodedPath) {
                "/auth/refresh" ->
                    MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                        .body("""{"access_token":"new-access","token_type":"bearer"}""")
                        .headersDelay(400, TimeUnit.MILLISECONDS).build() // widen the race window
                else -> if (request.headers["Authorization"] == "Bearer new-access") json(200, "[]") else json(401, "{}")
            }
        }

        val results = (1..8).map { async(Dispatchers.IO) { remote.listChildren() } }.awaitAll()

        assertTrue("all calls should succeed after the shared refresh: $results", results.all { it is ApiResult.Success })
        assertEquals(1, count("/auth/refresh"))
        assertEquals("new-access", store.accessToken())
    }

    @Test
    fun refreshRejectedWith401MeansNeedsLoginAndKeepsLocalData() = runBlocking {
        parentLoggedIn("old-access", "refresh-1")
        store.setFamilyTimezone("Asia/Karachi")
        serve { request ->
            if (request.url.encodedPath == "/auth/refresh") json(401, """{"detail":"Invalid refresh token"}""") else json(401, "{}")
        }

        val result = remote.listChildren()

        assertEquals(401, (result as ApiResult.HttpError).code)
        assertEquals(1, count("/auth/refresh"))
        assertEquals(SessionState.NeedsLogin, store.currentState())
        val info = store.currentInfo()
        assertEquals("fam-1", info.familyId) // nothing wiped
        assertEquals("Asia/Karachi", info.familyTimezone)
    }

    @Test
    fun aRefreshThatFailsForOtherReasonsDoesNotLogTheParentOut() = runBlocking {
        parentLoggedIn("old-access", "refresh-1")
        serve { request -> if (request.url.encodedPath == "/auth/refresh") json(500, "{}") else json(401, "{}") }

        assertTrue(remote.listChildren() is ApiResult.HttpError)
        assertEquals(SessionState.ParentDevice(ActiveMode.PARENT), store.currentState())
    }

    @Test
    fun theCallIsRetriedOnlyOnceEvenIfTheNewTokenIsRejectedToo() = runBlocking {
        parentLoggedIn("old-access", "refresh-1")
        serve { request ->
            if (request.url.encodedPath == "/auth/refresh") json(200, """{"access_token":"new-access"}""") else json(401, "{}")
        }

        assertEquals(401, (remote.listChildren() as ApiResult.HttpError).code)
        assertEquals(2, count("/children")) // original + exactly one retry
        assertEquals(1, count("/auth/refresh"))
    }

    // ---- kid device ---------------------------------------------------------------------------------------

    @Test
    fun aRejectedKidDeviceTokenMeansDeviceRevokedAndNeverRefreshes() = runBlocking {
        store.saveDevicePairing("device-token-1", "dev-1", "fam-1")
        serve { json(401, """{"detail":"Invalid or missing credentials"}""") }

        val result = remote.postEvents(emptyList())

        assertEquals(401, (result as ApiResult.HttpError).code)
        assertEquals(SessionState.DeviceRevoked, store.currentState())
        assertEquals(0, count("/auth/refresh"))
        assertEquals(1, count("/events")) // not retried
    }

    // ---- proactive refresh -----------------------------------------------------------------------------------

    @Test
    fun refreshesBeforeTheCallWhenTheAccessTokenExpiresWithin60Seconds() = runBlocking {
        val expiring = fakeJwt(clock.now().plusSeconds(30), unique = "expiring")
        parentLoggedIn(expiring, "refresh-1")
        val fresh = fakeJwt(clock.now().plusSeconds(900), unique = "fresh")
        serve { request ->
            if (request.url.encodedPath == "/auth/refresh") json(200, """{"access_token":"$fresh"}""") else json(200, "[]")
        }

        assertTrue(remote.listChildren() is ApiResult.Success)

        assertEquals(listOf("/auth/refresh", "/children"), seen.map { it.path }) // no wasted 401 round trip
        assertEquals("Bearer $fresh", seen.last().auth)
    }

    @Test
    fun doesNotRefreshWhenTheTokenHasMoreThan60SecondsLeft() = runBlocking {
        val healthy = fakeJwt(clock.now().plusSeconds(61), unique = "healthy")
        parentLoggedIn(healthy, "refresh-1")
        serve { json(200, "[]") }

        remote.listChildren()
        assertEquals(listOf("/children"), seen.map { it.path })
    }

    @Test
    fun proactiveRefreshUsesTheInjectedClock() = runBlocking {
        val token = fakeJwt(clock.now().plusSeconds(600), unique = "t")
        parentLoggedIn(token, "refresh-1")
        serve { request ->
            if (request.url.encodedPath == "/auth/refresh") json(200, """{"access_token":"renewed"}""") else json(200, "[]")
        }

        remote.listChildren()
        assertEquals(0, count("/auth/refresh"))

        clock.advance(Duration.ofSeconds(560)) // 40 s left
        remote.listChildren()
        assertEquals(1, count("/auth/refresh"))
        assertEquals("Bearer renewed", seen.last().auth)
    }

    @Test
    fun aFailedProactiveRefreshFallsBackToTheOldTokenAndKeepsTheSession() = runBlocking {
        val expiring = fakeJwt(clock.now().plusSeconds(20), unique = "expiring")
        parentLoggedIn(expiring, "refresh-1")
        serve { request -> if (request.url.encodedPath == "/auth/refresh") json(500, "{}") else json(200, "[]") }

        assertTrue(remote.listChildren() is ApiResult.Success)
        assertEquals("Bearer $expiring", seen.last().auth)
        assertEquals(SessionState.ParentDevice(ActiveMode.PARENT), store.currentState())
    }

    // ---- result mapping --------------------------------------------------------------------------------------------

    @Test
    fun http429MapsToRateLimitedWithRetryAfter() = runBlocking {
        parentLoggedIn("access-1")
        serve { json(429, """{"detail":"Too many requests"}""", "Retry-After" to "17") }

        assertEquals(ApiResult.RateLimited(17), remote.listChildren())
    }

    @Test
    fun http429WithoutANumericRetryAfterHasNoDelay() = runBlocking {
        serve { request ->
            if (request.url.encodedPath == "/auth/login") json(429, "{}") else json(429, "{}", "Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT")
        }

        assertEquals(ApiResult.RateLimited(null), remote.login("a@b.c", "pw"))
        assertEquals(ApiResult.RateLimited(null), remote.pair("123456", "tablet"))
    }

    @Test
    fun anUnreachableServerIsANetworkErrorNotAnException() = runBlocking {
        parentLoggedIn("access-1")
        server.close()

        assertTrue(remote.listChildren() is ApiResult.NetworkError)
        assertTrue(remote.login("a@b.c", "pw") is ApiResult.NetworkError)
    }

    @Test
    fun aBodyThatCannotBeDecodedIsAnHttpErrorNotAnException() = runBlocking {
        parentLoggedIn("access-1")
        serve { json(200, "this is not json") }

        val result = remote.listChildren()
        assertEquals(0, (result as ApiResult.HttpError).code)
    }

    @Test
    fun theBackendDetailBecomesTheErrorMessage() = runBlocking {
        parentLoggedIn("access-1")
        serve { json(409, """{"detail":"Video is already in this plan"}""") }

        assertEquals(ApiResult.HttpError(409, "Video is already in this plan"), remote.addPlanItem("c1", PlanItemCreate("v1")))
    }

    @Test
    fun validationDetailListsAreKeptAsText() = runBlocking {
        parentLoggedIn("access-1")
        serve { json(422, """{"detail":[{"loc":["body","pace_value"],"msg":"too small"}]}""") }

        val error = remote.putPlan("c1", PlanWrite(PaceType.VIDEOS_PER_DAY, 0, 31, null, java.time.LocalDate.of(2026, 11, 2))) as ApiResult.HttpError
        assertEquals(422, error.code)
        assertTrue(error.message!!.contains("pace_value"))
    }

    @Test
    fun deleteReturning204IsASuccess() = runBlocking {
        parentLoggedIn("access-1")
        serve { MockResponse.Builder().code(204).build() }

        assertEquals(ApiResult.Success(Unit), remote.deleteVideo("v1"))
    }

    // ---- pull / ETag ----------------------------------------------------------------------------------------------

    @Test
    fun pullSendsIfNoneMatchAndMaps304() = runBlocking {
        parentLoggedIn("access-1")
        val snapshot = resourceText("/fixtures/snapshot.json")
        val ifNoneMatch = java.util.Collections.synchronizedList(mutableListOf<String?>())
        serve { request ->
            ifNoneMatch += request.headers["If-None-Match"]
            if (request.headers["If-None-Match"] == "\"abc123\"") {
                MockResponse.Builder().code(304).addHeader("ETag", "\"abc123\"").build()
            } else {
                json(200, snapshot, "ETag" to "\"abc123\"")
            }
        }

        val firstResult = remote.pull(null)
        assertTrue("pull failed: $firstResult", firstResult is ApiResult.Success)
        val first = (firstResult as ApiResult.Success).value as PullOutcome.Updated
        assertEquals("\"abc123\"", first.etag)
        assertEquals(2, first.snapshot.children.size)

        val second = remote.pull(first.etag)
        assertEquals(ApiResult.Success(PullOutcome.NotModified), second)
        assertEquals(listOf<String?>(null, "\"abc123\""), ifNoneMatch.toList())
    }

    // ---- AuthRepository ---------------------------------------------------------------------------------------------

    private fun authRepository() = AuthRepository(remote, store)

    @Test
    fun loginStoresTokensAndTakesTheFamilyIdFromTheJwt() = runBlocking {
        val access = fakeJwt(clock.now().plusSeconds(900), familyId = "fam-from-jwt")
        val refresh = fakeJwt(clock.now().plusSeconds(86_400), familyId = "fam-from-jwt", type = "refresh")
        serve { json(200, """{"access_token":"$access","refresh_token":"$refresh","token_type":"bearer"}""") }

        val result = authRepository().login("mama@example.com", "correct horse")

        assertEquals(ApiResult.Success(Unit), result)
        assertEquals(SessionState.ParentDevice(ActiveMode.PARENT), store.currentState())
        assertEquals("fam-from-jwt", store.currentInfo().familyId)
        assertEquals(access, store.accessToken())
        assertEquals(refresh, store.refreshToken())
        assertTrue(seen.single().body.contains("mama@example.com"))
    }

    @Test
    fun aFailedLoginChangesNothing() = runBlocking {
        serve { json(401, """{"detail":"Incorrect email or password"}""") }

        val result = authRepository().login("mama@example.com", "nope")

        assertTrue(result is ApiResult.HttpError)
        assertEquals(SessionState.Unauthenticated, store.currentState())
    }

    @Test
    fun pairingStoresTheDeviceTokenAndMakesAKidDevice() = runBlocking {
        serve {
            json(201, """{"device_id":"dev-9","family_id":"fam-9","device_token":"tok-9"}""")
        }

        val result = authRepository().pairDevice("123456", "Kids tablet")

        assertTrue(result is ApiResult.Success)
        assertEquals(SessionState.KidDevice, store.currentState())
        assertEquals("tok-9", store.deviceToken())
        assertEquals("dev-9", store.currentInfo().deviceId)
        assertEquals("fam-9", store.currentInfo().familyId)
        assertTrue(seen.single().body.contains("Kids tablet"))
    }

    @Test
    fun anInvalidPairingCodeIsAnHttpErrorAndLeavesTheDeviceUnpaired() = runBlocking {
        serve { json(400, """{"detail":"Invalid or expired code"}""") }

        assertEquals(ApiResult.HttpError(400, "Invalid or expired code"), authRepository().pairDevice("000000", "tablet"))
        assertEquals(SessionState.Unauthenticated, store.currentState())
    }

    @Test
    fun logoutClearsSecretsButKeepsTheRestOfTheSession() = runBlocking {
        parentLoggedIn("access-1", "refresh-1")
        store.setFamilyTimezone("Asia/Karachi")

        authRepository().logout()

        assertEquals(SessionState.NeedsLogin, store.currentState())
        assertNull(store.accessToken())
        assertEquals("fam-1", store.currentInfo().familyId)
    }
}
