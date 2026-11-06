package com.bbro.kidtube.data.remote

import com.bbro.kidtube.testing.resourceText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Deserialization contract: fixtures under src/test/resources/fixtures are written from the backend schema modules
 * (field names, nullability, enum values). If a backend schema changes, change the fixture and this test with it.
 */
class ContractTest {
    private val json = KidTubeJson.instance

    private inline fun <reified T> fixture(name: String): T =
        json.decodeFromString(resourceText("/fixtures/$name.json"))

    private inline fun <reified T> small(key: String): T = json.decodeFromJsonElement(
        json.parseToJsonElement(resourceText("/fixtures/small_responses.json")).jsonObject.getValue(key),
    )

    // ---- small responses -----------------------------------------------------------------------

    @Test
    fun authResponses() {
        val register = small<RegisterResponse>("register_response")
        assertEquals("22222222-2222-4222-8222-222222222222", register.familyId)
        assertEquals("mama@example.com", register.email)

        val tokens = small<TokenResponse>("token_response")
        assertEquals("aaa.bbb.ccc", tokens.accessToken)
        assertEquals("ddd.eee.fff", tokens.refreshToken)

        assertEquals("ggg.hhh.iii", small<AccessTokenResponse>("access_token_response").accessToken)
    }

    @Test
    fun childAndDeviceResponses() {
        val child = small<ChildDto>("child")
        assertEquals("Amna", child.name)
        assertEquals(45, child.dailyAppMinutes)

        val device = small<DeviceDto>("device")
        assertEquals(ServerDeviceRole.KID, device.role)
        assertEquals(Instant.parse("2026-11-05T10:00:00Z"), device.pairedAt)
        assertNull(device.revokedAt)

        val pair = small<PairResponse>("pair_response")
        assertEquals("opaque-device-token_abc", pair.deviceToken)
        assertEquals(Instant.parse("2026-11-05T10:10:00Z"), small<PairCodeResponse>("pair_code_response").expiresAt)
    }

    @Test
    fun unknownDeviceRoleFallsBackAndOffsetDatetimeParses() {
        val device = small<DeviceDto>("device_future_role")
        assertEquals(ServerDeviceRole.UNKNOWN, device.role)
        assertEquals(Instant.parse("2026-11-06T00:00:00Z"), device.revokedAt)
    }

    // ---- video / plan -----------------------------------------------------------------------------

    @Test
    fun video() {
        val video = fixture<VideoDto>("video")
        assertEquals(Platform.YOUTUBE, video.platform)
        assertEquals(VideoStatus.VERIFIED, video.status)
        assertEquals("dQw4w9WgXcQ", video.platformVideoId)
        assertEquals(212, video.durationSeconds)
        assertEquals(Instant.parse("2026-11-01T08:30:00Z"), video.addedAt)
    }

    @Test
    fun plan() {
        val plan = fixture<PlanDto>("plan")
        assertEquals(PaceType.MINUTES_PER_DAY, plan.paceType)
        assertEquals(31, plan.activeWeekdays)
        assertNull(plan.endDate)
        assertEquals(4, plan.version)
        assertEquals(2, plan.items.size)

        val first = plan.items[0]
        assertTrue(first.required)
        assertEquals(3, first.viewTarget)
        assertEquals(1, first.dailyCap)
        assertEquals(0.9, first.watchThreshold, 0.0)
        assertEquals(OnMiss.SHIFT, first.onMiss)
        assertEquals(PlanItemStatus.ACTIVE, first.status)
        assertEquals(LocalDate.of(2026, 11, 2), first.addedOn)

        val second = plan.items[1]
        assertNull(second.viewTarget)
        assertEquals(LocalDate.of(2026, 11, 9), second.anchorDate)
        assertEquals(OnMiss.DROP, second.onMiss)
        assertEquals(PlanItemStatus.COMPLETED, second.status)
        assertEquals(Platform.FACEBOOK, second.video.platform)
        assertNull(second.video.title)
    }

    // ---- sync -----------------------------------------------------------------------------------------

    @Test
    fun snapshotWithHistory() {
        val snapshot = fixture<SnapshotDto>("snapshot")
        assertEquals("Asia/Karachi", snapshot.timezone)
        assertEquals("0123456789abcdef0123456789abcdef", snapshot.snapshotVersion)
        assertEquals(Instant.parse("2026-11-05T10:00:00.123456Z"), snapshot.serverTime)

        val (amna, bilal) = snapshot.children
        assertEquals(2, amna.plan!!.items.size)
        assertEquals(LocalDate.of(2026, 12, 31), amna.plan!!.endDate)
        assertNull(bilal.plan)
        assertTrue(bilal.history.views.isEmpty() && bilal.history.watchSeconds.isEmpty())

        assertEquals(
            listOf(LocalDate.of(2026, 11, 3) to 1, LocalDate.of(2026, 11, 4) to 2),
            amna.history.views.map { it.localDate to it.count },
        )
        assertEquals("77777777-7777-4777-8777-777777777771", amna.history.views[0].planItemId)
        assertEquals(listOf(640, 212), amna.history.watchSeconds.map { it.seconds })
        assertEquals(listOf(900), amna.history.appSeconds.map { it.seconds })
        assertEquals(listOf(600), amna.history.timeExtensionSeconds.map { it.seconds })
    }

    @Test
    fun snapshotDatetimesWithAndWithoutOffsetAreUtc() {
        val videos = fixture<SnapshotDto>("snapshot").videos
        assertEquals(Instant.parse("2026-11-01T08:30:00Z"), videos[0].addedAt) // +00:00
        assertEquals(Instant.parse("2026-11-02T09:15:00Z"), videos[1].addedAt) // naive (SQLite dev) = UTC
    }

    @Test
    fun pushResult() {
        val result = fixture<PushResult>("push_result")
        assertEquals(PushVideoStatus.CREATED, result.videos[0].status)
        assertEquals(VideoStatus.PENDING, result.videos[0].videoStatus)
        assertTrue(result.videos[0].retryable)
        assertEquals(PushVideoStatus.EXISTING, result.videos[1].status)
        assertEquals(PushVideoStatus.REJECTED, result.videos[2].status)
        assertEquals("forbidden", result.videos[2].reason)
        assertNull(result.videos[2].videoId)
        assertEquals(EventResultStatus.ACCEPTED, result.events[0].status)
        assertEquals(EventResultStatus.REJECTED, result.events[1].status)
        assertEquals("unknown child", result.events[1].reason)
        assertFalse(result.events[1].retryable)
    }

    @Test
    fun eventBatchResult() {
        val result = fixture<EventBatchResult>("event_batch_result")
        assertEquals(
            listOf(EventResultStatus.ACCEPTED, EventResultStatus.DUPLICATE, EventResultStatus.REJECTED),
            result.results.map { it.status },
        )
        assertNull(result.results[2].id)
        assertEquals("invalid id", result.results[2].reason)
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
        val snapshot = fixture<SnapshotDto>("newer_server_snapshot")
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
        val result = fixture<PushResult>("newer_server_push_result")
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
