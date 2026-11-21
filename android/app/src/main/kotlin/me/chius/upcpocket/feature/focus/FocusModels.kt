package me.chius.upcpocket.feature.focus

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.Serializable

@Serializable
enum class FocusMode(
    val label: String,
    val durationSeconds: Int,
    val isFocus: Boolean,
) {
    FOCUS_25(label = "专注 25 分钟", durationSeconds = 25 * 60, isFocus = true),
    SHORT_BREAK_5(label = "短休 5 分钟", durationSeconds = 5 * 60, isFocus = false),
    DEEP_45(label = "深度 45 分钟", durationSeconds = 45 * 60, isFocus = true),
}

/**
 * Wall-clock timer snapshot (single source of truth that survives process termination).
 */
@Serializable
data class FocusTimerSnapshot(
    val mode: FocusMode = FocusMode.FOCUS_25,
    val running: Boolean = false,
    val startedAt: Long = 0L,
    val plannedSeconds: Int = FocusMode.FOCUS_25.durationSeconds,
    val pausedTotalMs: Long = 0L,
    val pausedAt: Long = 0L,
    val focusRound: Int = 0,
) {
    val idle: Boolean
        get() = startedAt == 0L

    val paused: Boolean
        get() = !idle && pausedAt > 0L
}

object PomodoroClock {
    fun pausedMs(s: FocusTimerSnapshot, nowMillis: Long): Long {
        var paused = s.pausedTotalMs
        if (s.pausedAt > 0L) {
            paused += (nowMillis - s.pausedAt).coerceAtLeast(0L)
        }
        return paused.coerceAtLeast(0L)
    }

    fun elapsedSeconds(s: FocusTimerSnapshot, nowMillis: Long): Int {
        if (s.startedAt == 0L) return 0
        val paused = pausedMs(s, nowMillis)
        val rawMs = nowMillis - s.startedAt - paused
        return (rawMs / 1000L).coerceAtLeast(0L).toInt()
    }

    fun remainingSeconds(s: FocusTimerSnapshot, nowMillis: Long): Int {
        if (s.startedAt == 0L) return s.plannedSeconds
        return (s.plannedSeconds - elapsedSeconds(s, nowMillis)).coerceAtLeast(0)
    }

    fun progress(s: FocusTimerSnapshot, nowMillis: Long): Float {
        if (s.plannedSeconds <= 0) return 0f
        if (s.startedAt == 0L) return 1f
        return (remainingSeconds(s, nowMillis).toFloat() / s.plannedSeconds.toFloat()).coerceIn(0f, 1f)
    }

    fun isFinished(s: FocusTimerSnapshot, nowMillis: Long): Boolean =
        s.startedAt != 0L && remainingSeconds(s, nowMillis) <= 0

    fun format(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        return "%02d:%02d".format(safe / 60, safe % 60)
    }
}

@Serializable
data class FocusRecord(
    val id: String,
    val mode: FocusMode,
    val startedAt: Long,
    val endedAt: Long,
    val actualSeconds: Int,
    val completed: Boolean,
)

data class FocusTotals(
    val seconds: Int = 0,
    val sessions: Int = 0,
) {
    operator fun plus(other: FocusTotals): FocusTotals =
        FocusTotals(seconds + other.seconds, sessions + other.sessions)
}

data class FocusSummary(
    val today: FocusTotals = FocusTotals(),
    val week: FocusTotals = FocusTotals(),
    val month: FocusTotals = FocusTotals(),
    val allTime: FocusTotals = FocusTotals(),
)

/**
 * Pure statistics using a 04:00 AM study-day cutoff and Monday week start.
 */
object FocusStats {
    const val DAY_START_HOUR = 4

    fun dayKey(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate {
        val zdt = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return if (zdt.hour < DAY_START_HOUR) zdt.toLocalDate().minusDays(1) else zdt.toLocalDate()
    }

    fun dayStart(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        day.atTime(DAY_START_HOUR, 0).atZone(zone).toInstant().toEpochMilli()

    fun dayEnd(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        dayStart(day.plusDays(1), zone)

    fun weekStart(day: LocalDate): LocalDate =
        day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun monthStart(day: LocalDate): LocalDate = day.withDayOfMonth(1)

    fun totals(records: List<FocusRecord>): FocusTotals {
        var seconds = 0
        var sessions = 0
        records.asSequence()
            .filter { it.mode.isFocus }
            .forEach { rec ->
                seconds += rec.actualSeconds.coerceAtLeast(0)
                if (rec.completed) sessions += 1
            }
        return FocusTotals(seconds = seconds, sessions = sessions)
    }

    fun totalsBetween(records: List<FocusRecord>, fromMillis: Long, toMillis: Long): FocusTotals =
        totals(records.filter { it.startedAt >= fromMillis && it.startedAt < toMillis })

    fun summarize(
        records: List<FocusRecord>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): FocusSummary {
        val today = dayKey(nowMillis, zone)
        val endMs = dayEnd(today, zone)
        return FocusSummary(
            today = totalsBetween(records, dayStart(today, zone), endMs),
            week = totalsBetween(records, dayStart(weekStart(today), zone), endMs),
            month = totalsBetween(records, dayStart(monthStart(today), zone), endMs),
            allTime = totals(records),
        )
    }

    fun humanDuration(seconds: Int): String {
        if (seconds <= 0) return "0 分钟"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> "$hours 小时 $minutes 分"
            hours > 0 -> "$hours 小时"
            minutes > 0 -> "$minutes 分钟"
            else -> "不到 1 分钟"
        }
    }
}

@Serializable
data class FocusPersistenceState(
    val version: Int = 1,
    val snapshot: FocusTimerSnapshot = FocusTimerSnapshot(),
    val records: List<FocusRecord> = emptyList(),
)

data class FocusTimerUiState(
    val mode: FocusMode = FocusMode.FOCUS_25,
    val remainingSeconds: Int = FocusMode.FOCUS_25.durationSeconds,
    val elapsedSeconds: Int = 0,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val isIdle: Boolean = true,
    val progress: Float = 1f,
    val formattedTime: String = PomodoroClock.format(FocusMode.FOCUS_25.durationSeconds),
    val focusRound: Int = 0,
    val summary: FocusSummary = FocusSummary(),
    val recentRecords: List<FocusRecord> = emptyList(),
)
