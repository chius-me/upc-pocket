package me.chius.upcpocket.feature.todo

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Local-only todo repository persisting tasks atomically under `noBackupFilesDir/todos/todos-v1.json`.
 */
class TodoRepository internal constructor(
    private val directory: File,
    private val reminderBridge: TodoReminderBridge,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(
        directory = File(context.applicationContext.noBackupFilesDir, TODO_DIR),
        reminderBridge = AndroidTodoReminderBridge(context),
    )

    private val mutex = Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
    private val tasksById = linkedMapOf<String, TodoTask>()
    private var initialized = false

    private val _uiState = MutableStateFlow(TodoUiState())
    val uiState: StateFlow<TodoUiState> = _uiState.asStateFlow()

    suspend fun restore() = withContext(ioDispatcher) {
        mutex.withLock {
            withContext(NonCancellable) {
                restoreLocked()
            }
        }
    }

    suspend fun upsertTask(
        id: String?,
        rawTitle: String,
        dueAt: Long?,
        reminderAt: Long?,
    ): Boolean = withContext(ioDispatcher) {
        val title = rawTitle.trim()
        if (title.isEmpty() || title.length > MAX_TITLE_LENGTH) {
            return@withContext false
        }
        mutex.withLock {
            withContext(NonCancellable) {
                if (!initialized) restoreLocked()
                val now = clock().coerceAtLeast(1L)
                val existing = id?.let { tasksById[it] }
                val updated = if (existing != null) {
                    val reminderChanged = existing.reminderAt != reminderAt
                    existing.copy(
                        title = title,
                        updatedAt = now,
                        dueAt = dueAt,
                        reminderAt = reminderAt,
                        reminderFiredAt = if (reminderChanged) null else existing.reminderFiredAt,
                        revision = existing.revision + 1,
                    )
                } else {
                    val taskId = id ?: idGenerator()
                    TodoTask(
                        id = taskId,
                        title = title,
                        completed = false,
                        createdAt = now,
                        updatedAt = now,
                        completedAt = null,
                        dueAt = dueAt,
                        reminderAt = reminderAt,
                        reminderFiredAt = null,
                        revision = 1,
                    )
                }

                tasksById[updated.id] = updated
                writeTasksAtomically(tasksById.values.toList())
                val permissionHint = applyReminderPlanForTask(updated, now)
                publishUiStateLocked(permissionHint)
                true
            }
        }
    }

    suspend fun toggleCompleted(taskId: String) = withContext(ioDispatcher) {
        mutex.withLock {
            withContext(NonCancellable) {
                if (!initialized) restoreLocked()
                val existing = tasksById[taskId] ?: return@withContext
                val now = clock().coerceAtLeast(1L)
                val nextCompleted = !existing.completed
                val updated = existing.copy(
                    completed = nextCompleted,
                    updatedAt = now,
                    completedAt = if (nextCompleted) now else null,
                    revision = existing.revision + 1,
                )
                tasksById[taskId] = updated
                writeTasksAtomically(tasksById.values.toList())

                val permissionHint = if (nextCompleted) {
                    reminderBridge.cancel(taskId)
                    null
                } else {
                    applyReminderPlanForTask(updated, now)
                }
                publishUiStateLocked(permissionHint)
            }
        }
    }

    suspend fun deleteTask(taskId: String) = withContext(ioDispatcher) {
        mutex.withLock {
            withContext(NonCancellable) {
                if (!initialized) restoreLocked()
                if (tasksById.remove(taskId) != null) {
                    reminderBridge.cancel(taskId)
                    writeTasksAtomically(tasksById.values.toList())
                    publishUiStateLocked(null)
                }
            }
        }
    }

    suspend fun clearCompleted() = withContext(ioDispatcher) {
        mutex.withLock {
            withContext(NonCancellable) {
                if (!initialized) restoreLocked()
                val completedIds = tasksById.values.filter { it.completed }.map { it.id }
                if (completedIds.isEmpty()) return@withContext
                completedIds.forEach { id ->
                    tasksById.remove(id)
                    reminderBridge.cancel(id)
                }
                writeTasksAtomically(tasksById.values.toList())
                publishUiStateLocked(null)
            }
        }
    }

    suspend fun handleAlarmFired(taskId: String, revision: Int) = withContext(ioDispatcher) {
        mutex.withLock {
            withContext(NonCancellable) {
                if (!initialized) restoreLocked()
                val existing = tasksById[taskId]
                if (!TodoReminderPlanner.acceptsAlarm(existing, revision)) {
                    return@withContext
                }
                val task = requireNotNull(existing)
                val now = clock().coerceAtLeast(1L)
                val updated = task.copy(
                    reminderFiredAt = now,
                    updatedAt = now,
                )
                tasksById[taskId] = updated
                writeTasksAtomically(tasksById.values.toList())
                reminderBridge.showNotificationNow(updated)
                publishUiStateLocked(null)
            }
        }
    }

    private fun restoreLocked() {
        initialized = true
        tasksById.clear()
        readTasksFromDisk().forEach { task ->
            tasksById[task.id] = task
        }

        val now = clock().coerceAtLeast(1L)
        val access = reminderBridge.currentAccess()
        var hint: String? = null
        var mutated = false

        TodoReminderPlanner.rebuild(tasksById.values.toList(), now, access).forEach { (task, plan) ->
            when (plan) {
                is TodoReminderPlan.Schedule -> {
                    reminderBridge.schedule(task, plan.atMillis, plan.exact)
                }
                is TodoReminderPlan.NotifyNow -> {
                    val fired = task.copy(reminderFiredAt = now, updatedAt = now)
                    tasksById[task.id] = fired
                    mutated = true
                    reminderBridge.showNotificationNow(fired)
                }
                is TodoReminderPlan.Blocked -> {
                    hint = "部分待办提醒因系统通知未开启而未能弹出"
                }
                TodoReminderPlan.Nothing -> Unit
            }
        }
        if (mutated) {
            writeTasksAtomically(tasksById.values.toList())
        }
        publishUiStateLocked(hint)
    }

    private fun applyReminderPlanForTask(task: TodoTask, nowMillis: Long): String? {
        reminderBridge.cancel(task.id)
        val access = reminderBridge.currentAccess()
        return when (val plan = TodoReminderPlanner.plan(task, nowMillis, access)) {
            is TodoReminderPlan.Schedule -> {
                reminderBridge.schedule(task, plan.atMillis, plan.exact)
                if (!access.notificationsEnabled) {
                    "已保存待办，但系统通知权限未开启，到点可能无法收到提醒"
                } else {
                    null
                }
            }
            is TodoReminderPlan.NotifyNow -> {
                val fired = task.copy(reminderFiredAt = nowMillis, updatedAt = nowMillis)
                tasksById[task.id] = fired
                writeTasksAtomically(tasksById.values.toList())
                reminderBridge.showNotificationNow(fired)
                null
            }
            is TodoReminderPlan.Blocked -> "已保存待办，请开启系统通知权限以接收一次性提醒"
            TodoReminderPlan.Nothing -> null
        }
    }

    private fun publishUiStateLocked(permissionHint: String?) {
        val all = tasksById.values.toList()
        _uiState.value = TodoUiState(
            incompleteTasks = TodoOrdering.incomplete(all),
            completedTasks = TodoOrdering.completed(all),
            todayScheduleTasks = TodoOrdering.todayScheduleSummary(all, limit = 3),
            isLoaded = true,
            reminderPermissionHint = permissionHint,
        )
    }

    private fun readTasksFromDisk(): List<TodoTask> {
        val file = File(directory, TODO_FILE)
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return emptyList()
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.size > MAX_FILE_BYTES) return emptyList()
            json.decodeFromString<TodoFileEnvelope>(bytes.toString(StandardCharsets.UTF_8)).tasks
        }.getOrDefault(emptyList())
    }

    private fun writeTasksAtomically(tasks: List<TodoTask>) {
        runCatching {
            directory.mkdirs()
            val target = File(directory, TODO_FILE)
            val tmp = File(directory, "$TODO_FILE.tmp")
            val bytes = json.encodeToString(TodoFileEnvelope(tasks = tasks))
                .toByteArray(StandardCharsets.UTF_8)
            tmp.outputStream().use { fos ->
                fos.write(bytes)
                fos.fd.sync()
            }
            if (target.exists() && !target.delete()) {
                throw IOException("Failed to replace $TODO_FILE")
            }
            if (!tmp.renameTo(target)) {
                throw IOException("Failed to commit $TODO_FILE")
            }
        }
    }

    private companion object {
        const val TODO_DIR = "todos"
        const val TODO_FILE = "todos-v1.json"
        const val MAX_TITLE_LENGTH = 200
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
    }
}
