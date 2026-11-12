package com.bbro.kidtube.ui.reports

import android.content.res.Resources
import com.bbro.kidtube.R
import com.bbro.kidtube.data.remote.ReportDto
import com.bbro.kidtube.data.repo.ReportRange
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Period maths for the Reports tab. Mirrors backend/app/services/reports.py `range_bounds` (SPEC section 14): a day is that date,
 * a week is Monday..Sunday, a month is the calendar month. Every function takes `today` as an argument (the child's local date from
 * the injected Clock), so nothing here reads a clock.
 */
object ReportPeriods {
    fun start(range: ReportRange, anchor: LocalDate): LocalDate = when (range) {
        ReportRange.DAY -> anchor
        ReportRange.WEEK -> anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
        ReportRange.MONTH -> anchor.withDayOfMonth(1)
    }

    fun end(range: ReportRange, anchor: LocalDate): LocalDate = when (range) {
        ReportRange.DAY -> anchor
        ReportRange.WEEK -> start(range, anchor).plusDays(6)
        ReportRange.MONTH -> YearMonth.from(anchor).atEndOfMonth()
    }

    /** One period back ([steps] = -1) or forward (+1), never past [today]. */
    fun shift(range: ReportRange, anchor: LocalDate, steps: Int, today: LocalDate): LocalDate {
        val moved = when (range) {
            ReportRange.DAY -> anchor.plusDays(steps.toLong())
            ReportRange.WEEK -> anchor.plusWeeks(steps.toLong())
            ReportRange.MONTH -> anchor.plusMonths(steps.toLong())
        }
        return minOf(moved, today)
    }

    /** The next period exists only when it has started: its first day is not after [today]. */
    fun canGoNext(range: ReportRange, anchor: LocalDate, today: LocalDate): Boolean = end(range, anchor) < today

    /** "Wed 12 Nov", "3-9 Nov" (or "30 Nov - 6 Dec"), "November 2026"; month and weekday names follow [locale]. */
    fun label(range: ReportRange, anchor: LocalDate, locale: Locale): String {
        fun fmt(pattern: String, date: LocalDate) = DateTimeFormatter.ofPattern(pattern, locale).format(date)
        return when (range) {
            ReportRange.DAY -> fmt("EEE d MMM", anchor)
            ReportRange.MONTH -> fmt("LLLL y", anchor)
            ReportRange.WEEK -> {
                val from = start(range, anchor)
                val to = end(range, anchor)
                when {
                    from.year != to.year -> fmt("d MMM y", from) + " – " + fmt("d MMM y", to)
                    from.month != to.month -> fmt("d MMM", from) + " – " + fmt("d MMM", to)
                    else -> from.dayOfMonth.toString() + "–" + fmt("d MMM", to)
                }
            }
        }
    }
}

/** How a number of seconds is written: under a minute "40 s", under an hour "5 min", else "1h 05m". Whole units are floored. */
sealed interface DurationParts {
    data class Seconds(val seconds: Int) : DurationParts

    data class Minutes(val minutes: Int) : DurationParts

    data class HoursMinutes(val hours: Int, val minutes: Int) : DurationParts
}

fun durationParts(totalSeconds: Int): DurationParts {
    val s = totalSeconds.coerceAtLeast(0)
    return when {
        s < 60 -> DurationParts.Seconds(s)
        s < 3600 -> DurationParts.Minutes(s / 60)
        else -> DurationParts.HoursMinutes(s / 3600, (s % 3600) / 60)
    }
}

/** [durationParts] worded in the language of these resources (en: "1h 05m" / "5 min" / "40 s"). */
fun Resources.durationText(totalSeconds: Int): String = when (val p = durationParts(totalSeconds)) {
    is DurationParts.Seconds -> getString(R.string.duration_seconds, p.seconds)
    is DurationParts.Minutes -> getString(R.string.duration_minutes, p.minutes)
    is DurationParts.HoursMinutes -> getString(R.string.duration_hours_minutes, p.hours, p.minutes)
}

// ---- chart ------------------------------------------------------------------------------------------------------------

enum class ChartMetric { WATCH_TIME, VIEWS }

/** One bar: a day of the report and its value (seconds or views). */
data class ChartBar(val date: LocalDate, val value: Int)

/** One bar per day of the report, in the server's order. */
fun chartBars(report: ReportDto, metric: ChartMetric): List<ChartBar> = report.days.map {
    ChartBar(it.date, if (metric == ChartMetric.WATCH_TIME) it.watchSeconds else it.viewsCompleted)
}

/** Bar height: proportional to [max], never below [minHeight] so a zero day stays visible as a thin line. */
fun barHeight(value: Int, max: Int, plotHeight: Float, minHeight: Float): Float =
    if (value <= 0 || max <= 0) minHeight else maxOf(minHeight, plotHeight * value / max)

/** Spacing steps for axis labels that read naturally (every day, every 2nd, ...). */
private val LABEL_STEPS = listOf(1, 2, 3, 5, 7, 10)

/**
 * Which bars get a label under them. Every bar when the labels fit their slots; otherwise every n-th bar (n from [LABEL_STEPS],
 * starting with the first) so two labels never collide. A label needs [labelWidth] plus [gap] of room.
 */
fun labelIndices(count: Int, slotWidth: Float, labelWidth: Float, gap: Float = 4f): Set<Int> {
    if (count <= 0) return emptySet()
    if (slotWidth <= 0f) return setOf(0)
    val needed = Math.ceil(((labelWidth + gap) / slotWidth).toDouble()).toInt().coerceAtLeast(1)
    val step = LABEL_STEPS.firstOrNull { it >= needed } ?: count
    return (0 until count step step).toSet()
}

/** A rough label width in dp for [texts] (the widest one), good enough to decide how many labels fit. */
fun estimatedLabelWidth(texts: List<String>, charWidth: Float = 6f): Float = (texts.maxOfOrNull { it.length } ?: 0) * charWidth

// ---- report facts ----------------------------------------------------------------------------------------------------------

fun ReportDto.errorCount(): Int = playbackErrors.sumOf { it.count }

/** Nothing happened in the period: no views, no watch time, no app time, no errors. */
fun ReportDto.isEmptyPeriod(): Boolean =
    totals.viewsCompleted == 0 && totals.watchSeconds == 0 && totals.appSeconds == 0 && playbackErrors.isEmpty()

fun ReportDto.finishedItems(): Int = perVideo.count { it.completed }
