# M2a 本地日程待办与专注持久化规范

本文档定义 M2a 阶段「本机待办与一次性提醒」和「专注墙钟持久化与统计」在 Android 与 iOS 两端严格对齐的领域模型、排序、提醒规划、计时统计口径及隔离存储约束。

---

## 1. 本机待办与独立截止/一次性提醒（`TodoTask`）

### 数据模型与校验
- 字段：
  - `id: String`（UUID 字符串）
  - `title: String`（去首尾空白后长度必须为 `1..200` 字符）
  - `completed: Boolean`
  - `createdAt: Int64`（UTC 毫秒时间戳）
  - `updatedAt: Int64`
  - `completedAt: Int64?`
  - `dueAt: Int64?`（可选截止时间，**与提醒完全独立**）
  - `reminderAt: Int64?`（可选一次性提醒时刻，可与 `dueAt` 独立开启或关闭）
  - `reminderFiredAt: Int64?`（通知触发后记录时间戳，防重复触发）
  - `revision: Int`（每次创建/编辑/切换完成状态均严格递增，初始为 `1`）

### 稳定排序规则（`TodoOrdering`）
1. **未完成任务**：
   - 有截止时间（`dueAt != null`）优先：按 `dueAt` 升序，其次按 `id` 字典序。
   - 无截止时间（`dueAt == null`）在后：按 `createdAt` 倒序，其次按 `id` 字典序。
   - `reminderAt` **不参与**任何排序权重计算。
2. **已完成任务**：
   - 按 `completedAt ?? updatedAt` 倒序，其次按 `id` 字典序。
3. **课表顶部「今日日程待办」摘要（`todayScheduleSummary`）**：
   - 按未完成排序规则取最多前 3 条任务，并标注是否已逾期或今日截止（按本地时区自然日 `!dueDate.isAfter(today)` 判定）。

### 一次性提醒调度契约（`TodoReminderPlanner`）
- **决策函数 `plan(task, nowMillis, access)`**：
  - 已完成（`completed == true`）、无 `reminderAt` 或已触发（`reminderFiredAt != null`）→ `Nothing`。
  - `reminderAt > nowMillis` → `Schedule(at = reminderAt, exact = access.exactAlarmsAllowed)`。
  - `reminderAt <= nowMillis` → 若通知权限可用则 `NotifyNow(at = reminderAt)`，否则 `Blocked(NOTIFICATIONS_DISABLED)`。
- **防陈旧版本校验 `acceptsAlarm(task, revision)`**：
  - 仅当 `task != null && !task.completed && task.revision == revision && task.reminderFiredAt == null` 时允许触发通知并写回 `reminderFiredAt`。
- **双端平台实现边界**：
  - **Android**：`AndroidManifest.xml` 声明 `POST_NOTIFICATIONS`（仅在用户主动开启提醒时按需请求权限，拒权不阻塞创建待办），**不声明 `USE_EXACT_ALARM` 或 `SCHEDULE_EXACT_ALARM`**；使用 `AlarmManager.setAndAllowWhileIdle`（仅当 `canScheduleExactAlarms()` 返回 `true` 时使用 `setExactAndAllowWhileIdle`）配合非导出 `TodoAlarmReceiver`（`android:exported="false"`）与 `PendingIntent.FLAG_IMMUTABLE`。
  - **iOS**：使用 `UNUserNotificationCenter` 按需请求 `.alert` / `.sound` 授权，注册 `UNCalendarNotificationTrigger(repeats: false)` 一次性通知（标识符 `upcpocket.todo.<id>`），在完成、删除或清除提醒时取消对应待发送与已送达通知。

### 存储隔离
- **Android**：`noBackupFilesDir/todos/todos-v1.json`（原子写入，全异步 `Dispatchers.IO` + `Mutex`）。
- **iOS**：`Application Support/UPCPocketNoBackup/todos/todos-v1.json`（原子写入，`isExcludedFromBackup = true`，Swift 6 `actor` 串行读写）。

---

## 2. 专注墙钟持久化与统计（`FocusTimerSnapshot` + `FocusStats`）

### 模式与墙钟快照（`FocusMode` + `PomodoroClock`）
- 模式枚举（`isFocus` 区分专注段与休息段）：
  - `FOCUS_25`：25 分钟（`isFocus = true`）
  - `SHORT_BREAK_5`：5 分钟（`isFocus = false`）
  - `DEEP_45`：45 分钟（`isFocus = true`）
- **不存储可变“剩余秒数”**，仅持久化 `FocusTimerSnapshot(mode, running, startedAt, plannedSeconds, pausedTotalMs, pausedAt, focusRound)`：
  - `pausedMs(s, now) = max(0, pausedTotalMs + (pausedAt > 0 ? max(0, now - pausedAt) : 0))`
  - `elapsedSeconds(s, now) = startedAt == 0 ? 0 : Int(max(0, now - startedAt - pausedMs(s, now)) / 1000)`
  - `remainingSeconds(s, now) = startedAt == 0 ? plannedSeconds : max(0, plannedSeconds - elapsedSeconds(s, now))`
  - `progress(s, now) = plannedSeconds <= 0 ? 0 : clamp(elapsedSeconds(s, now) / Float(plannedSeconds), 0, 1)`
  - `isFinished(s, now) = startedAt != 0 && remainingSeconds(s, now) <= 0`
- App 重启或从后台恢复时依据墙钟直接重算剩余时间；若运行中的段落已在进程退出期间自然结束，则自动结算为一条 `completed = true` 的历史记录并重置为空闲态。

### 学习日 04:00 分界与双口径统计（`FocusStats`）
- **学习日起点**：凌晨 **04:00**（`DAY_START_HOUR = 4`）。00:00–03:59 期间开始的专注归属前一学习日；每周一为本周起点，每月 1 日为本月起点。
- **过滤与双口径**：
  - **仅统计专注段**（`mode.isFocus == true`）；休息段（`SHORT_BREAK_5`）不计入任何统计与历史累计轮数。
  - **专注时长 `seconds`**：累加全部专注段（含用户主动「提前结束」的 `completed = false`、且 `actualSeconds > 0` 的片段）。
  - **完成轮数 `sessions`**：仅统计完整走完（`completed = true`）的专注段，不虚计中途放弃的轮次。

### 存储隔离
- **Android**：`noBackupFilesDir/focus/focus-v1.json`（保留最近 200 条记录，原子写入，纯异步）。
- **iOS**：`Application Support/UPCPocketNoBackup/focus/focus-v1.json`（保留最近 200 条记录，`isExcludedFromBackup = true`，Swift 6 `actor` 串行读写）。
