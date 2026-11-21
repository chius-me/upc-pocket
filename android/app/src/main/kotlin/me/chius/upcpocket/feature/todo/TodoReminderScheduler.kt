package me.chius.upcpocket.feature.todo

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import me.chius.upcpocket.MainActivity
import me.chius.upcpocket.R

interface TodoReminderBridge {
    fun currentAccess(): TodoReminderAccess
    fun schedule(task: TodoTask, atMillis: Long, exact: Boolean)
    fun cancel(taskId: String)
    fun showNotificationNow(task: TodoTask)
}

class AndroidTodoReminderBridge(
    context: Context,
) : TodoReminderBridge {
    private val appContext = context.applicationContext
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val alarmManager =
        appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun currentAccess(): TodoReminderAccess {
        val postGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val notificationsEnabled = notificationManager.areNotificationsEnabled() && postGranted
        val exactAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }
        return TodoReminderAccess(
            notificationsEnabled = notificationsEnabled,
            exactAlarmsAllowed = exactAllowed,
        )
    }

    override fun schedule(task: TodoTask, atMillis: Long, exact: Boolean) {
        val intent = buildAlarmIntent(task.id, task.revision)
        val pending = PendingIntent.getBroadcast(
            appContext,
            stableRequestCode(task.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            if (exact && canUseExactAlarm()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            }
        }.onFailure {
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            }
        }
    }

    override fun cancel(taskId: String) {
        val intent = Intent(appContext, TodoAlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            appContext,
            stableRequestCode(taskId),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pending != null) {
            alarmManager.cancel(pending)
            pending.cancel()
        }
        notificationManager.cancel(stableRequestCode(taskId))
    }

    override fun showNotificationNow(task: TodoTask) {
        if (!currentAccess().notificationsEnabled) return
        ensureNotificationChannel()

        val launchIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            stableRequestCode(task.id),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val contentText = task.dueAt?.let { dueAt ->
            val formatted = Instant.ofEpochMilli(dueAt)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            "截止时间：$formatted"
        } ?: "本机一次性待办提醒"

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setContentTitle(task.title)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        notificationManager.notify(stableRequestCode(task.id), notification)
    }

    private fun canUseExactAlarm(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)

    private fun ensureNotificationChannel() {
        val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "待办提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "UPC Pocket 本机待办一次性提醒"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildAlarmIntent(taskId: String, revision: Int): Intent =
        Intent(appContext, TodoAlarmReceiver::class.java).apply {
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_REVISION, revision)
        }

    private fun stableRequestCode(taskId: String): Int =
        taskId.hashCode() and 0x7FFFFFFF

    companion object {
        const val CHANNEL_ID = "upc_todo_reminders"
        const val EXTRA_TASK_ID = "extra_todo_task_id"
        const val EXTRA_REVISION = "extra_todo_revision"
    }
}
