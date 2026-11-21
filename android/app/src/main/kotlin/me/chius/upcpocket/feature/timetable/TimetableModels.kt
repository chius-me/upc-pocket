package me.chius.upcpocket.feature.timetable

import kotlinx.serialization.Serializable
import me.chius.upcpocket.core.webview.ControlledWebViewContract

@Serializable
data class TimetableTermInfo(
    val year: String,
    val term: String,
    val startDayIso: String,
    val totalWeeks: Int,
    val id: String? = null,
    val label: String,
) {
    init {
        require(year.isNotBlank() && term.isNotBlank()) { "Term identifier must not be blank" }
        require(totalWeeks in 1..30) { "totalWeeks must be in 1..30" }
        require(label.isNotBlank()) { "Term label must not be blank" }
    }
}

@Serializable
data class TimetableIndex(
    val terms: List<TimetableTermInfo>,
    val currentYear: String? = null,
    val currentTerm: String? = null,
    val currentWeek: Int? = null,
    val currentWeekdaysIso: List<String>? = null,
    val currentWeekday: Int? = null,
) {
    fun findTerm(year: String, term: String): TimetableTermInfo? =
        terms.firstOrNull { it.year == year && it.term == term }

    fun currentTermOrNull(): TimetableTermInfo? {
        val year = currentYear ?: return null
        val term = currentTerm ?: return null
        return findTerm(year, term)
    }
}

@Serializable
data class CourseMeeting(
    val courseName: String,
    val weekday: Int, // 1=Mon .. 7=Sun
    val startPeriod: Int,
    val endPeriod: Int,
    val periods: List<Int>,
    val sourcePeriods: List<Int>,
    val weeks: List<Int>,
    val weekLabel: String,
    val dateIso: String,
    val timeRangeLabel: String? = null,
    val teacher: String? = null,
    val location: String? = null,
    val courseId: String? = null,
    val weekType: String? = null,
    val lessons: String? = null,
    val courseTime: String? = null,
    val sectionsSource: String = "lessArr",
    val credit: String? = null,
    val courseType: String? = null,
    val courseHour: String? = null,
    val examType: String? = null,
) {
    val periodRangeLabel: String
        get() = if (startPeriod == endPeriod) {
            "第 $startPeriod 节"
        } else {
            "第 $startPeriod–$endPeriod 节"
        }

    val effectiveTimeText: String
        get() = timeRangeLabel ?: "时间待确认"
}

@Serializable
data class TimetableDay(
    val weekday: Int, // 1=Mon .. 7=Sun
    val dateIso: String,
    val meetings: List<CourseMeeting>,
)

@Serializable
data class TimetableWeek(
    val term: TimetableTermInfo,
    val week: Int,
    val datesIso: List<String>,
    val days: List<TimetableDay>,
    val meetings: List<CourseMeeting>,
    val fetchedAtEpochMillis: Long,
    val complete: Boolean = true,
) {
    val isEmpty: Boolean
        get() = complete && meetings.isEmpty()
}

@Serializable
data class TimetableOfflineCache(
    val accountId: String,
    val cachedAtEpochMillis: Long,
    val index: TimetableIndex? = null,
    val weeks: List<TimetableWeek> = emptyList(),
)

data class TodayCourseSummary(
    val dateIso: String,
    val weekNumber: Int?,
    val courses: List<CourseMeeting>,
    val isOfflineCache: Boolean,
    val fetchedAtEpochMillis: Long?,
)

enum class TimetableConnectionStatus {
    UNCONFIGURED,
    RECOVERY_REQUIRED,
    IDLE,
    LOADING,
    ONLINE,
    OFFLINE_CACHE,
    INTERACTION_REQUIRED,
    INVALID_CREDENTIALS,
    ERROR,
}

data class TimetableUiState(
    val connectionStatus: TimetableConnectionStatus = TimetableConnectionStatus.UNCONFIGURED,
    val terms: List<TimetableTermInfo> = emptyList(),
    val selectedTerm: TimetableTermInfo? = null,
    val selectedWeek: Int? = null,
    val currentSchoolWeek: Int? = null,
    val weekData: TimetableWeek? = null,
    val todaySummary: TodayCourseSummary? = null,
    val isRefreshing: Boolean = false,
    val isOfflineFallback: Boolean = false,
    val fetchedAtEpochMillis: Long? = null,
    val statusDetail: String? = null,
    val errorMessage: String? = null,
    val interactionSession: ControlledWebViewContract.SessionContext? = null,
    val interactionReason: String? = null,
)
