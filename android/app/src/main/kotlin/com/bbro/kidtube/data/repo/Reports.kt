package com.bbro.kidtube.data.repo

import com.bbro.kidtube.core.ConnectivityChecker
import com.bbro.kidtube.data.remote.ApiResult
import com.bbro.kidtube.data.remote.KidTubeRemote
import com.bbro.kidtube.data.remote.ReportDto
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** The three report periods; [wire] is the `range` query value. */
enum class ReportRange(val wire: String) { DAY("day"), WEEK("week"), MONTH("month") }

/** What identifies one report: the same key always asks the server the same question. */
data class ReportKey(val childId: String, val range: ReportRange, val anchor: LocalDate)

enum class ReportFailureKind { NO_CONNECTION, SERVER_UNREACHABLE, RATE_LIMITED, NOT_FOUND, UNKNOWN }

sealed interface ReportResult {
    data class Loaded(val report: ReportDto) : ReportResult

    data class Failed(val kind: ReportFailureKind) : ReportResult
}

/**
 * Parent-only reports, online only. The last successful report per [ReportKey] is kept in memory (not on disk: a report is
 * server data and the app restarts empty) so the screen can show it at once while it refreshes, or when a refresh fails.
 */
@Singleton
class ReportRepository @Inject constructor(
    private val remote: KidTubeRemote,
    private val connectivity: ConnectivityChecker,
) {
    private val cache = object : LinkedHashMap<ReportKey, ReportDto>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ReportKey, ReportDto>?) = size > MAX_CACHED
    }

    /** The last report that was fetched successfully for [key], if any. */
    fun cached(key: ReportKey): ReportDto? = synchronized(cache) { cache[key] }

    suspend fun fetch(key: ReportKey): ReportResult = when (val result = remote.getReport(key.childId, key.range.wire, key.anchor)) {
        is ApiResult.Success -> {
            synchronized(cache) { cache[key] = result.value }
            ReportResult.Loaded(result.value)
        }
        is ApiResult.NetworkError ->
            ReportResult.Failed(if (connectivity.isOnline()) ReportFailureKind.SERVER_UNREACHABLE else ReportFailureKind.NO_CONNECTION)
        is ApiResult.RateLimited -> ReportResult.Failed(ReportFailureKind.RATE_LIMITED)
        is ApiResult.HttpError -> ReportResult.Failed(
            when (result.code) {
                404 -> ReportFailureKind.NOT_FOUND
                0, 502, 503, 504 -> ReportFailureKind.SERVER_UNREACHABLE
                else -> ReportFailureKind.UNKNOWN
            },
        )
    }

    private companion object {
        const val MAX_CACHED = 40
    }
}
