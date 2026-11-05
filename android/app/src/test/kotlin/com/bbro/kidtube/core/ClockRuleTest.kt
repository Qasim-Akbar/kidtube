package com.bbro.kidtube.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Enforces the project rule: app code reads time only through core.Clock.
 * Scans the production sources (src/main, src/debug, src/release) for direct clock calls.
 */
class ClockRuleTest {

    private val forbidden = listOf(
        Regex("""System\.currentTimeMillis\s*\("""),
        Regex("""System\.nanoTime\s*\("""),
        Regex("""\b(Instant|LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime|Year|YearMonth)\.now\s*\("""),
        Regex("""\bCalendar\.getInstance\s*\("""),
        Regex("""\bDate\s*\(\s*\)"""),
        Regex("""SystemClock\.(elapsedRealtime|uptimeMillis|currentThreadTimeMillis)"""),
    )

    @Test
    fun noDirectClockAccessOutsideDeviceClock() {
        val moduleDir = File(checkNotNull(System.getProperty("user.dir"))) // Gradle runs unit tests in the module dir
        val roots = listOf("main", "debug", "release").map { File(moduleDir, "src/$it") }.filter { it.exists() }
        assertTrue("could not find production sources under $moduleDir", roots.isNotEmpty())

        val violations = roots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { it.name != "DeviceClock.kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val trimmed = line.trim()
                    val isComment = trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
                    val code = line.substringBefore("//")
                    if (!isComment && forbidden.any { it.containsMatchIn(code) }) "${file.name}:${index + 1}: $trimmed" else null
                }
            }

        assertTrue(
            "Use the injected core.Clock instead of reading the clock directly:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
