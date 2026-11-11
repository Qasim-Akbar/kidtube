package com.bbro.kidtube.engine

import java.time.LocalDate

// Kotlin port of backend/app/engine/projection.py: simulate the queue forward assuming every offered view is completed.
// Pure; see Feed.kt for the notes on deliberate differences.

const val HORIZON_DAYS = 90 // days simulated after today; offsets 0..90 inclusive
const val MAX_OUTPUT_DAYS = HORIZON_DAYS + 1
const val MAX_STEPS_PER_DAY = 500 // hard guard on the within-day loop (it ends by itself: quotas and view targets are finite)

/**
 * Recompute the feed after every completed step until it offers nothing; returns the day's views in order.
 *
 * Counters ([viewsTotal], [viewsToday]) are updated in place. [first] is an already-computed first step (day 0 uses
 * build_feed for it). Python mutates `seconds_today` as a local; here it is the same (a local `var`).
 */
private fun simulateDay(
    plan: PlanConfig,
    items: List<ItemState>,
    day: LocalDate,
    viewsTotal: MutableMap<String, Int>,
    viewsToday: MutableMap<String, Int>,
    secondsTodayStart: Int,
    extra: Int,
    backlog: Int,
    defaultDurationSeconds: Int,
    first: FeedResult? = null,
): List<ProjectedView> {
    val byId = items.associateBy { it.id }
    val picked = mutableListOf<ProjectedView>()
    var secondsToday = secondsTodayStart
    var feed = first
    repeat(MAX_STEPS_PER_DAY) {
        val current = feed ?: evaluateDay(
            plan, items, viewsTotal, viewsToday, secondsToday, false, day, backlog, extra, defaultDurationSeconds,
        )
        if (current.items.isEmpty()) return picked
        for (f in current.items) { // the child finishes everything offered in this step
            picked += ProjectedView(f.itemId, f.videoId)
            viewsTotal[f.itemId] = (viewsTotal[f.itemId] ?: 0) + 1
            viewsToday[f.itemId] = (viewsToday[f.itemId] ?: 0) + 1
            secondsToday += byId.getValue(f.itemId).durationSeconds ?: defaultDurationSeconds
        }
        feed = null
    }
    return picked
}

fun project(
    plan: PlanConfig,
    items: List<ItemState>,
    views: List<ViewRecord>,
    seconds: List<SecondsRecord>,
    today: LocalDate,
    days: Int,
    defaultDurationSeconds: Int = DEFAULT_DURATION_SECONDS,
): Projection {
    val excluded = items.filter { isUnlimited(it) }.sortedWith(positionThenId)
    val simItems = items.filter { !isUnlimited(it) }

    val counts = countViews(simItems, views, today)
    val viewsTotal = counts.total
    val viewsToday0 = counts.today
    val remainingFinite = classify(simItems, viewsTotal, today).pending.sumOf {
        maxOf(0, (targetViews(it) ?: 0) - (viewsTotal[it.id] ?: 0))
    }

    val simulated = mutableListOf<ProjectedDay>()
    for (offset in 0..HORIZON_DAYS) {
        val day = today.plusDays(offset.toLong())
        val status: DayStatus
        val picked: List<ProjectedView>
        if (offset == 0) { // first step is exactly the feed logic, catch-up included
            val feed = buildFeed(plan, simItems, views, seconds, emptyList(), ChildLimits(), today, defaultDurationSeconds)
            status = when (feed.status) {
                FeedStatus.OUTSIDE_DATES -> DayStatus.OUTSIDE_DATES
                FeedStatus.DAY_OFF -> DayStatus.DAY_OFF
                else -> DayStatus.SCHEDULED
            }
            val base = if (plan.paceType == PaceType.VIDEOS_PER_DAY) plan.paceValue else plan.paceValue * 60
            picked = simulateDay(
                plan, simItems, today, viewsTotal, viewsToday0,
                seconds.filter { it.localDate == today }.sumOf { it.seconds },
                maxOf(0, feed.quotaToday - base), feed.backlog, defaultDurationSeconds, first = feed,
            )
        } else { // base pace only: the backlog is only ever absorbed on day 0
            status = when (nonActiveDayStatus(plan, day)) {
                FeedStatus.OUTSIDE_DATES -> DayStatus.OUTSIDE_DATES
                FeedStatus.DAY_OFF -> DayStatus.DAY_OFF
                else -> DayStatus.SCHEDULED
            }
            picked = if (status == DayStatus.SCHEDULED) {
                simulateDay(plan, simItems, day, viewsTotal, HashMap(), 0, 0, 0, defaultDurationSeconds)
            } else {
                emptyList()
            }
        }
        simulated += ProjectedDay(day, status, picked)
    }

    val end = today.plusDays(HORIZON_DAYS.toLong())
    val unscheduled = classify(simItems, viewsTotal, end).pending.sortedWith(positionThenId)
    val lastViewDay = simulated.filter { it.views.isNotEmpty() }.maxOfOrNull { it.date }
    val finish = if (unscheduled.isEmpty()) lastViewDay else null

    val shown = simulated.take(maxOf(0, minOf(days, MAX_OUTPUT_DAYS)))
    return Projection(
        days = shown,
        finishDate = finish,
        remainingFiniteViews = remainingFinite,
        unscheduledItemIds = unscheduled.map { it.id },
        excludedUnlimitedItemIds = excluded.map { it.id },
    )
}
