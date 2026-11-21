import Foundation
@preconcurrency import UserNotifications

protocol TodoReminderScheduling: Sendable {
    func currentAccess() async -> TodoReminderAccess
    func requestAuthorizationIfNeeded() async -> Bool
    func schedule(task: TodoTask, atMillis: Int64) async
    func cancel(taskId: String) async
    func showNotificationNow(task: TodoTask) async
}

actor UserNotificationTodoScheduler: TodoReminderScheduling {
    private static func identifier(for taskId: String) -> String {
        "upcpocket.todo.\(taskId)"
    }

    func currentAccess() async -> TodoReminderAccess {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        let allowed: Bool
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            allowed = true
        case .notDetermined, .denied:
            allowed = false
        @unknown default:
            allowed = false
        }
        return TodoReminderAccess(notificationsEnabled: allowed, exactAlarmsAllowed: allowed)
    }

    func requestAuthorizationIfNeeded() async -> Bool {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            return true
        case .notDetermined:
            return (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false
        case .denied:
            return false
        @unknown default:
            return false
        }
    }

    func schedule(task: TodoTask, atMillis: Int64) async {
        let fireDate = Date(timeIntervalSince1970: TimeInterval(atMillis) / 1000.0)
        let components = Calendar.current.dateComponents(
            [.year, .month, .day, .hour, .minute, .second],
            from: fireDate
        )
        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
        await enqueueNotification(task: task, trigger: trigger)
    }

    func cancel(taskId: String) async {
        let id = Self.identifier(for: taskId)
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [id])
        center.removeDeliveredNotifications(withIdentifiers: [id])
    }

    func showNotificationNow(task: TodoTask) async {
        let access = await currentAccess()
        guard access.notificationsEnabled else { return }
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)
        await enqueueNotification(task: task, trigger: trigger)
    }

    private func enqueueNotification(task: TodoTask, trigger: UNNotificationTrigger) async {
        let content = UNMutableNotificationContent()
        content.title = task.title
        if let dueAt = task.dueAt {
            let date = Date(timeIntervalSince1970: TimeInterval(dueAt) / 1000.0)
            let formatter = DateFormatter()
            formatter.dateFormat = "MM-dd HH:mm"
            content.body = "截止时间：\(formatter.string(from: date))"
        } else {
            content.body = "本机一次性待办提醒"
        }
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: Self.identifier(for: task.id),
            content: content,
            trigger: trigger
        )
        try? await UNUserNotificationCenter.current().add(request)
    }
}
