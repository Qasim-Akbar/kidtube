package com.bbro.kidtube.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDate

/**
 * JSON codec for the backend's engine vectors (backend/tests/engine_vectors/{feed,projection}/NN_name.json), the
 * counterpart of backend/app/engine/runner.py + the to_dict() methods. Test-only: production code never sees JSON.
 * Strict on purpose: a missing key or a wrong type throws instead of defaulting.
 */
class EngineVector(val kind: String, val file: String, val name: String, val input: JsonObject, val expected: JsonObject) {
    /** e.g. `feed/36_catch_up_one_missed_day.json`, used as the test name. */
    val id: String get() = "$kind/$file"

    /** Parameterized uses this for the test name: `run[feed/36_catch_up_one_missed_day.json]`. */
    override fun toString(): String = id
}

object EngineVectors {
    const val PROPERTY = "engine.vectors.dir"
    val KINDS = listOf("feed", "projection")

    /** The vectors directory, or null when the system property is missing / not a directory (the guard test reports it). */
    fun dirOrNull(): File? = System.getProperty(PROPERTY)?.let(::File)?.takeIf { it.isDirectory }

    fun loadAll(): List<EngineVector> {
        val dir = dirOrNull() ?: return emptyList()
        return KINDS.flatMap { kind ->
            File(dir, kind).listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }.map { f ->
                val root = Json.parseToJsonElement(f.readText()).jsonObject
                EngineVector(kind, f.name, root.getValue("name").jsonPrimitive.content, root.getValue("input").jsonObject, root.getValue("expected").jsonObject)
            }
        }
    }

    // ---- input -> engine types (runner.py: run_feed / run_projection) ----

    fun runFeed(i: JsonObject): JsonObject = buildFeed(
        plan(i.getValue("plan").jsonObject),
        i.getValue("items").jsonArray.map { item(it.jsonObject) },
        i.optList("views").map { view(it.jsonObject) },
        i.optList("seconds").map { SecondsRecord(date(it.jsonObject, "local_date"), it.jsonObject.getValue("seconds").jsonPrimitive.int) },
        i.optList("app_seconds").map { AppSecondsRecord(date(it.jsonObject, "local_date"), it.jsonObject.getValue("seconds").jsonPrimitive.int) },
        limits(i["limits"]),
        date(i, "today"),
        i["default_duration_seconds"]?.jsonPrimitive?.int ?: DEFAULT_DURATION_SECONDS,
    ).toJson()

    fun runProjection(i: JsonObject): JsonObject = project(
        plan(i.getValue("plan").jsonObject),
        i.getValue("items").jsonArray.map { item(it.jsonObject) },
        i.optList("views").map { view(it.jsonObject) },
        i.optList("seconds").map { SecondsRecord(date(it.jsonObject, "local_date"), it.jsonObject.getValue("seconds").jsonPrimitive.int) },
        date(i, "today"),
        i.getValue("days").jsonPrimitive.int,
        i["default_duration_seconds"]?.jsonPrimitive?.int ?: DEFAULT_DURATION_SECONDS,
    ).toJson()

    private fun JsonObject.optList(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

    private fun date(o: JsonObject, key: String): LocalDate = LocalDate.parse(o.getValue(key).jsonPrimitive.content)
    private fun optDate(o: JsonObject, key: String): LocalDate? = o[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let(LocalDate::parse)
    private fun optInt(o: JsonObject, key: String): Int? = o[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int

    private fun plan(o: JsonObject) = PlanConfig(
        PaceType.fromWire(o.getValue("pace_type").jsonPrimitive.content),
        o.getValue("pace_value").jsonPrimitive.int,
        o.getValue("active_weekdays").jsonPrimitive.int,
        o.getValue("max_extra_per_day").jsonPrimitive.int,
        date(o, "start_date"),
        optDate(o, "end_date"),
    )

    private fun item(o: JsonObject) = ItemState(
        id = o.getValue("id").jsonPrimitive.content,
        videoId = o.getValue("video_id").jsonPrimitive.content,
        position = o.getValue("position").jsonPrimitive.int,
        required = o.getValue("required").jsonPrimitive.boolean,
        viewTarget = optInt(o, "view_target"),
        dailyCap = optInt(o, "daily_cap"),
        anchorDate = optDate(o, "anchor_date"),
        onMiss = OnMiss.fromWire(o.getValue("on_miss").jsonPrimitive.content),
        status = ItemStatus.fromWire(o.getValue("status").jsonPrimitive.content),
        durationSeconds = optInt(o, "duration_seconds"),
        addedOn = date(o, "added_on"),
    )

    private fun view(o: JsonObject) = ViewRecord(o.getValue("item_id").jsonPrimitive.content, date(o, "local_date"))

    private fun limits(e: JsonElement?): ChildLimits {
        val o = (e as? JsonObject) ?: return ChildLimits()
        return ChildLimits(optInt(o, "daily_app_minutes"), o["app_time_extra_seconds"]?.jsonPrimitive?.int ?: 0)
    }

    // ---- engine types -> JSON (types.py: to_dict) ----

    private fun str(s: String?): JsonElement = if (s == null) JsonNull else JsonPrimitive(s)
    private fun num(n: Int?): JsonElement = if (n == null) JsonNull else JsonPrimitive(n)
    private fun strings(l: List<String>) = JsonArray(l.map { JsonPrimitive(it) })

    fun FeedResult.toJson(): JsonObject = JsonObject(
        mapOf(
            "status" to JsonPrimitive(status.wire),
            "items" to JsonArray(
                items.map {
                    JsonObject(
                        mapOf(
                            "item_id" to JsonPrimitive(it.itemId), "video_id" to JsonPrimitive(it.videoId),
                            "locked" to JsonPrimitive(it.locked), "required" to JsonPrimitive(it.required),
                        ),
                    )
                },
            ),
            "quota_today" to num(quotaToday),
            "views_today" to num(viewsToday),
            "remaining_videos" to num(remainingVideos),
            "remaining_seconds" to num(remainingSeconds),
            "backlog" to num(backlog),
            "skipped_item_ids" to strings(skippedItemIds),
            "dropped_item_ids" to strings(droppedItemIds),
        ),
    )

    fun Projection.toJson(): JsonObject = JsonObject(
        mapOf(
            "days" to JsonArray(
                days.map { d ->
                    JsonObject(
                        mapOf(
                            "date" to JsonPrimitive(d.date.toString()),
                            "status" to JsonPrimitive(d.status.wire),
                            "views" to JsonArray(
                                d.views.map { JsonObject(mapOf("item_id" to JsonPrimitive(it.itemId), "video_id" to JsonPrimitive(it.videoId))) },
                            ),
                        ),
                    )
                },
            ),
            "finish_date" to str(finishDate?.toString()),
            "remaining_finite_views" to num(remainingFiniteViews),
            "unscheduled_item_ids" to strings(unscheduledItemIds),
            "excluded_unlimited_item_ids" to strings(excludedUnlimitedItemIds),
        ),
    )
}
