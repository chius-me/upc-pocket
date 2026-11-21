import Foundation

struct TodoTask: Codable, Equatable, Identifiable, Sendable {
    let id: String
    var title: String
    var completed: Bool
    let createdAt: Int64
    var updatedAt: Int64
    var completedAt: Int64?
    var dueAt: Int64?
    var reminderAt: Int64?
    var reminderFiredAt: Int64?
    var revision: Int
}

struct TodoFileEnvelope: Codable, Sendable {
    var version: Int = 1
    var tasks: [TodoTask]
}

struct TodoUiState: Equatable, Sendable {
    var incompleteTasks: [TodoTask] = []
    var completedTasks: [TodoTask] = []
    var todayScheduleTasks: [TodoTask] = []
    var isLoaded: Bool = false
    var reminderPermissionHint: String? = nil
}
