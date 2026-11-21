package me.chius.upcpocket.feature.todo

import java.time.Instant
import java.time.ZoneId

/**
 * Deterministic ordering shared between Timetable today-schedule header, Home summary, and TodoScreen.
 *
 * - Incomplete: tasks with [TodoTask.dueAt] come first in ascending `dueAt` order (tie-break [TodoTask.id]);
 *   undated tasks follow in descending `createdAt` order (tie-break [TodoTask.id]).
 * - [TodoTask.reminderAt] never participates in ordering priority.
 * - Completed: sorted descending by `completedAt ?: updatedAt` (tie-break [TodoTask.id]).
 */
object TodoOrdering {

    fun incomplete(tasks: List<TodoTask>): List<TodoTask> {
        val (withDeadline, withoutDeadline) = tasks
            .asSequence()
            .filterNot { it.completed }
            .partition { it.dueAt != null }
        return withDeadline.sortedWith(
            compareBy<TodoTask> { it.dueAt!! }.thenBy { it.id },
        ) + withoutDeadline.sortedWith(
            compareByDescending<TodoTask> { it.createdAt }.thenBy { it.id },
        )
    }

    fun completed(tasks: List<TodoTask>): List<TodoTask> = tasks
        .asSequence()
        .filter { it.completed }
        .sortedWith(
            compareByDescending<TodoTask> { it.completedAt ?: it.updatedAt }
                .thenBy { it.id },
        )
        .toList()

    fun todayScheduleSummary(
        tasks: List<TodoTask>,
        limit: Int = 3,
    ): List<TodoTask> = incomplete(tasks).take(limit)

    fun isOverdueOrToday(
        task: TodoTask,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val deadline = task.dueAt ?: return false
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val dueDay = Instant.ofEpochMilli(deadline).atZone(zone).toLocalDate()
        return !dueDay.isAfter(today)
    }
}
