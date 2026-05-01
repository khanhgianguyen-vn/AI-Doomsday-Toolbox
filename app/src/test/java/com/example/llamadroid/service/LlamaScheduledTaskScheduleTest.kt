package com.example.llamadroid.service

import com.example.llamadroid.data.model.LlamaScheduledTaskEntity
import com.example.llamadroid.data.model.LlamaScheduledTaskScheduleType
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaScheduledTaskScheduleTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun `one time returns future timestamp and ignores past timestamp`() {
        val future = millis(2026, 5, 1, 7, 0)
        val task = baseTask(
            scheduleType = LlamaScheduledTaskScheduleType.ONE_TIME,
            oneTimeAtMillis = future
        )

        assertEquals(future, LlamaScheduledTaskSchedule.computeNextRun(task, millis(2026, 5, 1, 6, 0)))
        assertNull(LlamaScheduledTaskSchedule.computeNextRun(task, millis(2026, 5, 1, 8, 0)))
    }

    @Test
    fun `daily schedule uses today when time is still future otherwise tomorrow`() {
        val task = baseTask(
            scheduleType = LlamaScheduledTaskScheduleType.DAILY,
            timeOfDayMinutes = 7 * 60 + 30
        )

        assertEquals(
            millis(2026, 5, 1, 7, 30),
            LlamaScheduledTaskSchedule.computeNextRun(task, millis(2026, 5, 1, 7, 0))
        )
        assertEquals(
            millis(2026, 5, 2, 7, 30),
            LlamaScheduledTaskSchedule.computeNextRun(task, millis(2026, 5, 1, 8, 0))
        )
    }

    @Test
    fun `weekly schedule picks the next enabled weekday`() {
        val mondayWednesday = LlamaScheduledTaskSchedule.weekdayBit(DayOfWeek.MONDAY) or
            LlamaScheduledTaskSchedule.weekdayBit(DayOfWeek.WEDNESDAY)
        val task = baseTask(
            scheduleType = LlamaScheduledTaskScheduleType.WEEKLY,
            weekdaysMask = mondayWednesday,
            timeOfDayMinutes = 9 * 60
        )

        assertEquals(
            millis(2026, 5, 4, 9, 0),
            LlamaScheduledTaskSchedule.computeNextRun(task, millis(2026, 5, 1, 8, 0))
        )
        assertEquals(
            millis(2026, 5, 6, 9, 0),
            LlamaScheduledTaskSchedule.computeNextRun(task, millis(2026, 5, 4, 10, 0))
        )
    }

    @Test
    fun `monthly schedule clamps to last valid day`() {
        val task = baseTask(
            scheduleType = LlamaScheduledTaskScheduleType.MONTHLY,
            dayOfMonth = 31,
            timeOfDayMinutes = 7 * 60
        )

        assertEquals(
            millis(2026, 2, 28, 7, 0),
            LlamaScheduledTaskSchedule.computeNextRun(task, millis(2026, 2, 1, 0, 0))
        )
        assertEquals(
            millis(2026, 3, 31, 7, 0),
            LlamaScheduledTaskSchedule.computeNextRun(task, millis(2026, 2, 28, 8, 0))
        )
    }

    @Test
    fun `scheduled note fallback only triggers for note oriented prompts`() {
        assertTrue(scheduledTaskPromptRequestsNote("Research tech news and create a note with sources."))
        assertTrue(scheduledTaskPromptRequestsNote("Do a web search and write results into a note."))
        assertTrue(scheduledTaskPromptRequestsNote("Investiga noticias y guarda en notas con citas."))
        assertFalse(scheduledTaskPromptRequestsNote("Research tech news and answer briefly."))
    }

    @Test
    fun `scheduled review parser accepts ok and fix outcomes`() {
        val fix = parseScheduledTaskReview("FIX: You forgot to create the requested note.")
        assertTrue(fix.needsRepair)
        assertEquals("You forgot to create the requested note.", fix.feedback)

        val ok = parseScheduledTaskReview("OK: The output includes the note creation evidence.")
        assertFalse(ok.needsRepair)
        assertEquals("The output includes the note creation evidence.", ok.feedback)
    }

    private fun baseTask(
        scheduleType: String,
        oneTimeAtMillis: Long? = null,
        timeOfDayMinutes: Int = 7 * 60,
        weekdaysMask: Int = 0,
        dayOfMonth: Int = 1
    ) = LlamaScheduledTaskEntity(
        id = 1,
        name = "Tech news",
        enabled = true,
        taskPrompt = "Research the news.",
        scheduleType = scheduleType,
        oneTimeAtMillis = oneTimeAtMillis,
        timeOfDayMinutes = timeOfDayMinutes,
        weekdaysMask = weekdaysMask,
        dayOfMonth = dayOfMonth,
        timezoneId = zone.id
    )

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}
