package com.example.llamadroid.tama.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TamaBedtimeRulesTest {

    @Test
    fun `sleepy windows follow the requested hour ranges`() {
        assertTrue(isSleepyFairyReminderWindow(millis(2026, 4, 21, 21, 0)))
        assertTrue(isSleepyFairyReminderWindow(millis(2026, 4, 21, 23, 59)))
        assertFalse(isSleepyFairyReminderWindow(millis(2026, 4, 21, 20, 59)))
        assertFalse(isSleepyFairyReminderWindow(millis(2026, 4, 22, 0, 0)))

        assertTrue(isSleepyMoodWindow(millis(2026, 4, 21, 22, 0)))
        assertTrue(isSleepyMoodWindow(millis(2026, 4, 21, 23, 59)))
        assertFalse(isSleepyMoodWindow(millis(2026, 4, 21, 21, 59)))
        assertFalse(isSleepyMoodWindow(millis(2026, 4, 22, 0, 0)))
    }

    @Test
    fun `overnight overlap only counts time between midnight and six`() {
        val overlaps = overnightAwakeOverlaps(
            intervalStartMs = millis(2026, 4, 21, 23, 50),
            intervalEndMs = millis(2026, 4, 22, 0, 10)
        )

        assertEquals(1, overlaps.size)
        assertEquals("2026-04-22", overlaps.first().dateKey)
        assertEquals(10L * 60L * 1000L, overlaps.first().overlapMs)
    }

    @Test
    fun `additive overnight awake time triggers only after more than twenty minutes`() {
        val firstProgress = accumulateOvernightAwake(
            currentDateKey = null,
            currentAccumulatedMs = 0L,
            overlaps = listOf(
                TamaOvernightAwakeOverlap(
                    dateKey = "2026-04-22",
                    overlapMs = 10L * 60L * 1000L,
                    overnightWindowStartMs = millis(2026, 4, 22, 0, 0)
                )
            ),
            lastSleepWarningDateKey = null
        )
        assertEquals(10L * 60L * 1000L, firstProgress.accumulatedMs)
        assertTrue(firstProgress.triggerTimestamps.isEmpty())

        val secondProgress = accumulateOvernightAwake(
            currentDateKey = firstProgress.dateKey,
            currentAccumulatedMs = firstProgress.accumulatedMs,
            overlaps = listOf(
                TamaOvernightAwakeOverlap(
                    dateKey = "2026-04-22",
                    overlapMs = 12L * 60L * 1000L,
                    overnightWindowStartMs = millis(2026, 4, 22, 0, 0)
                )
            ),
            lastSleepWarningDateKey = null
        )
        assertEquals(22L * 60L * 1000L, secondProgress.accumulatedMs)
        assertEquals(1, secondProgress.triggerTimestamps.size)
    }

    @Test
    fun `exactly twenty minutes does not trigger the overnight miscare`() {
        val progress = accumulateOvernightAwake(
            currentDateKey = null,
            currentAccumulatedMs = 0L,
            overlaps = listOf(
                TamaOvernightAwakeOverlap(
                    dateKey = "2026-04-22",
                    overlapMs = 20L * 60L * 1000L,
                    overnightWindowStartMs = millis(2026, 4, 22, 0, 0)
                )
            ),
            lastSleepWarningDateKey = null
        )

        assertEquals(20L * 60L * 1000L, progress.accumulatedMs)
        assertTrue(progress.triggerTimestamps.isEmpty())
    }

    @Test
    fun `new overnight date starts a fresh accumulated bucket`() {
        val progress = accumulateOvernightAwake(
            currentDateKey = "2026-04-22",
            currentAccumulatedMs = 19L * 60L * 1000L,
            overlaps = listOf(
                TamaOvernightAwakeOverlap(
                    dateKey = "2026-04-23",
                    overlapMs = 2L * 60L * 1000L,
                    overnightWindowStartMs = millis(2026, 4, 23, 0, 0)
                )
            ),
            lastSleepWarningDateKey = null
        )

        assertEquals("2026-04-23", progress.dateKey)
        assertEquals(2L * 60L * 1000L, progress.accumulatedMs)
        assertTrue(progress.triggerTimestamps.isEmpty())
    }

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
