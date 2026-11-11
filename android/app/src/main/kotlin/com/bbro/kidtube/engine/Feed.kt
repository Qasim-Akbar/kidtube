package com.bbro.kidtube.engine

import java.time.LocalDate

// Kotlin port of backend/app/engine/feed.py (SPEC section 7). Pure: no I/O, no clock, no timezones.
// Deterministic: ordering is always (anchored-today first, position, id).
//
// Deliberate differences from Python:
//  - Ids are compared with String.compareTo (UTF-16 order). Python compares code points. They only differ for ids with
//    characters outside the BMP; ids are UUIDs or short ASCII strings, so the order is identical in practice.
//  - Python's weekday() is Monday=0; here it is `dayOfWeek.value - 1` (java.time Monday=1).
//  - sorted() in Python is stable and so is sortedWith in Kotlin, so ties behave the same.

const val DEFAULT_DURATION_SECONDS = 300
const val CATCH_UP_WINDOW_DAYS = 14

// ---------------------------------------------------------------- calendar

/** Monday = 0 .. Sunday = 6, the bit position in [PlanConfig.activeWeekdays]. */
internal fun weekdayIndex(d: LocalDate): Int = d.dayOfWeek.value - 1

private fun weekdayActive(plan: PlanConfig, d: LocalDate): Boolean = plan.activeWeekdays and (1 shl weekdayIndex(d)) != 0

fun isWithinDates(plan: PlanConfig, d: LocalDate): Boolean =
    !plan.startDate.isAfter(d) && (plan.endDate == null || !d.isAfter(plan.endDate))

fun isActiveDay(plan: PlanConfig, d: LocalDate): Boolean = isWithinDates(plan, d) && weekdayActive(plan, d)

/** "outside_dates" / "day_off" when the plan does not run on [d], else null. */
fun nonActiveDayStatus(plan: PlanConfig, d: LocalDate): FeedStatus? {
    if (!isWithinDates(plan, d)) return FeedStatus.OUTSIDE_DATES
    if (!weekdayActive(plan, d)) return FeedStatus.DAY_OFF
    return null
}

// ---------------------------------------------------------------- items

/** Finite number of views that completes the item; a required item with no target needs 1. */
fun targetViews(item: ItemState): Int? = item.viewTarget ?: if (item.required) 1 else null

fun isUnlimited(item: ItemState): Boolean = item.status == ItemStatus.ACTIVE && targetViews(item) == null

fun isComplete(item: ItemState, viewsTotal: Map<String, Int>): Boolean {
    if (item.status == ItemStatus.COMPLETED) return true
    val n = targetViews(item)
    return n != null && (viewsTotal[item.id] ?: 0) >= n
}

/** Python's (0 if anchor == today else 1, position, id) tuple. */
internal fun sortComparator(today: LocalDate): Comparator<ItemState> =
    compareBy<ItemState> { if (it.anchorDate == today) 0 else 1 }.thenBy { it.position }.thenBy { it.id }

internal val positionThenId: Comparator<ItemState> = compareBy<ItemState> { it.position }.thenBy { it.id }

data class Classified(
    /** Active, incomplete, not excluded by on_miss. */
    val pending: List<ItemState>,
    val skipped: List<ItemState>,
    val dropped: List<ItemState>,
)

fun classify(items: List<ItemState>, viewsTotal: Map<String, Int>, today: LocalDate): Classified {
    val pending = mutableListOf<ItemState>()
    val skipped = mutableListOf<ItemState>()
    val dropped = mutableListOf<ItemState>()
    for (item in items) {
        if (item.status != ItemStatus.ACTIVE || isComplete(item, viewsTotal)) continue
        if (item.anchorDate != null && item.anchorDate.isBefore(today)) {
            if (item.onMiss == OnMiss.SKIP) {
                skipped += item
                continue
            }
            if (item.onMiss == OnMiss.DROP) {
                dropped += item
                continue
            }
        }
        pending += item // includes future-anchored and daily-capped items
    }
    return Classified(pending, skipped, dropped)
}

// ---------------------------------------------------------------- counting

data class ViewCounts(val total: MutableMap<String, Int>, val today: MutableMap<String, Int>)

/** (views_total, views_today) per item id. Unknown items and future-dated records are ignored. */
fun countViews(items: List<ItemState>, views: List<ViewRecord>, today: LocalDate): ViewCounts {
    val known = items.mapTo(HashSet()) { it.id }
    val total = HashMap<String, Int>()
    val todayBy = HashMap<String, Int>()
    for (v in views) {
        if (v.itemId !in known || v.localDate.isAfter(today)) continue
        total[v.itemId] = (total[v.itemId] ?: 0) + 1
        if (v.localDate == today) todayBy[v.itemId] = (todayBy[v.itemId] ?: 0) + 1
    }
    return ViewCounts(total, todayBy)
}

/** Missed pace over the last 14 days before [today] (videos, or seconds in minutes mode). */
fun computeBacklog(
    plan: PlanConfig,
    items: List<ItemState>,
    views: List<ViewRecord>,
    seconds: List<SecondsRecord>,
    today: LocalDate,
): Int {
    val live = items.filter { it.status != ItemStatus.DROPPED }
    if (live.isEmpty()) return 0
    val lo = maxOf(today.minusDays(CATCH_UP_WINDOW_DAYS.toLong()), plan.startDate, live.minOf { it.addedOn })
    val hi = today.minusDays(1)
    if (lo.isAfter(hi)) return 0
    val dayCount = java.time.temporal.ChronoUnit.DAYS.between(lo, hi).toInt() + 1
    val activeDays = (0 until dayCount).count { isActiveDay(plan, lo.plusDays(it.toLong())) }
    val expected: Int
    val done: Int
    if (plan.paceType == PaceType.VIDEOS_PER_DAY) {
        val known = items.mapTo(HashSet()) { it.id }
        expected = plan.paceValue * activeDays
        done = views.count { it.itemId in known && !it.localDate.isBefore(lo) && !it.localDate.isAfter(hi) }
    } else {
        expected = plan.paceValue * 60 * activeDays
        done = seconds.filter { !it.localDate.isBefore(lo) && !it.localDate.isAfter(hi) }.sumOf { it.seconds }
    }
    return maxOf(0, expected - done)
}

// ---------------------------------------------------------------- one day

/** Feed for one day from pre-aggregated counters. [extra] is the already-capped catch-up (units of quota). */
fun evaluateDay(
    plan: PlanConfig,
    items: List<ItemState>,
    viewsTotal: Map<String, Int>,
    viewsTodayByItem: Map<String, Int>,
    secondsToday: Int,
    timeUp: Boolean,
    today: LocalDate,
    backlog: Int,
    extra: Int,
    defaultDurationSeconds: Int,
): FeedResult {
    val videos = plan.paceType == PaceType.VIDEOS_PER_DAY
    val viewsToday = viewsTodayByItem.values.sum()

    val off = nonActiveDayStatus(plan, today)
    if (off != null) {
        return FeedResult(off, emptyList(), 0, viewsToday, if (videos) 0 else null, if (videos) null else 0, 0, emptyList(), emptyList())
    }

    val quota = (if (videos) plan.paceValue else plan.paceValue * 60) + extra
    val remainingVideos = if (videos) maxOf(0, quota - viewsToday) else null
    val remainingSeconds = if (videos) null else maxOf(0, quota - secondsToday)
    val remaining = (if (videos) remainingVideos else remainingSeconds)!!

    val cls = classify(items, viewsTotal, today)
    val skippedIds = cls.skipped.sortedWith(positionThenId).map { it.id }
    val droppedIds = cls.dropped.sortedWith(positionThenId).map { it.id }

    fun result(status: FeedStatus, feed: List<FeedItem> = emptyList()) = FeedResult(
        status, feed, quota, viewsToday, remainingVideos, remainingSeconds, backlog, skippedIds, droppedIds,
    )

    if (items.none { it.status != ItemStatus.DROPPED }) return result(FeedStatus.EMPTY)
    if (cls.pending.isEmpty()) return result(FeedStatus.ALL_DONE)
    if (timeUp) return result(FeedStatus.TIME_UP)

    val eligible = cls.pending
        .filter {
            !(it.anchorDate != null && it.anchorDate.isAfter(today)) &&
                !(it.dailyCap != null && (viewsTodayByItem[it.id] ?: 0) >= it.dailyCap)
        }
        .sortedWith(sortComparator(today))
    if (remaining <= 0 || eligible.isEmpty()) return result(FeedStatus.DONE_TODAY)

    for (item in eligible) {
        if (item.required) { // required-first: only the first one, locked
            return result(FeedStatus.OK, listOf(FeedItem(item.id, item.videoId, locked = true, required = true)))
        }
    }

    val picked = mutableListOf<FeedItem>()
    var accumulated = 0
    for (item in eligible) {
        if (videos) {
            if (picked.size >= remaining) break
        } else {
            if (accumulated >= remaining) break
            accumulated += item.durationSeconds ?: defaultDurationSeconds
        }
        picked += FeedItem(item.id, item.videoId, locked = false, required = false)
    }
    return result(FeedStatus.OK, picked)
}

fun buildFeed(
    plan: PlanConfig,
    items: List<ItemState>,
    views: List<ViewRecord>,
    seconds: List<SecondsRecord>,
    appSeconds: List<AppSecondsRecord>,
    limits: ChildLimits,
    today: LocalDate,
    defaultDurationSeconds: Int = DEFAULT_DURATION_SECONDS,
): FeedResult {
    val counts = countViews(items, views, today)
    val secondsToday = seconds.filter { it.localDate == today }.sumOf { it.seconds }
    val appToday = appSeconds.filter { it.localDate == today }.sumOf { it.seconds }
    val timeUp = limits.dailyAppMinutes != null && appToday >= limits.dailyAppMinutes * 60 + limits.appTimeExtraSeconds
    val backlog = computeBacklog(plan, items, views, seconds, today)
    val cap = if (plan.paceType == PaceType.VIDEOS_PER_DAY) plan.maxExtraPerDay else plan.maxExtraPerDay * 60
    return evaluateDay(
        plan, items, counts.total, counts.today, secondsToday, timeUp, today,
        backlog, minOf(backlog, cap), defaultDurationSeconds,
    )
}
