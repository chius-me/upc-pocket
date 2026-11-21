package me.chius.upcpocket.feature.timetable

/**
 * Official UPC class schedule per 《关于调整作息时间的通知》(2020-05-25).
 * Periods 1..12 have official fixed clock windows; periods 13..14 have no confirmed official
 * schedule and must return null ("时间待确认") instead of being extrapolated.
 */
object TimetableSchedule {
    data class ClockRange(
        val startMinute: Int,
        val endMinute: Int,
    ) {
        val label: String
            get() = "${formatMinute(startMinute)}–${formatMinute(endMinute)}"

        private fun formatMinute(minute: Int): String =
            "%02d:%02d".format(minute / 60, minute % 60)
    }

    private val OFFICIAL_PERIODS: Map<Int, ClockRange> = mapOf(
        1 to ClockRange(8 * 60, 8 * 60 + 45),
        2 to ClockRange(8 * 60 + 50, 9 * 60 + 35),
        3 to ClockRange(9 * 60 + 55, 10 * 60 + 40),
        4 to ClockRange(10 * 60 + 45, 11 * 60 + 30),
        5 to ClockRange(11 * 60 + 35, 12 * 60 + 20),
        6 to ClockRange(14 * 60, 14 * 60 + 45),
        7 to ClockRange(14 * 60 + 50, 15 * 60 + 35),
        8 to ClockRange(15 * 60 + 55, 16 * 60 + 40),
        9 to ClockRange(16 * 60 + 45, 17 * 60 + 30),
        10 to ClockRange(19 * 60, 19 * 60 + 45),
        11 to ClockRange(19 * 60 + 50, 20 * 60 + 35),
        12 to ClockRange(20 * 60 + 40, 21 * 60 + 25),
    )

    private val COURSE_TIME_REGEX = Regex(
        """(\d{1,2})\s*[:：]\s*(\d{2})\s*[~～\-–—]\s*(\d{1,2})\s*[:：]\s*(\d{2})""",
    )

    fun periodRange(period: Int): ClockRange? = OFFICIAL_PERIODS[period]

    fun currentOrNextPeriod(minuteOfDay: Int): Int {
        for (period in 1..12) {
            val range = OFFICIAL_PERIODS[period] ?: continue
            if (minuteOfDay <= range.endMinute) return period
        }
        return 12
    }

    fun meetingClockRange(meeting: CourseMeeting): ClockRange? {
        parseCourseTime(meeting.courseTime)?.let { return it }
        val start = periodRange(meeting.startPeriod) ?: return null
        val end = periodRange(meeting.endPeriod) ?: return null
        if (end.endMinute <= start.startMinute) return null
        return ClockRange(start.startMinute, end.endMinute)
    }

    sealed interface TodayScheduleStatus {
        data class Ongoing(
            val meeting: CourseMeeting,
            val timeRange: String,
            val remainingMinutes: Int,
        ) : TodayScheduleStatus

        data class Upcoming(
            val meeting: CourseMeeting,
            val timeRange: String,
            val minutesUntilStart: Int,
        ) : TodayScheduleStatus

        data class FinishedToday(val completedCount: Int) : TodayScheduleStatus

        data object NoClassToday : TodayScheduleStatus
    }

    fun resolveTodayStatus(
        dayMeetings: List<CourseMeeting>,
        minuteOfDay: Int,
    ): TodayScheduleStatus {
        val sorted = dayMeetings.sortedBy { it.startPeriod }
        if (sorted.isEmpty()) return TodayScheduleStatus.NoClassToday
        for (meeting in sorted) {
            val range = meetingClockRange(meeting) ?: continue
            if (minuteOfDay in range.startMinute until range.endMinute) {
                return TodayScheduleStatus.Ongoing(
                    meeting = meeting,
                    timeRange = range.label,
                    remainingMinutes = (range.endMinute - minuteOfDay).coerceAtLeast(1),
                )
            }
            if (minuteOfDay < range.startMinute) {
                return TodayScheduleStatus.Upcoming(
                    meeting = meeting,
                    timeRange = range.label,
                    minutesUntilStart = range.startMinute - minuteOfDay,
                )
            }
        }
        return TodayScheduleStatus.FinishedToday(sorted.size)
    }

    fun resolveTimeRange(
        startPeriod: Int,
        endPeriod: Int,
        rawCourseTime: String? = null,
    ): String? {
        parseCourseTime(rawCourseTime)?.let { return it.label }
        val start = periodRange(startPeriod) ?: return null
        val end = periodRange(endPeriod) ?: return null
        if (end.endMinute <= start.startMinute) return null
        return ClockRange(start.startMinute, end.endMinute).label
    }

    private fun parseCourseTime(rawCourseTime: String?): ClockRange? {
        val raw = rawCourseTime?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val match = COURSE_TIME_REGEX.find(raw) ?: return null
        val startHour = match.groupValues[1].toIntOrNull() ?: return null
        val startMin = match.groupValues[2].toIntOrNull() ?: return null
        val endHour = match.groupValues[3].toIntOrNull() ?: return null
        val endMin = match.groupValues[4].toIntOrNull() ?: return null
        if (startMin !in 0..59 || endMin !in 0..59) return null
        val start = startHour * 60 + startMin
        val end = endHour * 60 + endMin
        if (start !in 0 until 24 * 60 || end !in 1..24 * 60 || end <= start) return null
        return ClockRange(start, end)
    }
}
