package com.bbro.kidtube.data.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

object KidTubeJson {
    /**
     * - ignoreUnknownKeys: a newer server may add fields.
     * - explicitNulls = false: null properties are omitted on encode and missing keys decode to null.
     *   (PATCH bodies that must send an explicit null are built as JsonObject, see [PatchField].)
     * - coerceInputValues: a null/invalid value for a property that has a default falls back to the default.
     * - encodeDefaults: request DTO defaults (e.g. watch_threshold) are sent explicitly.
     */
    val instance: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }
}

/** `YYYY-MM-DD`, the backend's date format (local dates are family-local). */
object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
}

/**
 * Parses what pydantic emits for datetimes: `...Z`, `...+00:00`, or (SQLite in dev drops tzinfo) a naive
 * `2026-11-05T20:00:00` which is UTC. Always writes `Z` form with millisecond precision (Python 3.10's
 * fromisoformat rejects 9-digit fractions).
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant = parseInstant(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toWireString())

    fun parseInstant(raw: String): Instant = try {
        OffsetDateTime.parse(raw).toInstant()
    } catch (_: java.time.format.DateTimeParseException) {
        LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC)
    }
}

/** ISO-8601 UTC with at most millisecond precision, e.g. `2026-11-05T20:00:00Z` or `...:00.123Z`. */
fun Instant.toWireString(): String = truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString()
