import Foundation

/// Deterministic ordering aligned with Android `TodoOrdering`:
/// - Incomplete: `dueAt != nil` sorted ascending by `dueAt` (tie-break `id`), followed by
///   `dueAt == nil` sorted descending by `createdAt` (tie-break `id`).
/// - `reminderAt` never participates in ordering priority.
/// - Completed: descending by `completedAt ?? updatedAt` (tie-break `id`).
enum TodoOrdering: Sendable {
    static func incomplete(_ tasks: [TodoTask]) -> [TodoTask] {
        let pending = tasks.filter { !$0.completed }
        let withDeadline = pending.filter { $0.dueAt != nil }.sorted { lhs, rhs in
            if lhs.dueAt! != rhs.dueAt! {
                return lhs.dueAt! < rhs.dueAt!
            }
            return lhs.id < rhs.id
        }
        let withoutDeadline = pending.filter { $0.dueAt == nil }.sorted { lhs, rhs in
            if lhs.createdAt != rhs.createdAt {
                return lhs.createdAt > rhs.createdAt
            }
            return lhs.id < rhs.id
        }
        return withDeadline + withoutDeadline
    }

    static func completed(_ tasks: [TodoTask]) -> [TodoTask] {
        tasks.filter(\.completed).sorted { lhs, rhs in
            let lTime = lhs.completedAt ?? lhs.updatedAt
            let rTime = rhs.completedAt ?? rhs.updatedAt
            if lTime != rTime {
                return lTime > rTime
            }
            return lhs.id < rhs.id
        }
    }

    static func todayScheduleSummary(_ tasks: [TodoTask], limit: Int = 3) -> [TodoTask] {
        Array(incomplete(tasks).prefix(limit))
    }

    static func isOverdueOrToday(
        _ task: TodoTask,
        nowMillis: Int64,
        timeZone: TimeZone = .current
    ) -> Bool {
        guard let dueAt = task.dueAt else { return false }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let todayStart = calendar.startOfDay(for: Date(timeIntervalSince1970: TimeInterval(nowMillis) / 1000.0))
        let dueDayStart = calendar.startOfDay(for: Date(timeIntervalSince1970: TimeInterval(dueAt) / 1000.0))
        return dueDayStart <= todayStart
    }
}
