package me.chius.upcpocket.feature.todo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import me.chius.upcpocket.core.designsystem.components.ExpressiveGroupedCard
import me.chius.upcpocket.core.designsystem.components.ExpressiveSectionHeader
import me.chius.upcpocket.core.designsystem.theme.GroupedCardPosition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(
    uiState: TodoUiState,
    onBack: () -> Unit,
    onUpsertTask: (id: String?, title: String, dueAt: Long?, reminderAt: Long?) -> Unit,
    onToggleCompleted: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
    onClearCompleted: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showEditor by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<TodoTask?>(null) }

    if (showEditor) {
        TodoEditorBottomSheet(
            initialTask = editingTask,
            onSave = { id, title, dueAt, reminderAt ->
                onUpsertTask(id, title, dueAt, reminderAt)
                showEditor = false
                editingTask = null
            },
            onDismiss = {
                showEditor = false
                editingTask = null
            },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.systemBars.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top + WindowInsetsSides.Bottom,
        ),
        topBar = {
            LargeTopAppBar(
                title = { Text("本机待办") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    if (uiState.completedTasks.isNotEmpty()) {
                        TextButton(onClick = onClearCompleted) {
                            Text("清除已完成")
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editingTask = null
                    showEditor = true
                },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("新建待办") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            uiState.reminderPermissionHint?.let { hint ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.NotificationsActive, contentDescription = null)
                            Text(hint, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                ExpressiveSectionHeader(
                    title = "未完成（${uiState.incompleteTasks.size}）",
                    subtitle = "仅按截止时间优先排列，提醒时刻不改变排序",
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            if (uiState.incompleteTasks.isEmpty()) {
                item {
                    ExpressiveGroupedCard(
                        position = GroupedCardPosition.SINGLE,
                        modifier = Modifier.padding(bottom = 18.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("暂无待办事项", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "点击右下角「新建待办」，可分别设置独立截止时间与一次性系统提醒，数据仅保存在本机。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = uiState.incompleteTasks,
                    key = { _, task -> task.id },
                ) { index, task ->
                    TodoRowCard(
                        task = task,
                        position = GroupedCardPosition.forIndex(index, uiState.incompleteTasks.size),
                        onToggleCompleted = { onToggleCompleted(task.id) },
                        onEdit = {
                            editingTask = task
                            showEditor = true
                        },
                        onDelete = { onDeleteTask(task.id) },
                        modifier = if (index == uiState.incompleteTasks.lastIndex) {
                            Modifier.padding(bottom = 18.dp)
                        } else {
                            Modifier
                        },
                    )
                }
            }

            if (uiState.completedTasks.isNotEmpty()) {
                item {
                    ExpressiveSectionHeader(
                        title = "已完成（${uiState.completedTasks.size}）",
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }

                itemsIndexed(
                    items = uiState.completedTasks,
                    key = { _, task -> task.id },
                ) { index, task ->
                    TodoRowCard(
                        task = task,
                        position = GroupedCardPosition.forIndex(index, uiState.completedTasks.size),
                        onToggleCompleted = { onToggleCompleted(task.id) },
                        onEdit = {
                            editingTask = task
                            showEditor = true
                        },
                        onDelete = { onDeleteTask(task.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TodoRowCard(
    task: TodoTask,
    position: GroupedCardPosition,
    onToggleCompleted: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = System.currentTimeMillis()
    val overdueOrToday = !task.completed && TodoOrdering.isOverdueOrToday(task, now)

    ExpressiveGroupedCard(
        position = position,
        onClick = onEdit,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = task.completed,
                onCheckedChange = { onToggleCompleted() },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None,
                    color = if (task.completed) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val metaParts = buildList {
                    task.dueAt?.let { due ->
                        val label = formatTodoTimestamp(due)
                        add(if (overdueOrToday) "截止 $label（今日/已逾期）" else "截止 $label")
                    }
                    task.reminderAt?.let { rem ->
                        val suffix = if (task.reminderFiredAt != null) "（已提醒）" else ""
                        add("提醒 ${formatTodoTimestamp(rem)}$suffix")
                    }
                }
                if (metaParts.isNotEmpty()) {
                    Text(
                        text = metaParts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overdueOrToday) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "删除待办",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoEditorBottomSheet(
    initialTask: TodoTask?,
    onSave: (id: String?, title: String, dueAt: Long?, reminderAt: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }

    var title by remember(initialTask) { mutableStateOf(initialTask?.title.orEmpty()) }
    var hasDueAt by remember(initialTask) { mutableStateOf(initialTask?.dueAt != null) }
    var dueAtMillis by remember(initialTask) {
        mutableStateOf(
            initialTask?.dueAt ?: defaultEndOfDayMillis(daysAhead = 0, zone = zone),
        )
    }
    var hasReminderAt by remember(initialTask) { mutableStateOf(initialTask?.reminderAt != null) }
    var reminderAtMillis by remember(initialTask) {
        mutableStateOf(
            initialTask?.reminderAt ?: (System.currentTimeMillis() + 3_600_000L),
        )
    }
    var notificationDeniedHint by remember { mutableStateOf(false) }

    var pickingTarget by remember { mutableStateOf<String?>(null) } // "due" | "reminder"

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationDeniedHint = !granted
    }

    if (pickingTarget != null) {
        val currentMillis = if (pickingTarget == "due") dueAtMillis else reminderAtMillis
        DateTimePickerDialogs(
            initialMillis = currentMillis,
            zone = zone,
            onPicked = { pickedMillis ->
                if (pickingTarget == "due") {
                    dueAtMillis = pickedMillis
                    hasDueAt = true
                } else {
                    reminderAtMillis = pickedMillis
                    hasReminderAt = true
                }
                pickingTarget = null
            },
            onDismiss = { pickingTarget = null },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (initialTask == null) "新建本机待办" else "编辑本机待办",
                style = MaterialTheme.typography.headlineSmall,
            )

            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= 200) title = it },
                label = { Text("待办内容") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Independent Due Date
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("设置独立截止时间", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (hasDueAt) "截止：${formatTodoTimestamp(dueAtMillis)}" else "不参与按截止优先排序",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = hasDueAt,
                    onCheckedChange = { enabled ->
                        hasDueAt = enabled
                    },
                )
            }

            if (hasDueAt) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = false,
                        onClick = { dueAtMillis = defaultEndOfDayMillis(0, zone) },
                        label = { Text("今晚") },
                    )
                    FilterChip(
                        selected = false,
                        onClick = { dueAtMillis = defaultEndOfDayMillis(1, zone) },
                        label = { Text("明晚") },
                    )
                    OutlinedButton(
                        onClick = { pickingTarget = "due" },
                    ) {
                        Text("自选时间")
                    }
                }
            }

            // Independent One-Shot Reminder
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("一次性系统提醒", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (hasReminderAt) "提醒：${formatTodoTimestamp(reminderAtMillis)}" else "到期前或按指定时刻提醒一次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = hasReminderAt,
                    onCheckedChange = { enabled ->
                        hasReminderAt = enabled
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                                PackageManager.PERMISSION_GRANTED
                            if (!granted) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    },
                )
            }

            if (hasReminderAt) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = false,
                        onClick = { reminderAtMillis = System.currentTimeMillis() + 900_000L },
                        label = { Text("15 分钟后") },
                    )
                    FilterChip(
                        selected = false,
                        onClick = { reminderAtMillis = System.currentTimeMillis() + 3_600_000L },
                        label = { Text("1 小时后") },
                    )
                    OutlinedButton(
                        onClick = { pickingTarget = "reminder" },
                    ) {
                        Text("自选时间")
                    }
                }
            }

            if (notificationDeniedHint) {
                Text(
                    text = "未授予通知权限，待办仍可正常保存；开启系统通知权限后即可触发提醒。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
                Button(
                    enabled = title.trim().isNotEmpty(),
                    onClick = {
                        onSave(
                            initialTask?.id,
                            title.trim(),
                            if (hasDueAt) dueAtMillis else null,
                            if (hasReminderAt) reminderAtMillis else null,
                        )
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DoneAll,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text("保存")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimePickerDialogs(
    initialMillis: Long,
    zone: ZoneId,
    onPicked: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialDateTime = remember(initialMillis) {
        Instant.ofEpochMilli(initialMillis).atZone(zone).toLocalDateTime()
    }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }

    if (pickedDate == null) {
        val utcDayMillis = remember(initialDateTime) {
            initialDateTime.toLocalDate()
                .atStartOfDay(ZoneId.of("UTC"))
                .toInstant()
                .toEpochMilli()
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = utcDayMillis)
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedUtc = datePickerState.selectedDateMillis
                        pickedDate = if (selectedUtc != null) {
                            Instant.ofEpochMilli(selectedUtc).atZone(ZoneId.of("UTC")).toLocalDate()
                        } else {
                            initialDateTime.toLocalDate()
                        }
                    },
                ) {
                    Text("下一步")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    } else {
        val timePickerState = rememberTimePickerState(
            initialHour = initialDateTime.hour,
            initialMinute = initialDateTime.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("选择时间") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val date = pickedDate ?: initialDateTime.toLocalDate()
                        val ldt = LocalDateTime.of(
                            date,
                            LocalTime.of(timePickerState.hour, timePickerState.minute),
                        )
                        onPicked(ldt.atZone(zone).toInstant().toEpochMilli())
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            },
        )
    }
}

private fun defaultEndOfDayMillis(daysAhead: Long, zone: ZoneId): Long {
    val targetDate = LocalDate.now(zone).plusDays(daysAhead)
    return LocalDateTime.of(targetDate, LocalTime.of(23, 59))
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
}

fun formatTodoTimestamp(epochMillis: Long): String {
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}
