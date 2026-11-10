package com.bbro.kidtube.testing

import com.bbro.kidtube.core.ConnectivityChecker
import com.bbro.kidtube.core.FakeClock
import com.bbro.kidtube.core.PinHasher
import com.bbro.kidtube.core.PinStore
import com.bbro.kidtube.core.ServerAddressStore
import com.bbro.kidtube.core.ServerConfig
import com.bbro.kidtube.data.remote.ApiWiring
import com.bbro.kidtube.data.remote.KidTubeJson
import com.bbro.kidtube.data.remote.StoreBaseUrlSource
import com.bbro.kidtube.data.repo.AuthRepository
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.security.SecureRandom

/**
 * The production auth wiring (ApiWiring with the dynamic base URL, AuthRepository, ServerAddressStore, PinStore over ONE
 * DataStore, like Hilt does) against a MockWebServer. Error bodies and headers come from the backend-generated contract
 * fixtures via [respond].
 */
class AuthRig(allowCleartext: Boolean = true) {
    val server = MockWebServer().apply { start() }
    val session = SessionHarness()
    val clock = FakeClock()
    val config = ServerConfig(server.url("/").toString(), allowCleartext)
    val serverAddress = ServerAddressStore(session.backing, config)
    val stack = ApiWiring.create(config.defaultBaseUrl, KidTubeJson.instance, session.store, clock, StoreBaseUrlSource(serverAddress))
    val auth = AuthRepository(stack.remote, session.store)
    val pinStore = PinStore(session.backing, session.cipher, clock, PinHasher(SecureRandom(), 1_000))

    var online = true
    val connectivity = ConnectivityChecker { online }

    /** Queues the fixture's status, headers and body as the next response. */
    fun respond(fixture: ContractFixture) {
        server.enqueue(response(fixture.status, fixture.bodyText, fixture.headers))
    }

    fun respond(status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        server.enqueue(response(status, body, headers))
    }

    private fun response(status: Int, body: String, headers: Map<String, String>): MockResponse =
        MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body)
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()

    fun close() {
        runCatching { server.close() }
        session.close()
    }
}
