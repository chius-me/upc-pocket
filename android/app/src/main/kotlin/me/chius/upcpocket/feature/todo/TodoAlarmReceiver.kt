package me.chius.upcpocket.feature.todo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.chius.upcpocket.UpcPocketApplication

/**
 * Non-exported receiver for one-shot local todo reminders.
 */
class TodoAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(AndroidTodoReminderBridge.EXTRA_TASK_ID) ?: return
        val revision = intent.getIntExtra(AndroidTodoReminderBridge.EXTRA_REVISION, -1)
        if (revision <= 0) return

        val app = context.applicationContext as? UpcPocketApplication ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.container.todoRepository.handleAlarmFired(taskId = taskId, revision = revision)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
