import Foundation

struct TodoReminderAccess: Equatable, Sendable {
    let notificationsEnabled: Bool
    let exactAlarmsAllowed: Bool
}

enum TodoReminderBlockReason: Equatable, Sendable {
    case notificationsDisabled
}

enum TodoReminderPlan: Equatable, Sendable {
    case schedule(atMillis: Int64, exact: Bool)
    case notifyNow(atMillis: Int64)
    case blocked(TodoReminderBlockReason)
    case nothing
}

enum TodoReminderPlanner: Sendable {
    static func plan(
        task: TodoTask,
        nowMillis: Int64,
        access: TodoReminderAccess
    ) -> TodoReminderPlan {
        guard !task.completed,
              let reminder = task.reminderAt,
              task.reminderFiredAt == nil else {
            return .nothing
        }
        if reminder > nowMillis {
            return .schedule(atMillis: reminder, exact: access.exactAlarmsAllowed)
        }
        return access.notificationsEnabled
            ? .notifyNow(atMillis: reminder)
            : .blocked(.notificationsDisabled)
    }

    static func rebuild(
        tasks: [TodoTask],
        nowMillis: Int64,
        access: TodoReminderAccess
    ) -> [(TodoTask, TodoReminderPlan)] {
        tasks.map { ($0, plan(task: $0, nowMillis: nowMillis, access: access)) }
    }

    static func acceptsAlarm(task: TodoTask?, revision: Int) -> Bool {
        guard let task else { return false }
        return !task.completed && task.revision == revision && task.reminderFiredAt == nil
    }
}
