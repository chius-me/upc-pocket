package me.chius.upcpocket.feature.library

import java.io.IOException
import java.util.Locale
import me.chius.upcpocket.core.auth.UpcChaoxingAuthEndpoints
import me.chius.upcpocket.core.auth.UpcLibraryAuthEndpoints
import me.chius.upcpocket.core.model.CampusPreference
import me.chius.upcpocket.core.webview.ControlledWebViewContract

enum class LibrarySystemTab(
    val label: String,
    val subtitle: String,
    val officialUrl: String,
) {
    LIBSEAT(
        label = "唐岛湾 libseat",
        subtitle = "libspace.upc.edu.cn",
        officialUrl = UpcLibraryAuthEndpoints.PAGE_URL,
    ),
    CHAOXING_SEAT(
        label = "古镇口东区",
        subtitle = "office.chaoxing.com · seat",
        officialUrl = UpcChaoxingAuthEndpoints.SEAT_ENTRY,
    ),
    CHAOXING_SEATENGINE(
        label = "求是",
        subtitle = "office.chaoxing.com · seatengine",
        officialUrl = UpcChaoxingAuthEndpoints.SEATENGINE_ENTRY,
    ),
    ;

    fun toChaoxingKind(): ChaoxingSeatKind? = when (this) {
        LIBSEAT -> null
        CHAOXING_SEAT -> ChaoxingSeatKind.SEAT
        CHAOXING_SEATENGINE -> ChaoxingSeatKind.SEATENGINE
    }

    companion object {
        fun defaultForCampus(campus: CampusPreference): LibrarySystemTab = when (campus) {
            CampusPreference.GUZHENKOU -> CHAOXING_SEAT
            CampusPreference.TANGDAOWAN, CampusPreference.ALL -> LIBSEAT
        }
    }
}

data class LibraryUser(
    val id: String,
    val username: String,
    val name: String,
)

data class LibraryFilters(
    val dates: List<String>,
    val buildings: List<LibraryBuilding>,
) {
    init {
        require(dates.isNotEmpty()) { "学校未返回可预约日期" }
        require(buildings.isNotEmpty()) { "学校未返回馆区" }
    }
}

data class LibraryBuilding(
    val id: String,
    val name: String,
)

data class LibraryFloor(
    val floorNum: Int,
    val floorName: String,
)

data class LibraryRoom(
    val roomId: String,
    val name: String,
    val floor: Int,
    val floorName: String? = null,
    val totalSeats: Int? = null,
    val freeSeats: Int? = null,
    val reserved: Int? = null,
    val inUse: Int? = null,
    val away: Int? = null,
    val disabled: Int? = null,
    val maxHour: Int? = null,
) {
    val floorLabel: String
        get() = floorName?.takeIf { it.isNotBlank() }
            ?: if (floor > 0) "楼层 $floor" else "楼层未提供"

    val remainingLabel: String
        get() = when {
            freeSeats != null && totalSeats != null -> "余 $freeSeats / 共 $totalSeats 座"
            freeSeats != null -> "余 $freeSeats 座"
            totalSeats != null -> "共 $totalSeats 座"
            else -> "座位统计待查询"
        }

    val maxDurationLabel: String?
        get() = maxHour?.takeIf { it > 0 }?.let { "学校单次最长 $it 小时" }

    val occupancy: Float?
        get() {
            val total = totalSeats ?: return null
            val free = freeSeats ?: return null
            if (total <= 0) return null
            return (free.toFloat() / total).coerceIn(0f, 1f)
        }
}

data class LibrarySeat(
    val id: String,
    val name: String,
    val row: Int,
    val col: Int,
    val type: String,
    val enabled: Boolean,
    val status: String,
    val localOnly: Boolean,
) {
    val isSeat: Boolean get() = type.equals("seat", ignoreCase = true)
    val selectable: Boolean get() = isSeat && enabled && !localOnly && status in setOf("FREE", "YESSELECT")
}

data class LibrarySeatLayout(
    val rows: Int,
    val cols: Int,
    val seats: List<LibrarySeat>,
)

data class LibraryReservation(
    val id: String,
    val date: String,
    val begin: String,
    val end: String,
    val location: String,
    val status: String,
) {
    val cancellable: Boolean get() = status == "RESERVE"

    val statusLabel: String
        get() = when (status) {
            "RESERVE" -> "已预约"
            "CHECK_IN" -> "履约中"
            "AWAY" -> "暂离"
            "COMPLETE" -> "已履约"
            "INCOMPLETE" -> "早退"
            "MISS" -> "失约"
            "CANCEL" -> "已取消"
            "STOP" -> "结束使用"
            else -> status.ifBlank { "未知状态" }
        }
}

data class LibraryTimeOption(
    val id: String,
    val value: String,
) {
    val normalizedId: String
        get() = if (id.equals("now", ignoreCase = true) || id == "-1") "-1" else id
}

object LibrarySeatStatus {
    fun label(status: String): String = when (status) {
        "FREE" -> "可用"
        "AWAY" -> "暂离"
        "IN_USE" -> "使用中"
        "BOOKED" -> "已预约"
        "FULL" -> "已满"
        "NOSELECT" -> "不可选"
        "YESSELECT" -> "已选"
        else -> status.ifBlank { "未知" }
    }
}

open class LibrarySeatException(message: String, cause: Throwable? = null) : IOException(message, cause)

class LibrarySessionExpiredException(message: String = "图书馆登录已失效，请重新验证") :
    LibrarySeatException(message)

enum class ChaoxingSeatKind(
    val api: String,
    val campusLabel: String,
    val officialUrl: String,
) {
    SEAT(
        api = "seat",
        campusLabel = "古镇口东区",
        officialUrl = UpcChaoxingAuthEndpoints.SEAT_ENTRY,
    ),
    SEATENGINE(
        api = "seatengine",
        campusLabel = "求是",
        officialUrl = UpcChaoxingAuthEndpoints.SEATENGINE_ENTRY,
    ),
}

data class ChaoxingUser(
    val sno: String,
)

data class ChaoxingSeatRoom(
    val id: Int,
    val name: String,
    val floor: String,
    val capacity: Int,
    val usedCount: Int?,
    val startMinute: Int,
    val endMinute: Int,
) {
    val remainingEstimate: Int?
        get() = usedCount?.let { (capacity - it).coerceAtLeast(0) }

    val openHoursLabel: String
        get() = "${formatClockMinute(startMinute)}–${formatClockMinute(endMinute)}"
}

data class ChaoxingSeatRoomPage(
    val rooms: List<ChaoxingSeatRoom>,
    val totalRow: Int,
    val totalPage: Int,
)

data class ChaoxingSeatPlace(
    val seatNum: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotate: Float,
)

data class ChaoxingSeatRoomDetail(
    val roomId: Int,
    val name: String,
    val capacity: Int,
    val timeUnit: Int,
    val startMinute: Int,
    val endMinute: Int,
    val places: List<ChaoxingSeatPlace>,
    val disabledSeatNums: Set<String> = emptySet(),
    val seatRulesKnown: Boolean = false,
    val minDurationMinutes: Int? = null,
    val maxDurationMinutes: Int? = null,
    val closed: Boolean = false,
)

data class ChaoxingTimeInterval(
    val startMinute: Int,
    val endMinute: Int,
) {
    val label: String
        get() = "${formatClockMinute(startMinute)}–${formatClockMinute(endMinute)}"
}

data class ChaoxingSelectSecrets(
    val submitEnc: String,
    val wfwEngineEnc: String,
    val riskCheckRequired: Boolean,
)

sealed interface ChaoxingReservationResult {
    data class Success(val seatNum: String, val day: String) : ChaoxingReservationResult
    data class Rejected(val message: String) : ChaoxingReservationResult
    data class InteractionRequired(val message: String) : ChaoxingReservationResult
    /** HTTP POST reached upstream but final status could not be confirmed; never auto-retry. */
    data class Unknown(val message: String) : ChaoxingReservationResult
}

open class ChaoxingException(
    message: String,
    val code: String = "chaoxing_protocol",
    cause: Throwable? = null,
) : IOException(message, cause)

class ChaoxingSessionExpiredException(message: String = "超星座位会话已失效，请重新验证") :
    ChaoxingException(message, "chaoxing_session_expired")

enum class LibraryConnectionStatus {
    UNCONFIGURED,
    RECOVERY_REQUIRED,
    IDLE,
    AUTHENTICATING,
    READY,
    INTERACTION_REQUIRED,
    INVALID_CREDENTIALS,
    ERROR,
}

data class LibraryInteractionRequest(
    val system: LibrarySystemTab,
    val sessionContext: ControlledWebViewContract.SessionContext,
    val scopeHosts: Set<String>,
    val targetHost: String,
    val completionPathPrefixes: Set<String>,
    val allowCompletionWithoutCookies: Boolean,
    val reason: String,
)

data class LibrarySeatUiState(
    val activeTab: LibrarySystemTab = LibrarySystemTab.LIBSEAT,
    val connectionStatus: LibraryConnectionStatus = LibraryConnectionStatus.UNCONFIGURED,
    val statusMessage: String? = null,
    val isLoading: Boolean = false,
    val isLoadingRoomDetail: Boolean = false,
    val errorMessage: String? = null,
    val interactionRequest: LibraryInteractionRequest? = null,
    val libseatDates: List<String> = emptyList(),
    val libseatSelectedDate: String? = null,
    val libseatBuildings: List<LibraryBuilding> = emptyList(),
    val libseatSelectedBuilding: LibraryBuilding? = null,
    val libseatFloors: List<LibraryFloor> = emptyList(),
    val libseatSelectedFloor: LibraryFloor? = null,
    val libseatRooms: List<LibraryRoom> = emptyList(),
    val libseatSelectedRoom: LibraryRoom? = null,
    val libseatSelectedRoomLayout: LibrarySeatLayout? = null,
    val libseatSelectedSeat: LibrarySeat? = null,
    val libseatStartTimes: List<LibraryTimeOption> = emptyList(),
    val libseatEndTimes: List<LibraryTimeOption> = emptyList(),
    val libseatSelectedStart: LibraryTimeOption? = null,
    val libseatSelectedEnd: LibraryTimeOption? = null,
    val isLoadingSeatTimes: Boolean = false,
    val isSubmittingReservation: Boolean = false,
    val cancellingReservationId: String? = null,
    val reservationMessage: String? = null,
    val libseatCurrentReservations: List<LibraryReservation> = emptyList(),
    val libseatHistoryReservations: List<LibraryReservation> = emptyList(),
    val chaoxingAvailableDates: List<String> = emptyList(),
    val chaoxingSelectedDate: String = "",
    val chaoxingRooms: List<ChaoxingSeatRoom> = emptyList(),
    val chaoxingSelectedRoom: ChaoxingSeatRoom? = null,
    val chaoxingSelectedRoomDetail: ChaoxingSeatRoomDetail? = null,
    val chaoxingSelectedStartMinute: Int = 8 * 60,
    val chaoxingSelectedEndMinute: Int = 12 * 60,
    val chaoxingUsedSeatNums: Set<String> = emptySet(),
    val chaoxingSelectedSeatNum: String? = null,
    val chaoxingSelectedSeatUsedTimes: List<ChaoxingTimeInterval> = emptyList(),
    val chaoxingSubmissionUnknownKey: String? = null,
)

fun formatClockMinute(minute: Int): String {
    val clamped = minute.coerceIn(0, 24 * 60)
    return "%02d:%02d".format(Locale.ROOT, clamped / 60, clamped % 60)
}
