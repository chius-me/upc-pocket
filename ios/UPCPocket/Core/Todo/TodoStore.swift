import Foundation

/// Local-only todo store persisting atomically to `Application Support/UPCPocketNoBackup/todos/todos-v1.json`.
actor TodoStore {
    private static let fileName = "todos-v1.json"
    private static let maxTitleLength = 200
    private static let maxFileBytes = 2 * 1024 * 1024

    private let directoryURL: URL?
    private let scheduler: any TodoReminderScheduling
    private let clock: @Sendable () -> Int64
    private let idGenerator: @Sendable () -> String
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    private var tasksById: [String: TodoTask] = [:]
    private var initialized = false
    private var currentState = TodoUiState()

    init(
        directoryURL: URL? = nil,
        scheduler: any TodoReminderScheduling = UserNotificationTodoScheduler(),
        clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        idGenerator: @escaping @Sendable () -> String = { UUID().uuidString }
    ) {
        self.directoryURL = directoryURL
        self.scheduler = scheduler
        self.clock = clock
        self.idGenerator = idGenerator
    }

    func restore() async -> TodoUiState {
        initialized = true
        tasksById.removeAll()
        for task in await readTasksFromDisk() {
            tasksById[task.id] = task
        }

        let now = max(1, clock())
        let access = await scheduler.currentAccess()
        var hint: String?
        var mutated = false

        for (task, plan) in TodoReminderPlanner.rebuild(tasks: Array(tasksById.values), nowMillis: now, access: access) {
            switch plan {
            case let .schedule(atMillis, _):
                await scheduler.schedule(task: task, atMillis: atMillis)
            case .notifyNow:
                var fired = task
                fired.reminderFiredAt = now
                fired.updatedAt = now
                tasksById[task.id] = fired
                mutated = true
                await scheduler.showNotificationNow(task: fired)
            case .blocked:
                hint = "部分待办提醒因系统通知未开启而未能弹出"
            case .nothing:
                break
            }
        }
        if mutated {
            await writeTasksAtomically(Array(tasksById.values))
        }
        publishState(permissionHint: hint)
        return currentState
    }

    func requestReminderPermissionIfNeeded() async -> Bool {
        await scheduler.requestAuthorizationIfNeeded()
    }

    func upsertTask(
        id: String?,
        rawTitle: String,
        dueAt: Int64?,
        reminderAt: Int64?
    ) async -> (Bool, TodoUiState) {
        let title = rawTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty, title.count <= Self.maxTitleLength else {
            return (false, currentState)
        }
        if !initialized {
            _ = await restore()
        }
        let now = max(1, clock())
        var updated: TodoTask
        if let id, let existing = tasksById[id] {
            let reminderChanged = existing.reminderAt != reminderAt
            updated = existing
            updated.title = title
            updated.updatedAt = now
            updated.dueAt = dueAt
            updated.reminderAt = reminderAt
            if reminderChanged {
                updated.reminderFiredAt = nil
            }
            updated.revision += 1
        } else {
            let newId = id ?? idGenerator()
            updated = TodoTask(
                id: newId,
                title: title,
                completed: false,
                createdAt: now,
                updatedAt: now,
                completedAt: nil,
                dueAt: dueAt,
                reminderAt: reminderAt,
                reminderFiredAt: nil,
                revision: 1
            )
        }

        tasksById[updated.id] = updated
        await writeTasksAtomically(Array(tasksById.values))
        let hint = await applyReminderPlan(for: updated, nowMillis: now)
        publishState(permissionHint: hint)
        return (true, currentState)
    }

    func toggleCompleted(taskId: String) async -> TodoUiState {
        if !initialized {
            _ = await restore()
        }
        guard var existing = tasksById[taskId] else { return currentState }
        let now = max(1, clock())
        let nextCompleted = !existing.completed
        existing.completed = nextCompleted
        existing.updatedAt = now
        existing.completedAt = nextCompleted ? now : nil
        existing.revision += 1

        tasksById[taskId] = existing
        await writeTasksAtomically(Array(tasksById.values))

        let hint: String?
        if nextCompleted {
            await scheduler.cancel(taskId: taskId)
            hint = nil
        } else {
            hint = await applyReminderPlan(for: existing, nowMillis: now)
        }
        publishState(permissionHint: hint)
        return currentState
    }

    func deleteTask(taskId: String) async -> TodoUiState {
        if !initialized {
            _ = await restore()
        }
        if tasksById.removeValue(forKey: taskId) != nil {
            await scheduler.cancel(taskId: taskId)
            await writeTasksAtomically(Array(tasksById.values))
            publishState(permissionHint: nil)
        }
        return currentState
    }

    func clearCompleted() async -> TodoUiState {
        if !initialized {
            _ = await restore()
        }
        let completedIds = tasksById.values.filter(\.completed).map(\.id)
        guard !completedIds.isEmpty else { return currentState }
        for id in completedIds {
            tasksById.removeValue(forKey: id)
            await scheduler.cancel(taskId: id)
        }
        await writeTasksAtomically(Array(tasksById.values))
        publishState(permissionHint: nil)
        return currentState
    }

    func handleReminderFired(taskId: String, revision: Int) async -> TodoUiState {
        if !initialized {
            _ = await restore()
        }
        guard let existing = tasksById[taskId],
              TodoReminderPlanner.acceptsAlarm(task: existing, revision: revision) else {
            return currentState
        }
        let now = max(1, clock())
        var updated = existing
        updated.reminderFiredAt = now
        updated.updatedAt = now
        tasksById[taskId] = updated
        await writeTasksAtomically(Array(tasksById.values))
        await scheduler.showNotificationNow(task: updated)
        publishState(permissionHint: nil)
        return currentState
    }

    private func applyReminderPlan(for task: TodoTask, nowMillis: Int64) async -> String? {
        await scheduler.cancel(taskId: task.id)
        let access = await scheduler.currentAccess()
        switch TodoReminderPlanner.plan(task: task, nowMillis: nowMillis, access: access) {
        case let .schedule(atMillis, _):
            await scheduler.schedule(task: task, atMillis: atMillis)
            if !access.notificationsEnabled {
                return "已保存待办，但系统通知权限未开启，到点可能无法收到提醒"
            }
            return nil
        case .notifyNow:
            var fired = task
            fired.reminderFiredAt = nowMillis
            fired.updatedAt = nowMillis
            tasksById[task.id] = fired
            await writeTasksAtomically(Array(tasksById.values))
            await scheduler.showNotificationNow(task: fired)
            return nil
        case .blocked:
            return "已保存待办，请开启系统通知权限以接收一次性提醒"
        case .nothing:
            return nil
        }
    }

    private func publishState(permissionHint: String?) {
        let all = Array(tasksById.values)
        currentState = TodoUiState(
            incompleteTasks: TodoOrdering.incomplete(all),
            completedTasks: TodoOrdering.completed(all),
            todayScheduleTasks: TodoOrdering.todayScheduleSummary(all, limit: 3),
            isLoaded: true,
            reminderPermissionHint: permissionHint
        )
    }

    private func readTasksFromDisk() async -> [TodoTask] {
        guard let fileURL = await resolveFileURL(),
              FileManager.default.fileExists(atPath: fileURL.path),
              let data = try? Data(contentsOf: fileURL),
              data.count <= Self.maxFileBytes,
              let decoded = try? decoder.decode(TodoFileEnvelope.self, from: data) else {
            return []
        }
        return decoded.tasks
    }

    private func writeTasksAtomically(_ tasks: [TodoTask]) async {
        guard let fileURL = await resolveFileURL(),
              let data = try? encoder.encode(TodoFileEnvelope(tasks: tasks)),
              data.count <= Self.maxFileBytes else {
            return
        }
        try? data.write(to: fileURL, options: .atomic)
        try? await LocalStorageExclusion.shared.excludeFromBackup(url: fileURL)
    }

    private func resolveFileURL() async -> URL? {
        if let directoryURL {
            try? await LocalStorageExclusion.shared.ensureExcludedDirectory(at: directoryURL)
            return directoryURL.appendingPathComponent(Self.fileName, isDirectory: false)
        }
        guard let base = try? await LocalStorageExclusion.shared.noBackupBaseDirectory() else {
            return nil
        }
        let dir = base.appendingPathComponent("todos", isDirectory: true)
        try? await LocalStorageExclusion.shared.ensureExcludedDirectory(at: dir)
        return dir.appendingPathComponent(Self.fileName, isDirectory: false)
    }
}
