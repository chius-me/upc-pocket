import Foundation

struct TimetableTermInfo: Codable, Equatable, Hashable, Identifiable, Sendable {
    let year: String
    let term: String
    let startDayIso: String
    let totalWeeks: Int
    let serverId: String?
    let label: String

    var id: String { "\(year)-\(term)" }
    var termKey: String { "\(year)-\(term)" }
}

struct TimetableIndex: Codable, Equatable, Sendable {
    let terms: [TimetableTermInfo]
    let currentYear: String?
    let currentTerm: String?
    let currentWeek: Int?
    let currentWeekdaysIso: [String]?
    let currentWeekday: Int?

    func findTerm(year: String, term: String) -> TimetableTermInfo? {
        terms.first { $0.year == year && $0.term == term }
    }

    func currentTermOrNull() -> TimetableTermInfo? {
        guard let currentYear, let currentTerm else { return nil }
        return findTerm(year: currentYear, term: currentTerm)
    }
}

struct CourseMeeting: Codable, Equatable, Hashable, Identifiable, Sendable {
    let courseName: String
    let weekday: Int // 1=Mon .. 7=Sun
    let startPeriod: Int
    let endPeriod: Int
    let periods: [Int]
    let sourcePeriods: [Int]
    let weeks: [Int]
    let weekLabel: String
    let dateIso: String
    let timeRangeLabel: String?
    let teacher: String?
    let location: String?
    let courseId: String?
    let weekType: String?
    let lessons: String?
    let courseTime: String?
    let sectionsSource: String
    let credit: String?
    let courseType: String?
    let courseHour: String?
    let examType: String?

    var id: String {
        "\(courseId ?? "")|\(courseName)|\(teacher ?? "")|\(dateIso)|\(startPeriod)-\(endPeriod)|\(location ?? "")"
    }

    var periodRangeLabel: String {
        startPeriod == endPeriod ? "第 \(startPeriod) 节" : "第 \(startPeriod)–\(endPeriod) 节"
    }

    var effectiveTimeText: String {
        timeRangeLabel ?? "时间待确认"
    }
}

struct TimetableDay: Codable, Equatable, Identifiable, Sendable {
    let weekday: Int
    let dateIso: String
    let meetings: [CourseMeeting]

    var id: String { dateIso }
}

struct TimetableWeek: Codable, Equatable, Sendable {
    let term: TimetableTermInfo
    let week: Int
    let datesIso: [String]
    let days: [TimetableDay]
    let meetings: [CourseMeeting]
    let fetchedAtEpochMillis: Int64
    let complete: Bool

    var isEmpty: Bool { complete && meetings.isEmpty }
}

struct TimetableOfflineCache: Codable, Equatable, Sendable {
    let accountId: String
    let cachedAtEpochMillis: Int64
    let index: TimetableIndex?
    let weeks: [TimetableWeek]
}

struct TodayCourseSummary: Equatable, Sendable {
    let dateIso: String
    let weekNumber: Int?
    let courses: [CourseMeeting]
    let isOfflineCache: Bool
    let fetchedAtEpochMillis: Int64?
}

enum TimetableConnectionStatus: Equatable, Sendable {
    case unconfigured
    case recoveryRequired
    case idle
    case loading
    case online
    case offlineCache
    case interactionRequired
    case invalidCredentials
    case error
}

struct TimetableUiState: Equatable, Sendable {
    var connectionStatus: TimetableConnectionStatus = .unconfigured
    var terms: [TimetableTermInfo] = []
    var selectedTerm: TimetableTermInfo? = nil
    var selectedWeek: Int? = nil
    var currentSchoolWeek: Int? = nil
    var weekData: TimetableWeek? = nil
    var todaySummary: TodayCourseSummary? = nil
    var isRefreshing: Bool = false
    var isOfflineFallback: Bool = false
    var fetchedAtEpochMillis: Int64? = nil
    var statusDetail: String? = nil
    var errorMessage: String? = nil
    var interactionSession: ControlledWebViewPolicy.SessionContext? = nil
    var interactionReason: String? = nil
}
