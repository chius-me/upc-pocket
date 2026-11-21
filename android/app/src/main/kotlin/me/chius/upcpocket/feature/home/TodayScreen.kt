package me.chius.upcpocket.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.EventSeat
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import me.chius.upcpocket.core.data.AppPreferencesState
import me.chius.upcpocket.core.designsystem.components.CompactGlanceChip
import me.chius.upcpocket.core.designsystem.components.ExpressiveGroupedCard
import me.chius.upcpocket.core.designsystem.components.ExpressiveSectionHeader
import me.chius.upcpocket.core.designsystem.components.GoogleSearchPillBar
import me.chius.upcpocket.core.designsystem.components.HeroAtAGlanceCard
import me.chius.upcpocket.core.designsystem.components.QuickSearchSheet
import me.chius.upcpocket.core.designsystem.theme.GroupedCardPosition
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.SchoolAccountState
import me.chius.upcpocket.feature.card.CardBalanceUiState
import me.chius.upcpocket.feature.grades.GpaCalculator
import me.chius.upcpocket.feature.grades.GradeFilterPreset
import me.chius.upcpocket.feature.grades.GradesUiState
import me.chius.upcpocket.feature.library.LibrarySeatUiState
import me.chius.upcpocket.feature.power.PowerUiState
import me.chius.upcpocket.feature.timetable.CourseMeeting
import me.chius.upcpocket.feature.timetable.TimetableSchedule
import me.chius.upcpocket.feature.timetable.TimetableUiState

private const val AUTO_SYNC_STALE_MS = 15L * 60L * 1000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    preferences: AppPreferencesState,
    accountState: SchoolAccountState,
    timetableState: TimetableUiState,
    cardBalanceState: CardBalanceUiState,
    powerState: PowerUiState,
    librarySeatState: LibrarySeatUiState,
    gradesState: GradesUiState,
    isAutoSyncing: Boolean,
    bottomNavPadding: Dp,
    onSmartAutoSync: (force: Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSchoolAccount: () -> Unit,
    onOpenTimetableTab: () -> Unit,
    onOpenCardWallet: () -> Unit,
    onOpenPower: () -> Unit,
    onOpenLibrarySeat: () -> Unit,
    onOpenGrades: () -> Unit,
    onOpenFreeClassroom: () -> Unit,
    onOpenShiguang: () -> Unit,
    onOpenAcademicCalendar: () -> Unit,
) {
    var showSearchSheet by remember { mutableStateOf(false) }

    LaunchedEffect(accountState.status, accountState.generation) {
        if (accountState.status == AccountStatus.SAVED_UNVERIFIED) {
            val stale = System.currentTimeMillis() - preferences.lastAutoSyncEpochMillis > AUTO_SYNC_STALE_MS
            if (stale) {
                onSmartAutoSync(false)
            }
        }
    }

    if (showSearchSheet) {
        QuickSearchSheet(
            timetableWeek = timetableState.weekData,
            onDismiss = { showSearchSheet = false },
            onOpenTimetableTab = onOpenTimetableTab,
            onOpenGrades = onOpenGrades,
            onOpenShiguang = onOpenShiguang,
            onOpenLibrarySeat = onOpenLibrarySeat,
            onOpenFreeClassroom = onOpenFreeClassroom,
            onOpenCardWallet = onOpenCardWallet,
            onOpenPower = onOpenPower,
            onOpenAcademicCalendar = onOpenAcademicCalendar,
            onOpenSchoolAccount = onOpenSchoolAccount,
            onOpenSettings = onOpenSettings,
        )
    }

    val shanghaiZone = remember { ZoneId.of("Asia/Shanghai") }
    val nowDate = remember { LocalDate.now(shanghaiZone) }
    val nowTime = remember { LocalTime.now(shanghaiZone) }
    val minuteOfDay = nowTime.hour * 60 + nowTime.minute
    val weekdayName = remember(nowDate) {
        when (nowDate.dayOfWeek.value) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            else -> "周日"
        }
    }
    val todayMeetings = timetableState.todaySummary?.courses.orEmpty()
    val todayStatus = remember(todayMeetings, minuteOfDay) {
        TimetableSchedule.resolveTodayStatus(todayMeetings, minuteOfDay)
    }

    val weekChipText = when {
        isAutoSyncing -> "同步中"
        timetableState.currentSchoolWeek != null -> "第 ${timetableState.currentSchoolWeek} 周"
        timetableState.todaySummary?.weekNumber != null -> "第 ${timetableState.todaySummary.weekNumber} 周"
        else -> null
    }

    val accountInitials = accountState.maskedLoginName?.take(2)?.uppercase(Locale.ROOT)
    val gradesSummary = remember(gradesState.snapshot) {
        gradesState.snapshot?.let { snap ->
            GpaCalculator.calculate(
                GpaCalculator.filterByPreset(snap.records, GradeFilterPreset.ALL, null),
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        GoogleSearchPillBar(
            placeholder = "搜索课程、成绩或校园服务",
            isAccountConfigured = accountState.status == AccountStatus.SAVED_UNVERIFIED,
            onBarClick = { showSearchSheet = true },
            onAccountClick = onOpenSettings,
            trailingChip = weekChipText,
            accountBadgeText = accountInitials,
        )

        PullToRefreshBox(
            isRefreshing = isAutoSyncing || timetableState.isRefreshing || cardBalanceState.isRefreshing,
            onRefresh = { onSmartAutoSync(true) },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 6.dp,
                    bottom = bottomNavPadding + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val cardSnap = cardBalanceState.snapshot
                        CompactGlanceChip(
                            label = "校园卡",
                            value = cardSnap?.totalBalanceText ?: "--",
                            unit = if (cardSnap != null) "元" else null,
                            icon = Icons.Rounded.CreditCard,
                            iconTint = MaterialTheme.colorScheme.primary,
                            onClick = onOpenCardWallet,
                            modifier = Modifier.weight(1f),
                        )

                        val reading = powerState.lastReading
                        CompactGlanceChip(
                            label = "宿舍电量",
                            value = reading?.kwhText ?: "--",
                            unit = if (reading != null) "度" else null,
                            icon = Icons.Rounded.Bolt,
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            onClick = onOpenPower,
                            modifier = Modifier.weight(1f),
                        )

                        val gpa = gradesSummary?.officialGpa
                        CompactGlanceChip(
                            label = "绩点 GPA",
                            value = gpa?.let { "%.2f".format(Locale.ROOT, it) } ?: "--",
                            icon = Icons.Rounded.School,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            onClick = onOpenGrades,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                item {
                    HeroAtAGlanceCard(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = if (accountState.status == AccountStatus.SAVED_UNVERIFIED) {
                            onOpenTimetableTab
                        } else {
                            onOpenSchoolAccount
                        },
                        modifier = Modifier.padding(bottom = 14.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${nowDate.format(DateTimeFormatter.ofPattern("M月d日"))} $weekdayName",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ) {
                                val badge = when {
                                    accountState.status != AccountStatus.SAVED_UNVERIFIED -> "未配置账号"
                                    isAutoSyncing -> "正在同步"
                                    timetableState.isOfflineFallback -> "离线课表"
                                    todayMeetings.isNotEmpty() -> "今日 ${todayMeetings.size} 节"
                                    else -> "空闲"
                                }
                                Text(
                                    text = badge,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        when {
                            accountState.status != AccountStatus.SAVED_UNVERIFIED -> {
                                Text(
                                    text = "连接你的学校账号",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "保存后自动同步今日课程、校园卡余额与图书馆预约。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(onClick = onOpenSchoolAccount) {
                                    Text("立即前往配置")
                                }
                            }
                            todayStatus is TimetableSchedule.TodayScheduleStatus.Ongoing -> {
                                Text(
                                    text = todayStatus.meeting.courseName,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "正在上课 · 距下课 ${todayStatus.remainingMinutes} 分钟 · ${todayStatus.meeting.location ?: "待定教室"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            todayStatus is TimetableSchedule.TodayScheduleStatus.Upcoming -> {
                                Text(
                                    text = todayStatus.meeting.courseName,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                val waitText = if (todayStatus.minutesUntilStart >= 60) {
                                    "${todayStatus.minutesUntilStart / 60} 小时 ${todayStatus.minutesUntilStart % 60} 分钟后"
                                } else {
                                    "${todayStatus.minutesUntilStart} 分钟后"
                                }
                                Text(
                                    text = "下一节 · $waitText · ${todayStatus.timeRange} · ${todayStatus.meeting.location ?: "待定教室"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            todayStatus is TimetableSchedule.TodayScheduleStatus.FinishedToday -> {
                                Text(
                                    text = "今日课程已全部结束",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "已完成今日 ${todayStatus.completedCount} 节课程安排，点按查看完整周课表。",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            else -> {
                                Text(
                                    text = if (timetableState.weekData == null) "下拉同步今日课表" else "今日无排课",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (timetableState.weekData == null) {
                                        "向下拉动即可自动刷新课表与校园实时状态。"
                                    } else {
                                        "享受自由时间，点按此卡可查看完整周课表。"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        val activeReservation = librarySeatState.libseatCurrentReservations.firstOrNull()
                        if (activeReservation != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                onClick = onOpenLibrarySeat,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.EventSeat,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = "${activeReservation.location} · ${activeReservation.begin}–${activeReservation.end}",
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = activeReservation.statusLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }

                if (todayMeetings.isNotEmpty()) {
                    item {
                        ExpressiveSectionHeader(
                            title = "今日日程",
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }

                    itemsIndexed(
                        items = todayMeetings,
                        key = { idx, item -> "${idx}_${item.courseName}_${item.startPeriod}_${item.endPeriod}" },
                    ) { index, meeting ->
                        TodayCourseRow(
                            meeting = meeting,
                            position = GroupedCardPosition.forIndex(index, todayMeetings.size),
                            onClick = onOpenTimetableTab,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayCourseRow(
    meeting: CourseMeeting,
    position: GroupedCardPosition,
    onClick: () -> Unit,
) {
    ExpressiveGroupedCard(
        position = position,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = meeting.periodRangeLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = meeting.courseName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        meeting.effectiveTimeText,
                        meeting.location,
                        meeting.teacher,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
