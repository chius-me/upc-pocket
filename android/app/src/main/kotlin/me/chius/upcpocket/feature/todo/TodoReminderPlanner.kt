package me.chius.upcpocket.feature.todo

data class TodoReminderAccess(
    val notificationsEnabled: Boolean,
    val exactAlarmsAllowed: Boolean,
)

enum class TodoReminderBlockReason {
    NOTIFICATIONS_DISABLED,
}

sealed interface TodoReminderPlan {
    data class Schedule(val atMillis: Long, val exact: Boolean) : TodoReminderPlan
    data class NotifyNow(val atMillis: Long) : TodoReminderPlan
    data class Blocked(val reason: TodoReminderBlockReason) : TodoReminderPlan
    data object Nothing : TodoReminderPlan
}

/**
 * Pure one-shot reminder planner enabling deterministic unit tests across permission states,
 * task completions, duplicate deliveries, and stale revision rejection.
 */
object TodoReminderPlanner {

    fun plan(
        task: TodoTask,
        nowMillis: Long,
        access: TodoReminderAccess,
    ): TodoReminderPlan {
        if (task.completed) return TodoReminderPlan.Nothing
        val reminder = task.reminderAt ?: return TodoReminderPlan.Nothing
        if (task.reminderFiredAt != null) return TodoReminderPlan.Nothing
        if (reminder > nowMillis) {
            return TodoReminderPlan.Schedule(
                atMillis = reminder,
                exact = access.exactAlarmsAllowed,
            )
        }
        return if (access.notificationsEnabled) {
            TodoReminderPlan.NotifyNow(reminder)
        } else {
            TodoReminderPlan.Blocked(TodoReminderBlockReason.NOTIFICATIONS_DISABLED)
        }
    }

    fun rebuild(
        tasks: List<TodoTask>,
        nowMillis: Long,
        access: TodoReminderAccess,
    ): List<Pair<TodoTask, TodoReminderPlan>> = tasks.map { task ->
        task to plan(task, nowMillis, access)
    }

    fun acceptsAlarm(task: TodoTask?, revision: Int): Boolean =
        task != null && !task.completed && task.revision == revision && task.reminderFiredAt == null
}
