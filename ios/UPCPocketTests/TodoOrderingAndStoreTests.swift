@testable import UPCPocket
import XCTest

final class TodoOrderingAndStoreTests: XCTestCase {
    private actor FakeScheduler: TodoReminderScheduling {
        var access = TodoReminderAccess(notificationsEnabled: true, exactAlarmsAllowed: true)
        var scheduled: [(String, Int64)] = []
        var cancelled: [String] = []
        var shownNotifications: [String] = []

        func currentAccess() async -> TodoReminderAccess {
            access
        }

        func requestAuthorizationIfNeeded() async -> Bool {
            access.notificationsEnabled
        }

        func schedule(task: TodoTask, atMillis: Int64) async {
            scheduled.append((task.id, atMillis))
        }

        func cancel(taskId: String) async {
            cancelled.append(taskId)
        }

        func showNotificationNow(task: TodoTask) async {
            shownNotifications.append(task.id)
        }
    }

    private var tempDir: URL!

    override func setUpWithError() throws {
        tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("upc-todo-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let tempDir {
            try? FileManager.default.removeItem(at: tempDir)
        }
    }

    func testOrderingPrioritizesDueDateAscendingAndUndatedDescendingRegardlessOfReminder() {
        let tasks = [
            TodoTask(id: "t-undated-old", title: "Old undated", completed: false, createdAt: 100, updatedAt: 100, completedAt: nil, dueAt: nil, reminderAt: 1_000, reminderFiredAt: nil, revision: 1),
            TodoTask(id: "t-undated-new", title: "New undated", completed: false, createdAt: 200, updatedAt: 200, completedAt: nil, dueAt: nil, reminderAt: nil, reminderFiredAt: nil, revision: 1),
            TodoTask(id: "t-due-later", title: "Due later", completed: false, createdAt: 50, updatedAt: 50, completedAt: nil, dueAt: 9_000, reminderAt: nil, reminderFiredAt: nil, revision: 1),
            TodoTask(id: "t-due-sooner", title: "Due sooner", completed: false, createdAt: 300, updatedAt: 300, completedAt: nil, dueAt: 5_000, reminderAt: 10_000, reminderFiredAt: nil, revision: 1),
            TodoTask(id: "t-done", title: "Done", completed: true, createdAt: 400, updatedAt: 500, completedAt: 500, dueAt: 1_000, reminderAt: nil, reminderFiredAt: nil, revision: 1)
        ]

        let ordered = TodoOrdering.incomplete(tasks)
        XCTAssertEqual(ordered.map(\.id), ["t-due-sooner", "t-due-later", "t-undated-new", "t-undated-old"])
        XCTAssertEqual(TodoOrdering.completed(tasks).map(\.id), ["t-done"])
        XCTAssertEqual(TodoOrdering.todayScheduleSummary(tasks, limit: 3).map(\.id), ["t-due-sooner", "t-due-later", "t-undated-new"])

        let utc = TimeZone(secondsFromGMT: 0)!
        XCTAssertTrue(TodoOrdering.isOverdueOrToday(ordered[0], nowMillis: 6_000, timeZone: utc))
        XCTAssertFalse(
            TodoOrdering.isOverdueOrToday(
                TodoTask(id: "f", title: "Future", completed: false, createdAt: 0, updatedAt: 0, completedAt: nil, dueAt: 86_400_000 * 2, reminderAt: nil, reminderFiredAt: nil, revision: 1),
                nowMillis: 0,
                timeZone: utc
            )
        )
    }

    func testReminderPlannerHandlesStatesAndStaleRevision() {
        let task = TodoTask(
            id: "a",
            title: "Reminder",
            completed: false,
            createdAt: 1_000,
            updatedAt: 1_000,
            completedAt: nil,
            dueAt: nil,
            reminderAt: 5_000,
            reminderFiredAt: nil,
            revision: 2
        )
        XCTAssertEqual(
            TodoReminderPlanner.plan(task: task, nowMillis: 2_000, access: TodoReminderAccess(notificationsEnabled: true, exactAlarmsAllowed: true)),
            .schedule(atMillis: 5_000, exact: true)
        )
        XCTAssertEqual(
            TodoReminderPlanner.plan(task: task, nowMillis: 6_000, access: TodoReminderAccess(notificationsEnabled: true, exactAlarmsAllowed: false)),
            .notifyNow(atMillis: 5_000)
        )
        XCTAssertEqual(
            TodoReminderPlanner.plan(task: task, nowMillis: 6_000, access: TodoReminderAccess(notificationsEnabled: false, exactAlarmsAllowed: false)),
            .blocked(.notificationsDisabled)
        )
        XCTAssertTrue(TodoReminderPlanner.acceptsAlarm(task: task, revision: 2))
        XCTAssertFalse(TodoReminderPlanner.acceptsAlarm(task: task, revision: 1))
    }

    func testStorePersistsTasksFiresOneShotReminderAndCancelsOnComplete() async {
        let scheduler = FakeScheduler()
        let store = TodoStore(
            directoryURL: tempDir,
            scheduler: scheduler,
            clock: { 1_000 },
            idGenerator: { "todo-1" }
        )
        _ = await store.restore()

        let (ok1, _) = await store.upsertTask(id: nil, rawTitle: "  复习高数  ", dueAt: 20_000, reminderAt: 10_000)
        let (okBlank, _) = await store.upsertTask(id: nil, rawTitle: "   ", dueAt: nil, reminderAt: nil)
        XCTAssertTrue(ok1)
        XCTAssertFalse(okBlank)

        let scheduledCount = await scheduler.scheduled.count
        XCTAssertEqual(scheduledCount, 1)

        // Stale revision ignored.
        _ = await store.handleReminderFired(taskId: "todo-1", revision: 99)
        var shown = await scheduler.shownNotifications.count
        XCTAssertEqual(shown, 0)

        // Matching revision fires once.
        _ = await store.handleReminderFired(taskId: "todo-1", revision: 1)
        _ = await store.handleReminderFired(taskId: "todo-1", revision: 1)
        shown = await scheduler.shownNotifications.count
        XCTAssertEqual(shown, 1)

        // Reopen store to confirm persistence.
        let reopened = TodoStore(directoryURL: tempDir, scheduler: scheduler, clock: { 15_000 })
        let restored = await reopened.restore()
        XCTAssertEqual(restored.incompleteTasks.count, 1)
        XCTAssertNotNil(restored.incompleteTasks.first?.reminderFiredAt)

        // Completing cancels reminder and moves to completed section.
        let afterToggle = await reopened.toggleCompleted(taskId: "todo-1")
        let cancelledIds = await scheduler.cancelled
        XCTAssertTrue(cancelledIds.contains("todo-1"))
        XCTAssertEqual(afterToggle.incompleteTasks.count, 0)
        XCTAssertEqual(afterToggle.completedTasks.count, 1)
    }
}
