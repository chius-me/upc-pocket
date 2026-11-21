import Foundation

enum LibrarySystemTab: String, CaseIterable, Identifiable, Hashable, Sendable {
    case libseat
    case chaoxingSeat
    case chaoxingSeatEngine

    var id: String { rawValue }

    var label: String {
        switch self {
        case .libseat: "唐岛湾 libseat"
        case .chaoxingSeat: "古镇口东区"
        case .chaoxingSeatEngine: "求是"
        }
    }

    var shortLabel: String {
        switch self {
        case .libseat: "唐岛湾"
        case .chaoxingSeat: "古镇口"
        case .chaoxingSeatEngine: "求是"
        }
    }

    var officialURL: URL {
        switch self {
        case .libseat: UpcLibraryAuthEndpoints.pageURL
        case .chaoxingSeat: UpcChaoxingAuthEndpoints.seatEntryURL
        case .chaoxingSeatEngine: UpcChaoxingAuthEndpoints.seatEngineEntryURL
        }
    }

    var chaoxingKind: ChaoxingSeatKind? {
        switch self {
        case .libseat: nil
        case .chaoxingSeat: .seat
        case .chaoxingSeatEngine: .seatEngine
        }
    }

    static func defaultForCampus(_ campus: CampusPreference) -> LibrarySystemTab {
        campus == .guzhenkou ? .chaoxingSeat : .libseat
    }
}

struct LibraryUser: Equatable, Sendable {
    let id: String
    let username: String
    let name: String
}

struct LibraryBuilding: Identifiable, Equatable, Hashable, Sendable {
    let id: String
    let name: String
}

struct LibraryFilters: Equatable, Sendable {
    let dates: [String]
    let buildings: [LibraryBuilding]
}

struct LibraryFloor: Identifiable, Equatable, Hashable, Sendable {
    let floorNum: Int
    let floorName: String

    var id: Int { floorNum }
}

struct LibraryRoom: Identifiable, Equatable, Hashable, Sendable {
    let roomId: String
    let name: String
    let floor: Int
    let floorName: String?
    let totalSeats: Int?
    let freeSeats: Int?
    let reserved: Int?
    let inUse: Int?
    let away: Int?
    let disabled: Int?
    let maxHour: Int?

    var id: String { roomId }

    var floorLabel: String {
        if let floorName, !floorName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return floorName
        }
        return floor > 0 ? "楼层 \(floor)" : "楼层未提供"
    }

    var remainingLabel: String {
        switch (freeSeats, totalSeats) {
        case let (.some(free), .some(total)):
            return "余 \(free) / 共 \(total) 座"
        case let (.some(free), .none):
            return "余 \(free) 座"
        case let (.none, .some(total)):
            return "共 \(total) 座"
        case (.none, .none):
            return "座位统计待查询"
        }
    }

    var maxDurationLabel: String? {
        guard let maxHour, maxHour > 0 else { return nil }
        return "学校单次最长 \(maxHour) 小时"
    }

    var occupancy: Double? {
        guard let total = totalSeats, let free = freeSeats, total > 0 else { return nil }
        return min(max(Double(free) / Double(total), 0), 1)
    }
}

struct LibrarySeat: Identifiable, Equatable, Hashable, Sendable {
    let id: String
    let name: String
    let row: Int
    let col: Int
    let type: String
    let enabled: Bool
    let status: String
    let localOnly: Bool

    var isSeat: Bool {
        type.caseInsensitiveCompare("seat") == .orderedSame
    }

    var selectable: Bool {
        isSeat && enabled && !localOnly && (status == "FREE" || status == "YESSELECT")
    }
}

struct LibrarySeatLayout: Equatable, Sendable {
    let rows: Int
    let cols: Int
    let seats: [LibrarySeat]
}

struct LibraryReservation: Identifiable, Equatable, Hashable, Sendable {
    let id: String
    let date: String
    let begin: String
    let end: String
    let location: String
    let status: String

    var statusLabel: String {
        switch status {
        case "RESERVE": "已预约"
        case "CHECK_IN": "履约中"
        case "AWAY": "暂离"
        case "COMPLETE": "已履约"
        case "INCOMPLETE": "早退"
        case "MISS": "失约"
        case "CANCEL": "已取消"
        case "STOP": "结束使用"
        default: status.isEmpty ? "未知状态" : status
        }
    }
}

enum LibrarySeatStatus: Sendable {
    static func label(for status: String) -> String {
        switch status {
        case "FREE": "可用"
        case "AWAY": "暂离"
        case "IN_USE": "使用中"
        case "BOOKED": "已预约"
        case "FULL": "已满"
        case "NOSELECT": "不可选"
        case "YESSELECT": "已选"
        default: status.isEmpty ? "未知" : status
        }
    }
}

enum LibrarySeatError: Error, LocalizedError, Equatable, Sendable {
    case sessionExpired(String)
    case message(String)

    var errorDescription: String? {
        switch self {
        case let .sessionExpired(msg), let .message(msg):
            msg
        }
    }
}

enum ChaoxingSeatKind: String, Sendable {
    case seat
    case seatEngine = "seatengine"

    var api: String { rawValue }

    var campusLabel: String {
        switch self {
        case .seat: "古镇口东区"
        case .seatEngine: "求是"
        }
    }

    var officialURL: URL {
        switch self {
        case .seat: UpcChaoxingAuthEndpoints.seatEntryURL
        case .seatEngine: UpcChaoxingAuthEndpoints.seatEngineEntryURL
        }
    }
}

struct ChaoxingUser: Equatable, Sendable {
    let sno: String
}

struct ChaoxingSeatRoom: Identifiable, Equatable, Hashable, Sendable {
    let id: Int
    let name: String
    let floor: String
    let capacity: Int
    let usedCount: Int?
    let startMinute: Int
    let endMinute: Int

    var remainingEstimate: Int? {
        usedCount.map { max(capacity - $0, 0) }
    }

    var openHoursLabel: String {
        "\(LibraryTimeFormatter.clockMinute(startMinute))–\(LibraryTimeFormatter.clockMinute(endMinute))"
    }
}

struct ChaoxingSeatRoomPage: Equatable, Sendable {
    let rooms: [ChaoxingSeatRoom]
    let totalRow: Int
    let totalPage: Int
}

struct ChaoxingSeatPlace: Identifiable, Equatable, Hashable, Sendable {
    let seatNum: String
    let x: Double
    let y: Double
    let width: Double
    let height: Double
    let rotate: Double

    var id: String { seatNum }
}

struct ChaoxingSeatRoomDetail: Equatable, Sendable {
    let roomId: Int
    let name: String
    let capacity: Int
    let timeUnit: Int
    let startMinute: Int
    let endMinute: Int
    let places: [ChaoxingSeatPlace]
    let disabledSeatNums: Set<String>
    let seatRulesKnown: Bool
    let minDurationMinutes: Int?
    let maxDurationMinutes: Int?
    let closed: Bool
}

struct ChaoxingTimeInterval: Identifiable, Equatable, Hashable, Sendable {
    let startMinute: Int
    let endMinute: Int

    var id: String { "\(startMinute)-\(endMinute)" }

    var label: String {
        "\(LibraryTimeFormatter.clockMinute(startMinute))–\(LibraryTimeFormatter.clockMinute(endMinute))"
    }
}

enum ChaoxingProtocolError: Error, LocalizedError, Equatable, Sendable {
    case sessionExpired
    case protocolError(String)
    case upstream(String)
    case rateLimited

    var errorDescription: String? {
        switch self {
        case .sessionExpired:
            "超星座位会话已失效，请重新验证"
        case let .protocolError(msg), let .upstream(msg):
            msg
        case .rateLimited:
            "请求过于频繁，请稍后重试"
        }
    }
}

enum LibraryConnectionStatus: Equatable, Sendable {
    case unconfigured
    case recoveryRequired
    case idle
    case authenticating
    case ready
    case interactionRequired
    case invalidCredentials
    case error
}

struct LibraryInteractionRequest: Equatable, Sendable {
    let system: LibrarySystemTab
    let sessionContext: ControlledWebViewPolicy.SessionContext
    let scopeHosts: Set<String>
    let targetHost: String
    let targetCookieDomains: Set<String>
    let completionPathPrefixes: Set<String>
    let usesURLTokenCapture: Bool
    let reason: String
}

struct LibrarySeatUiState: Equatable, Sendable {
    var activeTab: LibrarySystemTab = .libseat
    var connectionStatus: LibraryConnectionStatus = .unconfigured
    var statusMessage: String?
    var isLoading = false
    var isLoadingRoomDetail = false
    var errorMessage: String?
    var interactionRequest: LibraryInteractionRequest?
    var libseatDates: [String] = []
    var libseatSelectedDate: String?
    var libseatBuildings: [LibraryBuilding] = []
    var libseatSelectedBuilding: LibraryBuilding?
    var libseatFloors: [LibraryFloor] = []
    var libseatSelectedFloor: LibraryFloor?
    var libseatRooms: [LibraryRoom] = []
    var libseatSelectedRoom: LibraryRoom?
    var libseatSelectedRoomLayout: LibrarySeatLayout?
    var libseatCurrentReservations: [LibraryReservation] = []
    var libseatHistoryReservations: [LibraryReservation] = []
    var chaoxingAvailableDates: [String] = []
    var chaoxingSelectedDate = ""
    var chaoxingRooms: [ChaoxingSeatRoom] = []
    var chaoxingSelectedRoom: ChaoxingSeatRoom?
    var chaoxingSelectedRoomDetail: ChaoxingSeatRoomDetail?
    var chaoxingSelectedStartMinute = 8 * 60
    var chaoxingSelectedEndMinute = 12 * 60
    var chaoxingUsedSeatNums: Set<String> = []
    var chaoxingSelectedSeatNum: String?
    var chaoxingSelectedSeatUsedTimes: [ChaoxingTimeInterval] = []
}

enum LibraryTimeFormatter: Sendable {
    static func clockMinute(_ minute: Int) -> String {
        let clamped = min(max(minute, 0), 24 * 60)
        return String(format: "%02d:%02d", clamped / 60, clamped % 60)
    }
}
