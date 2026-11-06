package com.bbro.kidtube.data.remote

import com.bbro.kidtube.core.JwtClaims
import com.bbro.kidtube.sync.PlacementErrors
import com.bbro.kidtube.testing.ContractFixtures
import com.bbro.kidtube.testing.resourceText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Deserialization contract: every shape here is decoded from the backend-GENERATED fixtures
 * (backend/tests/contract_fixtures, read at test time via [ContractFixtures]). The only hand-written inputs are the
 * forward-compatibility probes under test/resources/forward_compat (a NEWER server), which by definition cannot come
 * from today's backend.
 */
class ContractTest {
    private val json = KidTubeJson.instance

    private fun fx(n: Int) = ContractFixtures.load(n)

    private inline fun <reified T> forwardCompat(name: String): T =
        json.decodeFromString(resourceText("/forward_compat/$name.json"))

    private fun detailOf(n: Int): String = (fx(n).body as JsonObject).getValue("detail").jsonPrimitive.content

    // ---- auth / children / devices -------------------------------------------------------------

    @Test
    fun authResponses() {
        val register = fx(2).decodeBody<RegisterResponse>()
        assertEquals("00000000-0000-4000-8000-000000000002", register.familyId)
        assertEquals("mum@example.com", register.email)

        val tokens = fx(6).decodeBody<TokenResponse>()
        assertTrue(tokens.accessToken.isNotBlank() && tokens.refreshToken.isNotBlank())
        assertEquals("00000000-0000-4000-8000-000000000002", JwtClaims.parse(tokens.accessToken)?.familyId)

        assertTrue(fx(7).decodeBody<AccessTokenResponse>().accessToken.isNotBlank())
    }

    @Test
    fun childAndDeviceResponses() {
        val child = fx(14).decodeBody<ChildDto>()
        assertEquals("Ali", child.name)
        assertEquals("lion", child.avatar)
        assertNull(child.dailyAppMinutes)

        val devices = fx(41).decodeBody<List<DeviceDto>>()
        assertEquals(ServerDeviceRole.KID, devices[0].role)
        assertEquals(Instant.parse("2030-01-01T00:00:09Z"), devices[0].pairedAt) // microseconds + Z
        assertNull(devices[0].revokedAt)

        val pair = fx(40).decodeBody<PairResponse>()
        assertEquals("kidtube-device-token-placeholder-0000000001", pair.deviceToken)
        assertEquals(Instant.parse("2030-01-01T00:00:08Z"), fx(39).decodeBody<PairCodeResponse>().expiresAt)
    }

    @Test
    fun rateLimitedResponsesCarryRetryAfterAndADetailString() {
        for (n in listOf(83, 84)) {
            val f = fx(n)
            assertEquals(429, f.status)
            assertTrue(f.headers.getValue("retry-after").toLong() > 0)
            assertTrue(detailOf(n).isNotBlank())
        }
    }

    @Test
    fun unknownDeviceRoleFallsBackAndOffsetDatetimeParses() {
        val device = json.decodeFromString<DeviceDto>(
            """{"id":"d1","name":"Fridge","role":"appliance","paired_at":null,"revoked_at":"2026-11-06T00:00:00+00:00"}""",
        )
        assertEquals(ServerDeviceRole.UNKNOWN, device.role)
        assertEquals(Instant.parse("2026-11-06T00:00:00Z"), device.revokedAt)
    }

    // ---- video / plan -----------------------------------------------------------------------------

    @Test
    fun videos() {
        val video = fx(16).decodeBody<VideoDto>()
        assertEquals(Platform.YOUTUBE, video.platform)
        assertEquals(VideoStatus.VERIFIED, video.status)
        assertEquals("vid00000001", video.platformVideoId)
        assertEquals("Counting to ten", video.title)
        assertNull(video.durationSeconds)
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), video.addedAt)

        assertEquals(VideoStatus.UNAVAILABLE, fx(19).decodeBody<VideoDto>().status)
        val facebook = fx(20).decodeBody<VideoDto>()
        assertEquals(Platform.FACEBOOK, facebook.platform)
        assertEquals(VideoStatus.PENDING, facebook.status)
        assertEquals(VideoStatus.VERIFIED, fx(72).decodeBody<VideoDto>().status) // reverify: pending -> verified
        assertEquals(VideoStatus.UNAVAILABLE, fx(21).decodeBody<VideoDto>().status)
    }

    @Test
    fun plan() {
        val plan = fx(38).decodeBody<PlanDto>()
        assertEquals(PaceType.VIDEOS_PER_DAY, plan.paceType)
        assertEquals(31, plan.activeWeekdays)
        assertNull(plan.endDate)
        assertEquals(4, plan.items.size)

        val first = plan.items[0]
        assertTrue(first.required)
        assertEquals(1, first.viewTarget)
        assertNull(first.dailyCap)
        assertEquals(0.9, first.watchThreshold, 0.0)
        assertEquals(OnMiss.SHIFT, first.onMiss)
        assertEquals(PlanItemStatus.ACTIVE, first.status)
        assertEquals(LocalDate.of(2026, 1, 5), first.addedOn)
        assertEquals("Counting to ten", first.video.title)

        val anchored = plan.items.single { it.anchorDate != null }
        assertEquals(LocalDate.of(2026, 1, 12), anchored.anchorDate)
        assertEquals(OnMiss.DROP, anchored.onMiss)
        assertEquals(0.8, anchored.watchThreshold, 0.0)
    }

    @Test
    fun planItemCreateResponseAndErrors() {
        val item = fx(28).decodeBody<PlanItemDto>()
        assertEquals(0, item.position)
        assertTrue(item.required)
        assertEquals(1, fx(29).decodeBody<PlanItemDto>().dailyCap)

        assertEquals(409, fx(31).status)
        assertEquals(422, fx(32).status)
        assertEquals(422, fx(33).status)
    }

    /**
     * The sync engine tells "this child has no plan yet" from the other 404s by the backend's exact words, so they are
     * pinned here: if the backend text changes, this test fails and [PlacementErrors] must change with it.
     */
    @Test
    fun noPlanErrorTextIsPinned() {
        assertEquals(404, fx(24).status)
        assertEquals("This child has no plan yet", detailOf(24))
        assertEquals(PlacementErrors.NO_PLAN_DETAIL, detailOf(24))
        assertEquals("This video is already in the plan", detailOf(31))
        assertEquals("This video is unavailable", detailOf(32))
    }

    // ---- sync -----------------------------------------------------------------------------------------

    @Test
    fun snapshotWithHistory() {
        val f = fx(73)
        assertTrue(f.headers.getValue("etag").startsWith("\""))
        val snapshot = f.decodeBody<SnapshotDto>()
        assertEquals("Asia/Karachi", snapshot.timezone)
        assertEquals("00000000000000000000000000000001", snapshot.snapshotVersion)
        assertEquals(Instant.parse("2030-01-01T00:00:13Z"), snapshot.serverTime)
        assertEquals(listOf("Ali", "Sara", "Omar", "Noor", "Pip"), snapshot.children.map { it.name })

        val ali = snapshot.children[0]
        assertEquals(4, ali.plan!!.items.size)
        assertNull(ali.plan!!.endDate)
        assertEquals(listOf(LocalDate.of(2026, 1, 5) to 1), ali.history.views.map { it.localDate to it.count })
        assertEquals("00000000-0000-4000-8000-000000000017", ali.history.views[0].planItemId)
        assertEquals(listOf(96), ali.history.watchSeconds.map { it.seconds })
        assertEquals(listOf(1200), ali.history.appSeconds.map { it.seconds })
        assertEquals(listOf(900), ali.history.timeExtensionSeconds.map { it.seconds })

        assertTrue(snapshot.children[3].plan!!.items.isEmpty()) // Noor: a plan with no items
        assertNull(snapshot.children[4].plan) // Pip: no plan at all
        assertTrue(snapshot.children[4].history.views.isEmpty())
        assertEquals(9, snapshot.videos.size)
        assertTrue(snapshot.videos.any { it.status == VideoStatus.UNAVAILABLE })
        assertTrue(snapshot.videos.any { it.platform == Platform.FACEBOOK && it.status == VideoStatus.PENDING })
    }

    @Test
    fun notModifiedHasNoBodyAndTheSameEtag() {
        val f = fx(74)
        assertEquals(304, f.status)
        assertEquals(fx(73).headers.getValue("etag"), f.headers.getValue("etag"))
        assertEquals(fx(73).headers.getValue("etag"), f.requestHeaders.getValue("If-None-Match"))
    }

    @Test
    fun pushResultMixed() {
        val result = fx(69).decodeBody<PushResult>()
        assertEquals(
            listOf(PushVideoStatus.CREATED, PushVideoStatus.CREATED, PushVideoStatus.REJECTED, PushVideoStatus.EXISTING),
            result.videos.map { it.status },
        )
        assertEquals(VideoStatus.VERIFIED, result.videos[0].videoStatus)
        assertFalse(result.videos[0].retryable)
        assertEquals(VideoStatus.PENDING, result.videos[1].videoStatus) // slow oEmbed: created but still pending
        assertTrue(result.videos[1].retryable)
        assertNull(result.videos[2].videoId)
        assertEquals("Only YouTube and Facebook links are supported", result.videos[2].reason)
        assertEquals(
            listOf(EventResultStatus.ACCEPTED, EventResultStatus.REJECTED, EventResultStatus.DUPLICATE),
            result.events.map { it.status },
        )
        assertEquals("payload.seconds out of range", result.events[1].reason)
        assertTrue(result.events.none { it.retryable })
    }

    @Test
    fun pushAsKidDeviceRejectsVideosAsForbidden() {
        val result = fx(70).decodeBody<PushResult>()
        assertEquals(PushVideoStatus.REJECTED, result.videos.single().status)
        assertEquals("forbidden", result.videos.single().reason)
        assertEquals(EventResultStatus.ACCEPTED, result.events.single().status)
        assertEquals(422, fx(71).status)
    }

    @Test
    fun eventBatchResults() {
        val result = fx(58).decodeBody<EventBatchResult>()
        assertEquals(EventResultStatus.ACCEPTED, result.results[0].status)
        assertEquals("invalid payload.watched_pct", result.results[5].reason)
        assertEquals("unknown child", result.results[6].reason)
        assertTrue(fx(59).decodeBody<EventBatchResult>().results.all { it.status == EventResultStatus.DUPLICATE })
        assertEquals(EventResultStatus.REJECTED, fx(60).decodeBody<EventBatchResult>().results[1].status)
    }

    // ---- what the app sends matches the generated requests -----------------------------------------------

    @Test
    fun pushBodyEncodesLikeTheGeneratedRequest() {
        val sent = fx(69).requestBody!!.jsonObject
        val decoded = json.decodeFromJsonElement(PushBody.serializer(), sent)
        assertEquals(4, decoded.videos.size)
        assertEquals(3, decoded.events.size)
        assertEquals(sent["videos"], json.encodeToJsonElement(PushBody.serializer(), decoded).jsonObject["videos"])
        val event = json.encodeToJsonElement(EventDto.serializer(), decoded.events[0]).jsonObject
        assertEquals(sent["events"]!!.jsonArray[0].jsonObject.keys, event.keys)
    }

    @Test
    fun planItemCreateEncodesLikeTheGeneratedRequestPlusExplicitDefaults() {
        val sent = fx(28).requestBody!!.jsonObject // {required, video_id, view_target}
        val body = json.encodeToJsonElement(
            PlanItemCreate.serializer(),
            PlanItemCreate(videoId = sent.getValue("video_id").jsonPrimitive.content, required = true, viewTarget = 1),
        ).jsonObject
        assertTrue(body.keys.containsAll(sent.keys))
        assertEquals(sent.keys + setOf("on_miss", "watch_threshold"), body.keys)
    }

    // ---- a newer server must not crash an older app -----------------------------------------------------

    @Test
    fun extraFieldsAreIgnored() {
        val child = json.decodeFromString<ChildDto>(
            """{"id":"c1","name":"Amna","avatar":null,"daily_app_minutes":null,"surprise":{"deep":[1,2]}}""",
        )
        assertEquals("Amna", child.name)
    }

    @Test
    fun newerServerSnapshotDecodesWithUnknownEnums() {
        val snapshot = forwardCompat<SnapshotDto>("newer_server_snapshot")
        val plan = snapshot.children.single().plan!!
        assertEquals(PaceType.UNKNOWN, plan.paceType)
        assertEquals(OnMiss.UNKNOWN, plan.items[0].onMiss)
        assertEquals(PlanItemStatus.UNKNOWN, plan.items[0].status)
        assertEquals(Platform.UNKNOWN, plan.items[0].video.platform)
        assertEquals(VideoStatus.UNKNOWN, plan.items[0].video.status)
        assertEquals(Platform.UNKNOWN, snapshot.videos.single().platform)
        assertEquals(VideoStatus.UNKNOWN, snapshot.videos.single().status)
        assertTrue(snapshot.children.single().history.views.isEmpty())
    }

    @Test
    fun newerServerPushResultDecodesWithUnknownStatuses() {
        val result = forwardCompat<PushResult>("newer_server_push_result")
        assertEquals(PushVideoStatus.UNKNOWN, result.videos.single().status)
        assertEquals(VideoStatus.UNKNOWN, result.videos.single().videoStatus)
        assertEquals(EventResultStatus.UNKNOWN, result.events.single().status)
    }

    @Test
    fun wireEnumFallbackHelper() {
        assertEquals(Platform.FACEBOOK, Platform.fromWire("facebook"))
        assertEquals(Platform.UNKNOWN, Platform.fromWire("tiktok"))
        assertEquals(EventType.TIME_EXTENSION, EventType.fromWire("time_extension"))
        assertEquals(EventType.UNKNOWN, EventType.fromWire("hologram"))
    }

    // ---- what the app sends --------------------------------------------------------------------------------

    @Test
    fun planItemCreateSendsExplicitDefaultsAndOmitsNulls() {
        val body = json.encodeToJsonElement(PlanItemCreate.serializer(), PlanItemCreate(videoId = "v1")).jsonObject
        assertEquals(setOf("video_id", "required", "on_miss", "watch_threshold"), body.keys)
        assertEquals("shift", body["on_miss"]!!.toString().trim('"'))
        assertEquals("0.9", body["watch_threshold"].toString())
    }

    @Test
    fun planWriteOmitsMaxExtraWhenNullSoTheServerPicksItsDefault() {
        val write = PlanWrite(PaceType.MINUTES_PER_DAY, 20, 31, null, LocalDate.of(2026, 11, 2), null)
        val body = json.encodeToJsonElement(PlanWrite.serializer(), write).jsonObject
        assertFalse("max_extra_per_day" in body)
        assertFalse("end_date" in body)
        assertEquals("2026-11-02", body["start_date"].toString().trim('"'))
        assertEquals("minutes_per_day", body["pace_type"].toString().trim('"'))
    }

    @Test
    fun patchBodiesDistinguishAbsentFromExplicitNull() {
        val childPatch = ChildPatch(avatar = PatchField.Value(null), dailyAppMinutes = PatchField.Value(30)).toJson()
        assertEquals(setOf("avatar", "daily_app_minutes"), childPatch.keys) // name absent: unchanged
        assertEquals("null", childPatch["avatar"].toString())
        assertEquals("30", childPatch["daily_app_minutes"].toString())

        val itemPatch = PlanItemPatch(
            viewTarget = PatchField.Value(null),
            anchorDate = PatchField.Value(LocalDate.of(2026, 11, 9)),
            status = PatchField.Value(PlanItemStatus.DROPPED),
            required = PatchField.Value(true),
        ).toJson()
        assertEquals(setOf("view_target", "anchor_date", "status", "required"), itemPatch.keys)
        assertEquals("null", itemPatch["view_target"].toString())
        assertEquals("\"2026-11-09\"", itemPatch["anchor_date"].toString())
        assertEquals("\"dropped\"", itemPatch["status"].toString())
        assertEquals("true", itemPatch["required"].toString())

        assertTrue(PlanItemPatch().toJson().isEmpty())
    }

    @Test
    fun eventDtoMatchesTheServerValidator() {
        val payload: JsonObject = buildJsonObject {
            put("plan_item_id", "77777777-7777-4777-8777-777777777771")
            put("watched_pct", 0.95)
        }
        val dto = EventDto(
            id = "88888888-8888-4888-8888-888888888881",
            childId = "33333333-3333-4333-8333-333333333333",
            type = EventType.VIEW_COMPLETE,
            occurredAt = Instant.parse("2026-11-05T20:00:00.123456789Z"),
            localDate = LocalDate.of(2026, 11, 6),
            payload = payload,
        )
        val body = json.encodeToJsonElement(EventDto.serializer(), dto).jsonObject
        assertEquals(setOf("id", "child_id", "type", "occurred_at", "local_date", "payload"), body.keys)
        assertEquals("view_complete", body["type"].toString().trim('"'))
        // Python 3.10 fromisoformat rejects 9 fractional digits: milliseconds at most, Z suffix.
        assertEquals("2026-11-05T20:00:00.123Z", body["occurred_at"].toString().trim('"'))
        assertEquals("2026-11-06", body["local_date"].toString().trim('"'))
        assertEquals(payload, body["payload"])
    }
}
