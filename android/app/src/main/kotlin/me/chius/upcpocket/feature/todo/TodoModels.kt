package me.chius.upcpocket.feature.todo

import kotlinx.serialization.Serializable

@Serializable
data class TodoTask(
    val id: String,
    val title: String,
    val completed: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
    val dueAt: Long? = null,
    val reminderAt: Long? = null,
    val reminderFiredAt: Long? = null,
    val revision: Int = 1,
)

@Serializable
data class TodoFileEnvelope(
    val version: Int = 1,
    val tasks: List<TodoTask> = emptyList(),
)

data class TodoUiState(
    val incompleteTasks: List<TodoTask> = emptyList(),
    val completedTasks: List<TodoTask> = emptyList(),
    val todayScheduleTasks: List<TodoTask> = emptyList(),
    val isLoaded: Boolean = false,
    val reminderPermissionHint: String? = null,
)
