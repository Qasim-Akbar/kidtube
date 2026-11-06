package com.bbro.kidtube.data.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** An enum with a stable lowercase wire value (what the backend sends/accepts and what Room stores). */
interface WireEnum {
    val wire: String
}

/**
 * Serializer that maps an unrecognised wire value to [unknown] instead of throwing, so a newer server that
 * adds an enum value never crashes an older app. [unknown] is written back as its own wire value ("unknown").
 */
abstract class WireEnumSerializer<E>(
    serialName: String,
    private val values: Array<E>,
    private val unknown: E,
) : KSerializer<E> where E : Enum<E>, E : WireEnum {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): E {
        val raw = decoder.decodeString()
        return values.firstOrNull { it.wire == raw } ?: unknown
    }

    override fun serialize(encoder: Encoder, value: E) = encoder.encodeString(value.wire)
}

/** Parse a stored/wire string, falling back to [unknown]. */
fun <E> Array<E>.fromWire(raw: String, unknown: E): E where E : Enum<E>, E : WireEnum =
    firstOrNull { it.wire == raw } ?: unknown

@Serializable(with = PlatformSerializer::class)
enum class Platform(override val wire: String) : WireEnum {
    YOUTUBE("youtube"), FACEBOOK("facebook"), UNKNOWN("unknown");

    companion object {
        fun fromWire(raw: String): Platform = entries.toTypedArray().fromWire(raw, UNKNOWN)
    }
}

object PlatformSerializer : WireEnumSerializer<Platform>("Platform", Platform.entries.toTypedArray(), Platform.UNKNOWN)

@Serializable(with = VideoStatusSerializer::class)
enum class VideoStatus(override val wire: String) : WireEnum {
    PENDING("pending"), VERIFIED("verified"), UNAVAILABLE("unavailable"), UNKNOWN("unknown");

    companion object {
        fun fromWire(raw: String): VideoStatus = entries.toTypedArray().fromWire(raw, UNKNOWN)
    }
}

object VideoStatusSerializer :
    WireEnumSerializer<VideoStatus>("VideoStatus", VideoStatus.entries.toTypedArray(), VideoStatus.UNKNOWN)

@Serializable(with = PaceTypeSerializer::class)
enum class PaceType(override val wire: String) : WireEnum {
    VIDEOS_PER_DAY("videos_per_day"), MINUTES_PER_DAY("minutes_per_day"), UNKNOWN("unknown");

    companion object {
        fun fromWire(raw: String): PaceType = entries.toTypedArray().fromWire(raw, UNKNOWN)
    }
}

object PaceTypeSerializer : WireEnumSerializer<PaceType>("PaceType", PaceType.entries.toTypedArray(), PaceType.UNKNOWN)

@Serializable(with = OnMissSerializer::class)
enum class OnMiss(override val wire: String) : WireEnum {
    SHIFT("shift"), SKIP("skip"), DROP("drop"), UNKNOWN("unknown");

    companion object {
        fun fromWire(raw: String): OnMiss = entries.toTypedArray().fromWire(raw, UNKNOWN)
    }
}

object OnMissSerializer : WireEnumSerializer<OnMiss>("OnMiss", OnMiss.entries.toTypedArray(), OnMiss.UNKNOWN)

@Serializable(with = PlanItemStatusSerializer::class)
enum class PlanItemStatus(override val wire: String) : WireEnum {
    ACTIVE("active"), COMPLETED("completed"), DROPPED("dropped"), UNKNOWN("unknown");

    companion object {
        fun fromWire(raw: String): PlanItemStatus = entries.toTypedArray().fromWire(raw, UNKNOWN)
    }
}

object PlanItemStatusSerializer :
    WireEnumSerializer<PlanItemStatus>("PlanItemStatus", PlanItemStatus.entries.toTypedArray(), PlanItemStatus.UNKNOWN)

/** Device role as the backend names it (`parent` | `kid`). Not the same type as core.DeviceRole (local setup choice). */
@Serializable(with = ServerDeviceRoleSerializer::class)
enum class ServerDeviceRole(override val wire: String) : WireEnum {
    PARENT("parent"), KID("kid"), UNKNOWN("unknown")
}

object ServerDeviceRoleSerializer : WireEnumSerializer<ServerDeviceRole>(
    "ServerDeviceRole", ServerDeviceRole.entries.toTypedArray(), ServerDeviceRole.UNKNOWN,
)

@Serializable(with = EventTypeSerializer::class)
enum class EventType(override val wire: String) : WireEnum {
    VIEW_COMPLETE("view_complete"),
    WATCH_PROGRESS("watch_progress"),
    APP_SESSION("app_session"),
    PLAYBACK_ERROR("playback_error"),
    TIME_EXTENSION("time_extension"), // parent-only on the server
    UNKNOWN("unknown");

    companion object {
        fun fromWire(raw: String): EventType = entries.toTypedArray().fromWire(raw, UNKNOWN)
    }
}

object EventTypeSerializer : WireEnumSerializer<EventType>("EventType", EventType.entries.toTypedArray(), EventType.UNKNOWN)

/** Per-item result of POST /events and /sync/push. UNKNOWN must be treated as "not acknowledged". */
@Serializable(with = EventResultStatusSerializer::class)
enum class EventResultStatus(override val wire: String) : WireEnum {
    ACCEPTED("accepted"), DUPLICATE("duplicate"), REJECTED("rejected"), UNKNOWN("unknown")
}

object EventResultStatusSerializer : WireEnumSerializer<EventResultStatus>(
    "EventResultStatus", EventResultStatus.entries.toTypedArray(), EventResultStatus.UNKNOWN,
)

/** Per-video result of POST /sync/push. */
@Serializable(with = PushVideoStatusSerializer::class)
enum class PushVideoStatus(override val wire: String) : WireEnum {
    CREATED("created"), EXISTING("existing"), REJECTED("rejected"), UNKNOWN("unknown")
}

object PushVideoStatusSerializer : WireEnumSerializer<PushVideoStatus>(
    "PushVideoStatus", PushVideoStatus.entries.toTypedArray(), PushVideoStatus.UNKNOWN,
)
