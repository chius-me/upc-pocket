import Foundation

enum FocusMode: String, CaseIterable, Codable, Identifiable, Sendable {
    case focus25
    case shortBreak5
    case deep45

    var id: String { rawValue }

    var label: String {
        switch self {
        case .focus25: "专注 25 分钟"
        case .shortBreak5: "短休 5 分钟"
        case .deep45: "深度 45 分钟"
        }
    }

    var durationSeconds: Int {
        switch self {
        case .focus25: 25 * 60
        case .shortBreak5: 5 * 60
        case .deep45: 45 * 60
        }
    }

    var isFocus: Bool {
        self != .shortBreak5
    }
}

struct FocusTimerSnapshot: Codable, Equatable, Sendable {
    var mode: FocusMode = .focus25
    var running: Bool = false
    var startedAt: Int64 = 0
    var plannedSeconds: Int = FocusMode.focus25.durationSeconds
    var pausedTotalMs: Int64 = 0
    var pausedAt: Int64 = 0
    var focusRound: Int = 0

    var isIdle: Bool { startedAt == 0 }
    var isPaused: Bool { !isIdle && pausedAt > 0 }
}

enum PomodoroClock: Sendable {
    static func pausedMs(_ snapshot: FocusTimerSnapshot, nowMillis: Int64) -> Int64 {
        var paused = snapshot.pausedTotalMs
        if snapshot.pausedAt > 0 {
            paused += max(0, nowMillis - snapshot.pausedAt)
        }
        return max(0, paused)
    }

    static func elapsedSeconds(_ snapshot: FocusTimerSnapshot, nowMillis: Int64) -> Int {
        guard snapshot.startedAt > 0 else { return 0 }
        let paused = pausedMs(snapshot, nowMillis: nowMillis)
        let rawMs = nowMillis - snapshot.startedAt - paused
        return Int(max(0, rawMs / 1000))
    }

    static func remainingSeconds(_ snapshot: FocusTimerSnapshot, nowMillis: Int64) -> Int {
        guard snapshot.startedAt > 0 else { return snapshot.plannedSeconds }
        return max(0, snapshot.plannedSeconds - elapsedSeconds(snapshot, nowMillis: nowMillis))
    }

    static func progress(_ snapshot: FocusTimerSnapshot, nowMillis: Int64) -> Double {
        guard snapshot.plannedSeconds > 0 else { return 0 }
        guard snapshot.startedAt > 0 else { return 1 }
        let ratio = Double(remainingSeconds(snapshot, nowMillis: nowMillis)) / Double(snapshot.plannedSeconds)
        return min(max(0, ratio), 1)
    }

    static func isFinished(_ snapshot: FocusTimerSnapshot, nowMillis: Int64) -> Bool {
        snapshot.startedAt != 0 && remainingSeconds(snapshot, nowMillis: nowMillis) <= 0
    }

    static func format(_ seconds: Int) -> String {
        let safe = max(0, seconds)
        return String(format: "%02d:%02d", safe / 60, safe % 60)
    }
}

struct FocusRecord: Codable, Equatable, Identifiable, Sendable {
    let id: String
    let mode: FocusMode
    let startedAt: Int64
    let endedAt: Int64
    let actualSeconds: Int
    let completed: Bool
}

struct FocusTotals: Equatable, Sendable {
    var seconds: Int = 0
    var sessions: Int = 0
}

struct FocusSummary: Equatable, Sendable {
    var today = FocusTotals()
    var week = FocusTotals()
    var month = FocusTotals()
    var allTime = FocusTotals()
}

/// Pure focus statistics using a 04:00 AM study-day cutoff and Monday week start.
enum FocusStats: Sendable {
    static let dayStartHour = 4

    static func dayStartDate(for epochMillis: Int64, timeZone: TimeZone = .current) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let date = Date(timeIntervalSince1970: TimeInterval(epochMillis) / 1000.0)
        let hour = calendar.component(.hour, from: date)
        let dayAnchor: Date = if hour < dayStartHour {
            calendar.date(byAdding: .day, value: -1, to: date) ?? date
        } else {
            date
        }
        var comps = calendar.dateComponents([.year, .month, .day], from: dayAnchor)
        comps.hour = dayStartHour
        comps.minute = 0
        comps.second = 0
        return calendar.date(from: comps) ?? dayAnchor
    }

    static func dayKeyString(for epochMillis: Int64, timeZone: TimeZone = .current) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let start = dayStartDate(for: epochMillis, timeZone: timeZone)
        let comps = calendar.dateComponents([.year, .month, .day], from: start)
        return String(format: "%04d-%02d-%02d", comps.year ?? 2026, comps.month ?? 1, comps.day ?? 1)
    }

    static func totals(for records: [FocusRecord]) -> FocusTotals {
        var seconds = 0
        var sessions = 0
        for rec in records where rec.mode.isFocus {
            seconds += max(0, rec.actualSeconds)
            if rec.completed {
                sessions += 1
            }
        }
        return FocusTotals(seconds: seconds, sessions: sessions)
    }

    static func summarize(
        records: [FocusRecord],
        nowMillis: Int64,
        timeZone: TimeZone = .current
    ) -> FocusSummary {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone

        let todayStart = dayStartDate(for: nowMillis, timeZone: timeZone)
        let todayEnd = calendar.date(byAdding: .day, value: 1, to: todayStart) ?? todayStart

        // Weekday: 1=Sun, 2=Mon .. 7=Sat -> days since Monday
        let weekday = calendar.component(.weekday, from: todayStart)
        let daysFromMonday = (weekday + 5) % 7
        let weekStart = calendar.date(byAdding: .day, value: -daysFromMonday, to: todayStart) ?? todayStart

        var monthComps = calendar.dateComponents([.year, .month], from: todayStart)
        monthComps.day = 1
        monthComps.hour = dayStartHour
        monthComps.minute = 0
        monthComps.second = 0
        let monthStart = calendar.date(from: monthComps) ?? todayStart

        let todayMs = Int64(todayStart.timeIntervalSince1970 * 1000)
        let todayEndMs = Int64(todayEnd.timeIntervalSince1970 * 1000)
        let weekMs = Int64(weekStart.timeIntervalSince1970 * 1000)
        let monthMs = Int64(monthStart.timeIntervalSince1970 * 1000)

        return FocusSummary(
            today: totals(for: records.filter { $0.startedAt >= todayMs && $0.startedAt < todayEndMs }),
            week: totals(for: records.filter { $0.startedAt >= weekMs && $0.startedAt < todayEndMs }),
            month: totals(for: records.filter { $0.startedAt >= monthMs && $0.startedAt < todayEndMs }),
            allTime: totals(for: records)
        )
    }

    static func humanDuration(_ seconds: Int) -> String {
        guard seconds > 0 else { return "0 分钟" }
        let hours = seconds / 3600
        let minutes = (seconds % 3600) / 60
        if hours > 0 && minutes > 0 {
            return "\(hours) 小时 \(minutes) 分"
        } else if hours > 0 {
            return "\(hours) 小时"
        } else if minutes > 0 {
            return "\(minutes) 分钟"
        } else {
            return "不到 1 分钟"
        }
    }
}

struct FocusPersistenceState: Codable, Sendable {
    var version: Int = 1
    var snapshot = FocusTimerSnapshot()
    var records: [FocusRecord] = []
}

struct FocusUiState: Equatable, Sendable {
    var mode: FocusMode = .focus25
    var remainingSeconds: Int = FocusMode.focus25.durationSeconds
    var elapsedSeconds: Int = 0
    var isRunning: Bool = false
    var isPaused: Bool = false
    var isIdle: Bool = true
    var progress: Double = 1.0
    var formattedTime: String = PomodoroClock.format(FocusMode.focus25.durationSeconds)
    var focusRound: Int = 0
    var summary = FocusSummary()
    var recentRecords: [FocusRecord] = []
}
