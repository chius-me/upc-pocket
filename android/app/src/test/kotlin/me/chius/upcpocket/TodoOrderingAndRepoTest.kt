package me.chius.upcpocket

import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import me.chius.upcpocket.feature.todo.TodoOrdering
import me.chius.upcpocket.feature.todo.TodoReminderAccess
import me.chius.upcpocket.feature.todo.TodoReminderBlockReason
import me.chius.upcpocket.feature.todo.TodoReminderBridge
import me.chius.upcpocket.feature.todo.TodoReminderPlan
import me.chius.upcpocket.feature.todo.TodoReminderPlanner
import me.chius.upcpocket.feature.todo.TodoRepository
import me.chius.upcpocket.feature.todo.TodoTask
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TodoOrderingAndRepoTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("upc-todo-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `ordering puts due tasks first ascending and undated tasks descending regardless of reminder`() {
        val tasks = listOf(
            TodoTask(
                id = "t-undated-old",
                title = "Old undated with urgent reminder",
                createdAt = 100L,
                updatedAt = 100L,
                dueAt = null,
                reminderAt = 1_000L,
            ),
            TodoTask(
                id = "t-undated-new",
                title = "New undated",
                createdAt = 200L,
                updatedAt = 200L,
                dueAt = null,
                reminderAt = null,
            ),
            TodoTask(
                id = "t-due-later",
                title = "Due later",
                createdAt = 50L,
                updatedAt = 50L,
                dueAt = 9_000L,
                reminderAt = null,
            ),
            TodoTask(
                id = "t-due-sooner",
                title = "Due sooner",
                createdAt = 300L,
                updatedAt = 300L,
                dueAt = 5_000L,
                reminderAt = 10_000L,
            ),
            TodoTask(
                id = "t-done",
                title = "Completed",
                completed = true,
                createdAt = 400L,
                updatedAt = 500L,
                completedAt = 500L,
                dueAt = 1_000L,
            ),
        )

        val ordered = TodoOrdering.incomplete(tasks)
        assertEquals(
            listOf("t-due-sooner", "t-due-later", "t-undated-new", "t-undated-old"),
            ordered.map { it.id },
        )
        assertEquals(listOf("t-done"), TodoOrdering.completed(tasks).map { it.id })
        assertEquals(
            listOf("t-due-sooner", "t-due-later", "t-undated-new"),
            TodoOrdering.todayScheduleSummary(tasks, limit = 3).map { it.id },
        )

        assertTrue(TodoOrdering.isOverdueOrToday(ordered[0], nowMillis = 6_000L, zone = ZoneId.of("UTC")))
        assertFalse(
            TodoOrdering.isOverdueOrToday(
                TodoTask(id = "future", title = "Future", createdAt = 0L, updatedAt = 0L, dueAt = 86_400_000L * 2),
                nowMillis = 0L,
                zone = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun `reminder planner handles future past blocked completed and stale revision`() {
        val futureTask = TodoTask(
            id = "a",
            title = "Reminder task",
            createdAt = 1_000L,
            updatedAt = 1_000L,
            reminderAt = 5_000L,
            revision = 2,
        )
        assertEquals(
            TodoReminderPlan.Schedule(atMillis = 5_000L, exact = true),
            TodoReminderPlanner.plan(
                futureTask,
                nowMillis = 2_000L,
                access = TodoReminderAccess(notificationsEnabled = true, exactAlarmsAllowed = true),
            ),
        )
        assertEquals(
            TodoReminderPlan.NotifyNow(atMillis = 5_000L),
            TodoReminderPlanner.plan(
                futureTask,
                nowMillis = 6_000L,
                access = TodoReminderAccess(notificationsEnabled = true, exactAlarmsAllowed = false),
            ),
        )
        assertEquals(
            TodoReminderPlan.Blocked(TodoReminderBlockReason.NOTIFICATIONS_DISABLED),
            TodoReminderPlanner.plan(
                futureTask,
                nowMillis = 6_000L,
                access = TodoReminderAccess(notificationsEnabled = false, exactAlarmsAllowed = false),
            ),
        )
        assertEquals(
            TodoReminderPlan.Nothing,
            TodoReminderPlanner.plan(
                futureTask.copy(completed = true),
                nowMillis = 2_000L,
                access = TodoReminderAccess(notificationsEnabled = true, exactAlarmsAllowed = true),
            ),
        )

        assertTrue(TodoReminderPlanner.acceptsAlarm(futureTask, revision = 2))
        assertFalse(TodoReminderPlanner.acceptsAlarm(futureTask, revision = 1))
        assertFalse(TodoReminderPlanner.acceptsAlarm(futureTask.copy(completed = true), revision = 2))
    }

    @Test
    fun `repository persists tasks handles one-shot alarm and cancels on complete or delete`() = runTest {
        val bridge = FakeReminderBridge()
        var now = 1_000L
        var nextId = 1

        val repo = TodoRepository(
            directory = tempDir,
            reminderBridge = bridge,
            clock = { now },
            idGenerator = { "todo-${nextId++}" },
        )
        repo.restore()

        assertTrue(repo.upsertTask(id = null, rawTitle = "  复习高数  ", dueAt = 20_000L, reminderAt = 10_000L))
        assertFalse(repo.upsertTask(id = null, rawTitle = "   ", dueAt = null, reminderAt = null))

        val created = repo.uiState.value.incompleteTasks.single()
        assertEquals("复习高数", created.title)
        assertEquals(1, bridge.scheduled.size)

        // Firing alarm with stale revision is ignored.
        now = 10_000L
        repo.handleAlarmFired(created.id, revision = 99)
        assertEquals(0, bridge.shownNotifications.size)

        // Matching revision fires once and stamps reminderFiredAt; second fire is ignored.
        repo.handleAlarmFired(created.id, revision = 1)
        assertEquals(1, bridge.shownNotifications.size)
        repo.handleAlarmFired(created.id, revision = 1)
        assertEquals(1, bridge.shownNotifications.size)

        // Re-opening repository restores persisted tasks and fired state.
        val reopened = TodoRepository(directory = tempDir, reminderBridge = bridge, clock = { now })
        reopened.restore()
        val restoredTask = reopened.uiState.value.incompleteTasks.single()
        assertNotNull(restoredTask.reminderFiredAt)

        // Completing cancels reminder and moves to completed list.
        now = 15_000L
        reopened.toggleCompleted(restoredTask.id)
        assertTrue(bridge.cancelled.contains(restoredTask.id))
        assertEquals(0, reopened.uiState.value.incompleteTasks.size)
        assertEquals(1, reopened.uiState.value.completedTasks.size)

        reopened.clearCompleted()
        assertEquals(0, reopened.uiState.value.completedTasks.size)
    }

    private class FakeReminderBridge : TodoReminderBridge {
        var access = TodoReminderAccess(notificationsEnabled = true, exactAlarmsAllowed = false)
        val scheduled = mutableListOf<Triple<String, Long, Boolean>>()
        val cancelled = mutableListOf<String>()
        val shownNotifications = mutableListOf<String>()

        override fun currentAccess(): TodoReminderAccess = access

        override fun schedule(task: TodoTask, atMillis: Long, exact: Boolean) {
            scheduled += Triple(task.id, atMillis, exact)
        }

        override fun cancel(taskId: String) {
            cancelled += taskId
        }

        override fun showNotificationNow(task: TodoTask) {
            shownNotifications += task.id
        }
    }
}
