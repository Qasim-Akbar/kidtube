package com.bbro.kidtube.data.entity

import androidx.room.TypeConverter
import com.bbro.kidtube.data.remote.EventType
import com.bbro.kidtube.data.remote.OnMiss
import com.bbro.kidtube.data.remote.PaceType
import com.bbro.kidtube.data.remote.Platform
import com.bbro.kidtube.data.remote.PlanItemStatus
import com.bbro.kidtube.data.remote.VideoStatus
import java.time.Instant
import java.time.LocalDate

/** Instants are stored as epoch millis, dates as ISO `YYYY-MM-DD`, enums by wire value (unknown -> UNKNOWN). */
class Converters {
    @TypeConverter fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()
    @TypeConverter fun longToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter fun dateToString(value: LocalDate?): String? = value?.toString()
    @TypeConverter fun stringToDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter fun platformToString(v: Platform): String = v.wire
    @TypeConverter fun stringToPlatform(v: String): Platform = Platform.fromWire(v)

    @TypeConverter fun videoStatusToString(v: VideoStatus): String = v.wire
    @TypeConverter fun stringToVideoStatus(v: String): VideoStatus = VideoStatus.fromWire(v)

    @TypeConverter fun paceTypeToString(v: PaceType): String = v.wire
    @TypeConverter fun stringToPaceType(v: String): PaceType = PaceType.fromWire(v)

    @TypeConverter fun onMissToString(v: OnMiss): String = v.wire
    @TypeConverter fun stringToOnMiss(v: String): OnMiss = OnMiss.fromWire(v)

    @TypeConverter fun planItemStatusToString(v: PlanItemStatus): String = v.wire
    @TypeConverter fun stringToPlanItemStatus(v: String): PlanItemStatus = PlanItemStatus.fromWire(v)

    @TypeConverter fun eventTypeToString(v: EventType): String = v.wire
    @TypeConverter fun stringToEventType(v: String): EventType = EventType.fromWire(v)

    // Local-only enums: a value this app version wrote itself, so an unknown one is a bug and should fail loudly.
    @TypeConverter fun localEventStateToString(v: LocalEventState): String = v.wire
    @TypeConverter fun stringToLocalEventState(v: String): LocalEventState = LocalEventState.entries.first { it.wire == v }

    @TypeConverter fun outboxVideoStateToString(v: OutboxVideoState): String = v.wire
    @TypeConverter fun stringToOutboxVideoState(v: String): OutboxVideoState = OutboxVideoState.entries.first { it.wire == v }

    @TypeConverter fun placementStateToString(v: PlacementState): String = v.wire
    @TypeConverter fun stringToPlacementState(v: String): PlacementState = PlacementState.entries.first { it.wire == v }

    @TypeConverter fun historyKindToString(v: HistoryKind): String = v.wire
    @TypeConverter fun stringToHistoryKind(v: String): HistoryKind = HistoryKind.entries.first { it.wire == v }
}
