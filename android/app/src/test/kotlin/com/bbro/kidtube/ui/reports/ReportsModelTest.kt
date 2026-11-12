package com.bbro.kidtube.ui.reports

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.bbro.kidtube.data.remote.ReportDto
import com.bbro.kidtube.data.repo.ReportRange
import com.bbro.kidtube.testing.ContractFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.util.Locale

/** Period maths, labels, durations and chart helpers (pure; Robolectric only for the string resources). */
@RunWith(RobolectricTestRunner::class)
class ReportsModelTest {
    private fun d(text: String) = LocalDate.parse(text)

    private fun resources(locale: Locale): Resources {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(configuration).resources
    }

    private fun hasUrdu(text: String) = text.any { it in '؀'..'ۿ' }

    // ---- period bounds (the same rules as backend range_bounds) ---------------------------------------------------------------

    @Test
    fun aWeekIsMondayToSunday_whateverDayOfItIsPicked() {
        for (day in listOf("2026-01-05", "2026-01-07", "2026-01-11")) { // Mon, Wed, Sun
            assertEquals(d("2026-01-05"), ReportPeriods.start(ReportRange.WEEK, d(day)))
            assertEquals(d("2026-01-11"), ReportPeriods.end(ReportRange.WEEK, d(day)))
        }
        assertEquals(d("2025-12-29"), ReportPeriods.start(ReportRange.WEEK, d("2026-01-01"))) // a week across the year
        assertEquals(d("2026-01-04"), ReportPeriods.end(ReportRange.WEEK, d("2026-01-01")))
    }

    @Test
    fun aMonthIsTheCalendarMonth_includingLeapFebruary() {
        assertEquals(d("2026-02-01"), ReportPeriods.start(ReportRange.MONTH, d("2026-02-17")))
        assertEquals(d("2026-02-28"), ReportPeriods.end(ReportRange.MONTH, d("2026-02-17")))
        assertEquals(d("2028-02-29"), ReportPeriods.end(ReportRange.MONTH, d("2028-02-01")))
        assertEquals(d("2026-12-31"), ReportPeriods.end(ReportRange.MONTH, d("2026-12-05")))
    }

    @Test
    fun aDayIsItself() {
        assertEquals(d("2026-01-05"), ReportPeriods.start(ReportRange.DAY, d("2026-01-05")))
        assertEquals(d("2026-01-05"), ReportPeriods.end(ReportRange.DAY, d("2026-01-05")))
    }

    // ---- navigation ----------------------------------------------------------------------------------------------------------

    @Test
    fun movingBackAndForwardAcrossMonthAndYearBoundaries() {
        val today = d("2026-03-31")
        assertEquals(d("2026-01-31"), ReportPeriods.shift(ReportRange.DAY, d("2026-02-01"), -1, today))
        assertEquals(d("2025-12-31"), ReportPeriods.shift(ReportRange.DAY, d("2026-01-01"), -1, today))
        assertEquals(d("2026-02-28"), ReportPeriods.shift(ReportRange.MONTH, d("2026-03-31"), -1, today)) // 31 -> last day of a shorter month
        assertEquals(d("2025-12-05"), ReportPeriods.shift(ReportRange.MONTH, d("2026-01-05"), -1, today))
        assertEquals(d("2026-02-23"), ReportPeriods.shift(ReportRange.WEEK, d("2026-03-02"), -1, today))
        assertEquals(d("2026-03-05"), ReportPeriods.shift(ReportRange.MONTH, d("2026-02-05"), 1, today))
    }

    @Test
    fun neverMovesPastToday_andNextIsOnlyOfferedWhenTheNextPeriodHasStarted() {
        val today = d("2026-01-07") // Wednesday
        assertEquals(today, ReportPeriods.shift(ReportRange.DAY, today, 1, today))
        assertEquals(today, ReportPeriods.shift(ReportRange.WEEK, d("2025-12-31"), 1, today)) // same weekday next week is the future: clamped
        assertFalse(ReportPeriods.canGoNext(ReportRange.DAY, today, today))
        assertTrue(ReportPeriods.canGoNext(ReportRange.DAY, d("2026-01-06"), today))
        assertFalse("this week is not over", ReportPeriods.canGoNext(ReportRange.WEEK, d("2026-01-05"), today))
        assertTrue(ReportPeriods.canGoNext(ReportRange.WEEK, d("2025-12-31"), today))
        assertFalse("this month is not over", ReportPeriods.canGoNext(ReportRange.MONTH, today, today))
        assertTrue(ReportPeriods.canGoNext(ReportRange.MONTH, d("2025-12-20"), today))
        assertFalse("the last day of a month: the next month has not started", ReportPeriods.canGoNext(ReportRange.MONTH, d("2026-01-31"), d("2026-01-31")))
        assertTrue(ReportPeriods.canGoNext(ReportRange.MONTH, d("2026-01-31"), d("2026-02-01")))
    }

    // ---- labels --------------------------------------------------------------------------------------------------------------

    @Test
    fun englishLabels() {
        assertEquals("Wed 12 Nov", ReportPeriods.label(ReportRange.DAY, d("2025-11-12"), Locale.ENGLISH))
        assertEquals("3–9 Nov", ReportPeriods.label(ReportRange.WEEK, d("2025-11-05"), Locale.ENGLISH))
        assertEquals("30 Nov – 6 Dec", ReportPeriods.label(ReportRange.WEEK, d("2026-12-02"), Locale.ENGLISH))
        assertEquals("29 Dec 2025 – 4 Jan 2026", ReportPeriods.label(ReportRange.WEEK, d("2026-01-01"), Locale.ENGLISH))
        assertEquals("November 2026", ReportPeriods.label(ReportRange.MONTH, d("2026-11-12"), Locale.ENGLISH))
    }

    @Test
    fun urduLabelsUseUrduMonthAndWeekdayNames() {
        val urdu = Locale.forLanguageTag("ur")
        val day = ReportPeriods.label(ReportRange.DAY, d("2026-11-12"), urdu)
        val month = ReportPeriods.label(ReportRange.MONTH, d("2026-11-12"), urdu)
        assertFalse("not the English month: $month", month.contains("November"))
        assertTrue("an Urdu month name: $month", hasUrdu(month))
        assertTrue("an Urdu weekday: $day", hasUrdu(day))
        assertFalse(day.contains("Thu"))
    }

    // ---- durations ---------------------------------------------------------------------------------------------------------------

    @Test
    fun durationPartsPickTheSmallestReadableUnit() {
        assertEquals(DurationParts.Seconds(0), durationParts(0))
        assertEquals(DurationParts.Seconds(40), durationParts(40))
        assertEquals(DurationParts.Seconds(59), durationParts(59))
        assertEquals(DurationParts.Minutes(1), durationParts(60))
        assertEquals(DurationParts.Minutes(5), durationParts(300))
        assertEquals(DurationParts.Minutes(59), durationParts(3599))
        assertEquals(DurationParts.HoursMinutes(1, 0), durationParts(3600))
        assertEquals(DurationParts.HoursMinutes(1, 5), durationParts(3900))
        assertEquals(DurationParts.HoursMinutes(26, 2), durationParts(26 * 3600 + 150))
        assertEquals("never a minus", DurationParts.Seconds(0), durationParts(-5))
    }

    @Test
    fun englishDurations() {
        val r = resources(Locale.ENGLISH)
        assertEquals("40 s", r.durationText(40))
        assertEquals("5 min", r.durationText(300))
        assertEquals("1h 05m", r.durationText(3900))
        assertEquals("2h 00m", r.durationText(7200))
        assertEquals("0 s", r.durationText(0))
    }

    @Test
    fun urduDurationsKeepLatinDigitsAndUrduUnits() {
        val r = resources(Locale.forLanguageTag("ur"))
        val seconds = r.durationText(40)
        val minutes = r.durationText(300)
        val hours = r.durationText(3900)
        assertTrue(seconds, seconds.startsWith("40 ") && hasUrdu(seconds))
        assertTrue(minutes, minutes.startsWith("5 ") && hasUrdu(minutes))
        assertTrue(hours, hours.startsWith("1 ") && hours.contains("05 "))
        assertTrue("digits stay Latin: $hours", hours.none { it in '٠'..'٩' || it in '۰'..'۹' })
    }

    // ---- chart ---------------------------------------------------------------------------------------------------------------------

    @Test
    fun aWeekHasRoomForEveryLabel_aMonthSkipsThemSoNoneCollide() {
        // a phone plot is about 312dp wide: 7 slots of 44dp for 3-letter labels (24dp)
        assertEquals((0..6).toSet(), labelIndices(7, 312f / 7, estimatedLabelWidth(listOf("Mon", "Tue", "Wed"))))
        // month: 31 slots of about 10dp with two-digit labels (16dp): every second day
        val slot = 312f / 31
        val month = labelIndices(31, slot, estimatedLabelWidth(listOf("10", "31")))
        assertEquals((0..30 step 2).toSet(), month)
        assertTrue(month.sorted().zipWithNext().all { (a, b) -> (b - a) * slot >= 16f }) // never closer than a label is wide
    }

    @Test
    fun labelSkippingGrowsWithCrowdingAndHandlesDegenerateInput() {
        assertEquals(setOf(0, 5, 10, 15, 20, 25, 30), labelIndices(31, 6f, 20f)) // step 5: days 1, 6, 11, ...
        assertEquals(setOf(0), labelIndices(7, 0f, 20f))
        assertEquals(emptySet<Int>(), labelIndices(0, 40f, 20f))
        assertEquals((0..2).toSet(), labelIndices(3, 200f, 20f))
        assertEquals(setOf(0), labelIndices(5, 1f, 100f)) // absurdly crowded: only the first
    }

    @Test
    fun barHeightsAreProportional_andAZeroDayStaysAThinLine() {
        assertEquals(140f, barHeight(50, 50, 140f, 2f), 0.001f)
        assertEquals(70f, barHeight(25, 50, 140f, 2f), 0.001f)
        assertEquals("zero is a thin line", 2f, barHeight(0, 50, 140f, 2f), 0.001f)
        assertEquals("a whole empty week", 2f, barHeight(0, 0, 140f, 2f), 0.001f)
        assertEquals("a tiny value is not thinner than the line", 2f, barHeight(1, 100000, 140f, 2f), 0.001f)
    }

    @Test
    fun chartBarsFollowTheServerDays_forEitherMetric() {
        val report = ContractFixtures.load(77).decodeBody<ReportDto>()
        val watch = chartBars(report, ChartMetric.WATCH_TIME)
        val views = chartBars(report, ChartMetric.VIEWS)
        assertEquals(7, watch.size)
        assertEquals(report.days.map { it.date }, watch.map { it.date })
        assertEquals(listOf(96, 0, 0, 0, 0, 0, 0), watch.map { it.value })
        assertEquals(listOf(1, 0, 0, 0, 0, 0, 0), views.map { it.value })
        assertEquals(31, chartBars(ContractFixtures.load(78).decodeBody<ReportDto>(), ChartMetric.VIEWS).size)
    }

    @Test
    fun reportFacts() {
        val week = ContractFixtures.load(77).decodeBody<ReportDto>()
        assertEquals(1, week.errorCount())
        assertFalse(week.isEmptyPeriod())
        assertEquals(1, week.finishedItems())
        assertTrue(ContractFixtures.load(79).decodeBody<ReportDto>().isEmptyPeriod())
    }
}
