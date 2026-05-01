package com.example.llamadroid.tama.data

import java.util.Calendar
import java.util.Locale

const val TAMA_SLEEPY_FAIRY_COOLDOWN_MS = 20L * 60L * 1000L
const val TAMA_SLEEPY_FAIRY_AUTO_HIDE_MS = 8_000L
const val TAMA_OVERNIGHT_AWAKE_PENALTY_MS = 20L * 60L * 1000L

data class TamaOvernightAwakeOverlap(
    val dateKey: String,
    val overlapMs: Long,
    val overnightWindowStartMs: Long
)

data class TamaOvernightAwakeProgress(
    val dateKey: String?,
    val accumulatedMs: Long,
    val triggerTimestamps: List<Long>
)

fun isSleepyFairyReminderWindow(now: Long = System.currentTimeMillis()): Boolean {
    val calendar = Calendar.getInstance().apply { timeInMillis = now }
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    return hour in 21..23
}

fun isSleepyMoodWindow(now: Long = System.currentTimeMillis()): Boolean {
    val calendar = Calendar.getInstance().apply { timeInMillis = now }
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    return hour in 22..23
}

fun localDateKey(now: Long = System.currentTimeMillis()): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = now }
    return localDateKey(calendar)
}

fun localDateKey(calendar: Calendar): String {
    return String.format(
        Locale.ROOT,
        "%04d-%02d-%02d",
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH) + 1,
        calendar.get(Calendar.DAY_OF_MONTH)
    )
}

fun overnightAwakeOverlaps(
    intervalStartMs: Long,
    intervalEndMs: Long
): List<TamaOvernightAwakeOverlap> {
    if (intervalEndMs <= intervalStartMs) return emptyList()
    val overlaps = mutableListOf<TamaOvernightAwakeOverlap>()
    val cursor = Calendar.getInstance().apply {
        timeInMillis = intervalStartMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    while (cursor.timeInMillis < intervalEndMs) {
        val windowStart = cursor.timeInMillis
        val windowEnd = windowStart + (6L * 60L * 60L * 1000L)
        val overlapStart = maxOf(intervalStartMs, windowStart)
        val overlapEnd = minOf(intervalEndMs, windowEnd)
        if (overlapEnd > overlapStart) {
            overlaps += TamaOvernightAwakeOverlap(
                dateKey = localDateKey(cursor),
                overlapMs = overlapEnd - overlapStart,
                overnightWindowStartMs = windowStart
            )
        }
        cursor.add(Calendar.DAY_OF_YEAR, 1)
    }
    return overlaps
}

fun accumulateOvernightAwake(
    currentDateKey: String?,
    currentAccumulatedMs: Long,
    overlaps: List<TamaOvernightAwakeOverlap>,
    lastSleepWarningDateKey: String?
): TamaOvernightAwakeProgress {
    if (overlaps.isEmpty()) {
        return TamaOvernightAwakeProgress(
            dateKey = currentDateKey,
            accumulatedMs = currentAccumulatedMs,
            triggerTimestamps = emptyList()
        )
    }
    var activeDateKey = currentDateKey
    var activeAccumulatedMs = currentAccumulatedMs
    val triggerTimestamps = mutableListOf<Long>()
    overlaps.forEach { overlap ->
        if (activeDateKey != overlap.dateKey) {
            activeDateKey = overlap.dateKey
            activeAccumulatedMs = 0L
        }
        val previousAccumulated = activeAccumulatedMs
        val newAccumulated = previousAccumulated + overlap.overlapMs
        val alreadyWarnedForDate = lastSleepWarningDateKey == overlap.dateKey ||
            triggerTimestamps.any { localDateKey(it) == overlap.dateKey }
        if (!alreadyWarnedForDate &&
            previousAccumulated <= TAMA_OVERNIGHT_AWAKE_PENALTY_MS &&
            newAccumulated > TAMA_OVERNIGHT_AWAKE_PENALTY_MS
        ) {
            val triggerOffset = (TAMA_OVERNIGHT_AWAKE_PENALTY_MS - previousAccumulated).coerceAtLeast(0L) + 1L
            triggerTimestamps += overlap.overnightWindowStartMs + triggerOffset
        }
        activeAccumulatedMs = newAccumulated
    }
    return TamaOvernightAwakeProgress(
        dateKey = activeDateKey,
        accumulatedMs = activeAccumulatedMs,
        triggerTimestamps = triggerTimestamps
    )
}
