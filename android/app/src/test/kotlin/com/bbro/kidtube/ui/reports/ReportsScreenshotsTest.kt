package com.bbro.kidtube.ui.reports

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.bbro.kidtube.data.remote.ReportDto
import com.bbro.kidtube.data.repo.ReportFailureKind
import com.bbro.kidtube.data.repo.ReportRange
import com.bbro.kidtube.ui.theme.KidTubeTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/** Renders the Reports tab to PNG for design review. Skipped unless KIDTUBE_SCREENSHOTS names the output directory (files p2-*.png). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ReportsScreenshotsBase(private val tag: String) {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, state: ReportsUiState) {
        val dir = System.getenv("KIDTUBE_SCREENSHOTS")
        assumeTrue("set KIDTUBE_SCREENSHOTS to render screenshots", dir != null)
        compose.setContent { KidTubeTheme { Frame { ReportsContent(state, ReportsActions()) } } }
        compose.waitForIdle()
        val file = File(dir, "p2-$name-$tag.png").also { it.parentFile?.mkdirs() }
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The page background the tab sits on inside Parent Home. */
    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(KidTubeTheme.colors.ground),
        ) { content() }
    }

    @Test fun week() = shot("week", reportState(fixtureReport(77)))

    @Test fun day() = shot("day", reportState(fixtureReport(76), range = ReportRange.DAY))

    @Test fun month() = shot("month", reportState(fixtureReport(78), range = ReportRange.MONTH))

    @Test fun monthFilled() = shot("month-filled", reportState(busyMonth(), range = ReportRange.MONTH))

    @Test fun empty() = shot("empty", reportState(fixtureReport(79)))

    @Test fun loading() = shot("loading", reportState(null, refreshing = true))

    @Test fun offline() = shot("offline", reportState(null, failure = ReportFailureKind.NO_CONNECTION))

    @Test fun stale() = shot("stale", reportState(fixtureReport(77), failure = ReportFailureKind.NO_CONNECTION, anchor = LocalDate.parse("2025-12-29")))

    @Test fun minutesPlan() = shot("minutes-plan", reportState(minutesPlanReport()))
}

/** A month with watching on most days, so the bars have varied heights (the contract fixture has only one busy day). */
internal fun busyMonth(): ReportDto {
    val base = fixtureReport(78)
    val days = base.days.mapIndexed { i, d ->
        val watch = if (i % 7 == 6) 0 else (i * 97 % 41 + 5) * 60
        d.copy(watchSeconds = watch, appSeconds = watch + 600, viewsCompleted = watch / 600)
    }
    return base.copy(
        days = days,
        totals = base.totals.copy(
            watchSeconds = days.sumOf { it.watchSeconds }, appSeconds = days.sumOf { it.appSeconds },
            viewsCompleted = days.sumOf { it.viewsCompleted },
        ),
    )
}

/** The week fixture with a minutes-per-day plan (watched vs planned in time) and long titles. */
internal fun minutesPlanReport(): ReportDto {
    val base = fixtureReport(77)
    return base.copy(
        plannedVsWatched = base.plannedVsWatched?.copy(
            paceType = com.bbro.kidtube.data.remote.PaceType.MINUTES_PER_DAY, unit = "seconds", expected = 1800, actual = 96,
        ),
        topVideos = base.topVideos.map { it.copy(title = "A very long video title that goes on and on about counting numbers and colours with friends") },
        totals = base.totals.copy(watchSeconds = 3900 + 96, appSeconds = 7500),
    )
}

@Config(qualifiers = "en-w411dp-h2400dp-xxhdpi")
class ReportsScreenshotsEnTest : ReportsScreenshotsBase("en")

@Config(qualifiers = "ur-ldrtl-w411dp-h2400dp-xxhdpi")
class ReportsScreenshotsUrTest : ReportsScreenshotsBase("ur")
