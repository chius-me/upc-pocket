package me.chius.upcpocket.feature.classroom

import java.time.LocalDate
import me.chius.upcpocket.core.model.CampusPreference

enum class FreeClassroomCampus(
    val apiLabel: String,
    val shortLabel: String,
) {
    TANGDAOWAN(apiLabel = "唐岛湾校区", shortLabel = "唐岛湾"),
    GUZHENKOU(apiLabel = "古镇口校区", shortLabel = "古镇口"),
    ;

    companion object {
        fun fromPreferred(campus: CampusPreference): FreeClassroomCampus = when (campus) {
            CampusPreference.GUZHENKOU -> GUZHENKOU
            CampusPreference.TANGDAOWAN, CampusPreference.ALL -> TANGDAOWAN
        }
    }
}

enum class ClassroomPeriodGroup(
    val label: String,
    val periods: Set<Int>,
) {
    MORNING("上午", setOf(1, 2, 3, 4, 5)),
    AFTERNOON("下午", setOf(6, 7, 8, 9)),
    EVENING("晚上", setOf(10, 11, 12, 13)),
    ;

    val rangeLabel: String get() = "${periods.min()}–${periods.max()}"
}

enum class ClassroomQueryMode {
    PRESET,
    CUSTOM,
}

enum class ZhuoyueWingFilter(
    val label: String,
    private val wing: String?,
) {
    ALL("全部", null),
    A("卓越 A", "A"),
    B("卓越 B", "B"),
    ;

    private val roomNamePattern = wing?.let { Regex("^卓越\\s*$it\\s*\\d") }

    fun matches(room: FreeClassroomRoom): Boolean = this == ALL ||
        (room.building == "卓越教学楼" && roomNamePattern!!.containsMatchIn(room.name.trim()))
}

enum class GroupSelectionState {
    NONE,
    PARTIAL,
    ALL,
}

fun groupSelectionState(selected: Set<Int>, group: ClassroomPeriodGroup): GroupSelectionState {
    val count = group.periods.count { it in selected }
    return when (count) {
        0 -> GroupSelectionState.NONE
        group.periods.size -> GroupSelectionState.ALL
        else -> GroupSelectionState.PARTIAL
    }
}

fun toggleGroup(selected: Set<Int>, group: ClassroomPeriodGroup): Set<Int> =
    if (groupSelectionState(selected, group) == GroupSelectionState.ALL) {
        selected - group.periods
    } else {
        selected + group.periods
    }

fun formatPeriods(periods: Set<Int>): String {
    if (periods.isEmpty()) return ""
    val sorted = periods.sorted()
    val ranges = mutableListOf<IntRange>()
    var start = sorted.first()
    var previous = start
    for (value in sorted.drop(1)) {
        if (value == previous + 1) {
            previous = value
        } else {
            ranges += start..previous
            start = value
            previous = value
        }
    }
    ranges += start..previous
    return ranges.joinToString("、") { range ->
        if (range.first == range.last) "${range.first}" else "${range.first}–${range.last}"
    }
}

data class FreeClassroomQuery(
    val campus: FreeClassroomCampus,
    val date: LocalDate,
    val building: String = "全部",
    val periods: Set<Int> = ClassroomPeriodGroup.MORNING.periods,
) {
    fun validate() {
        require(building.isNotBlank() && building.length <= 100) { "请选择教学楼" }
        require(periods.isNotEmpty() && periods.all { it in 1..13 }) { "请选择 1–13 节中的至少一节" }
    }
}

data class FreeClassroomRoom(
    val code: String,
    val name: String,
    val building: String,
    val campus: String,
    val capacity: Int?,
    val available: Boolean,
)

data class FreeClassroomResult(
    val query: FreeClassroomQuery,
    val buildings: List<String>,
    val rooms: List<FreeClassroomRoom>,
    val fetchedAtEpochMillis: Long,
) {
    val availableRooms: List<FreeClassroomRoom>
        get() = rooms.filter { it.available }

    fun filterZhuoyueWing(filter: ZhuoyueWingFilter): FreeClassroomResult =
        if (query.campus != FreeClassroomCampus.GUZHENKOU || filter == ZhuoyueWingFilter.ALL) {
            this
        } else {
            copy(rooms = rooms.filter(filter::matches))
        }

    fun displayGroups(onlyAvailable: Boolean): List<BuildingRoomGroup> =
        rooms.groupBy { it.building }
            .map { (building, groupRooms) ->
                val shown = if (onlyAvailable) groupRooms.filter { it.available } else groupRooms
                BuildingRoomGroup(
                    building = building,
                    rooms = shown,
                    freeCount = groupRooms.count { it.available },
                    totalCount = groupRooms.size,
                )
            }
            .filter { it.rooms.isNotEmpty() }
}

data class BuildingRoomGroup(
    val building: String,
    val rooms: List<FreeClassroomRoom>,
    val freeCount: Int,
    val totalCount: Int,
)
