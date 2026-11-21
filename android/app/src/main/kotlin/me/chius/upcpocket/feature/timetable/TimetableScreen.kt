package me.chius.upcpocket.feature.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import me.chius.upcpocket.core.auth.UpcTimetableAuthEndpoints
import me.chius.upcpocket.core.designsystem.components.ExpressiveGroupedCard
import me.chius.upcpocket.core.designsystem.components.ExpressiveHeroCard
import me.chius.upcpocket.core.designsystem.components.ExpressiveListRow
import me.chius.upcpocket.core.designsystem.components.ExpressiveSectionHeader
import me.chius.upcpocket.core.designsystem.components.ExpressiveStatusBadge
import me.chius.upcpocket.core.designsystem.theme.GroupedCardPosition
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.SchoolAccountState
import me.chius.upcpocket.core.webview.ControlledSchoolWebViewDialog
import me.chius.upcpocket.core.webview.ControlledWebViewContract

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    accountState: SchoolAccountState,
    uiState: TimetableUiState,
    bottomNavPadding: Dp,
    onOpenSchoolAccount: () -> Unit,
    onRefresh: () -> Unit,
    onSelectWeek: (TimetableTermInfo, Int) -> Unit,
    onAdoptWebViewCookies: (Map<String, String>, ControlledWebViewContract.SessionContext) -> Unit,
    onDismissInteraction: () -> Unit,
    onClearCache: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showWebViewDialog by remember { mutableStateOf(false) }
    var selectedMeeting by remember { mutableStateOf<CourseMeeting?>(null) }

    val activeInteractionSession = uiState.interactionSession
    if (showWebViewDialog && activeInteractionSession != null &&
        activeInteractionSession.isStillValidFor(accountState)
    ) {
        ControlledSchoolWebViewDialog(
            sessionContext = activeInteractionSession,
            accountState = accountState,
            scopeHosts = ControlledWebViewContract.timetableScopeHosts,
            targetCookieHost = UpcTimetableAuthEndpoints.APP_HOST,
            completionPathPrefixes = setOf("/site/weekschedule/"),
            onCookiesCaptured = { cookies ->
                showWebViewDialog = false
                onAdoptWebViewCookies(cookies, activeInteractionSession)
            },
            onDismiss = {
                showWebViewDialog = false
                onDismissInteraction()
            },
        )
    }

    selectedMeeting?.let { meeting ->
        CourseDetailBottomSheet(
            meeting = meeting,
            onDismiss = { selectedMeeting = null },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.systemBars.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
        ),
        topBar = {
            LargeTopAppBar(
                title = { Text("课表") },
                actions = {
                    if (uiState.weekData != null || uiState.terms.isNotEmpty()) {
                        IconButton(
                            onClick = onClearCache,
                            enabled = !uiState.isRefreshing,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "清除离线课表缓存",
                            )
                        }
                    }
                    if (accountState.status == AccountStatus.SAVED_UNVERIFIED) {
                        IconButton(
                            onClick = onRefresh,
                            enabled = !uiState.isRefreshing,
                        ) {
                            if (uiState.isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "同步课表",
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = bottomNavPadding + 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                ExpressiveSectionHeader(
                    title = "今日日程待办",
                    subtitle = "与课表共享同一视图，本机待办数据将在 M2 接入",
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            item {
                ExpressiveListRow(
                    title = "今日暂无本机待办",
                    subtitle = "M2 将接入本地 Room 待办、截止日期与一次性系统提醒",
                    leadingIcon = Icons.Rounded.CheckCircleOutline,
                    leadingContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    leadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    position = GroupedCardPosition.SINGLE,
                    trailingContent = {
                        ExpressiveStatusBadge(
                            text = "M2",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.padding(bottom = 18.dp),
                )
            }

            item {
                ExpressiveSectionHeader(
                    title = "周视图（周日–周六 · 1–12 节）",
                    subtitle = uiState.statusDetail,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            if (accountState.status != AccountStatus.SAVED_UNVERIFIED) {
                item {
                    val (badge, subtitle) = when (accountState.status) {
                        AccountStatus.UNSET ->
                            "未配置账号" to "请先前往「学校账号」保存学号与统一认证密码，方可同步学校课表。"
                        AccountStatus.RECOVERY_REQUIRED ->
                            "需要重新保存" to "加密状态已失效或需要重置，请先重新保存学校账号凭据。"
                        AccountStatus.SAVED_UNVERIFIED -> "" to ""
                    }
                    ExpressiveHeroCard(
                        title = "只读周课表",
                        subtitle = subtitle,
                        icon = Icons.Rounded.CalendarMonth,
                        badgeText = badge,
                        onClick = onOpenSchoolAccount,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            } else {
                item {
                    TimetableControlCard(
                        uiState = uiState,
                        onRefresh = onRefresh,
                        onSelectWeek = onSelectWeek,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }

                if (uiState.connectionStatus == TimetableConnectionStatus.INTERACTION_REQUIRED &&
                    activeInteractionSession != null
                ) {
                    item {
                        InteractionChallengeCard(
                            reason = uiState.interactionReason ?: "需要图形或短信验证码验证",
                            onOpenWebView = { showWebViewDialog = true },
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                } else if (uiState.connectionStatus == TimetableConnectionStatus.INVALID_CREDENTIALS) {
                    item {
                        InvalidCredentialBanner(
                            message = uiState.errorMessage ?: "学号或统一认证密码错误",
                            onOpenAccount = onOpenSchoolAccount,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                } else if (!uiState.errorMessage.isNullOrBlank()) {
                    item {
                        ErrorStatusBanner(
                            message = uiState.errorMessage,
                            isOfflineFallback = uiState.isOfflineFallback,
                            onRetry = onRefresh,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                }

                if (uiState.isOfflineFallback && uiState.weekData != null) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                val syncedText = formatTimestamp(uiState.fetchedAtEpochMillis)
                                Text(
                                    text = if (syncedText != null) {
                                        "当前显示本机离线缓存（同步于 $syncedText）"
                                    } else {
                                        "当前显示本机离线缓存"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                val weekData = uiState.weekData
                if (weekData != null) {
                    if (weekData.isEmpty) {
                        item {
                            ExpressiveGroupedCard(
                                position = GroupedCardPosition.SINGLE,
                                modifier = Modifier.padding(bottom = 12.dp),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = "${weekData.term.label} · 第 ${weekData.week} 周暂无排课记录",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        text = "学校服务端返回完整 7 天无课程安排；可切换上下周或点击右上角刷新确认。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    } else {
                        item {
                            TimetableWeekGrid(
                                weekData = weekData,
                                onSelectMeeting = { selectedMeeting = it },
                                modifier = Modifier.padding(bottom = 14.dp),
                            )
                        }
                    }
                } else if (!uiState.isRefreshing &&
                    uiState.connectionStatus != TimetableConnectionStatus.ERROR
                ) {
                    item {
                        ExpressiveHeroCard(
                            title = "等待初次同步",
                            subtitle = "点击「同步课表」将仅针对 cas.upc.edu.cn 提交一次统一认证（遇验证码自动转入受控安全页），成功后离线缓存本周课程。",
                            icon = Icons.Rounded.CalendarMonth,
                            badgeText = accountState.status.label,
                            onClick = onRefresh,
                            modifier = Modifier.padding(bottom = 14.dp),
                        )
                    }
                }
            }

            item {
                ExpressiveGroupedCard(
                    position = GroupedCardPosition.TOP,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "学校官方作息时间（2020-05-25 调整版）",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        ScheduleLine("上午 1–5 节", "08:00 – 12:20")
                        ScheduleLine("下午 6–9 节", "14:00 – 17:30")
                        ScheduleLine("晚间 10–12 节", "19:00 – 21:25")
                        ScheduleLine("第 13–14 节", "时间待确认")
                    }
                }
            }

            item {
                ExpressiveListRow(
                    title = if (accountState.status == AccountStatus.SAVED_UNVERIFIED) {
                        "管理学校账号"
                    } else {
                        "前往配置学校账号"
                    },
                    subtitle = "仅向精确校验的 cas.upc.edu.cn HTTPS 表单提交认证",
                    leadingIcon = Icons.Rounded.Schedule,
                    position = GroupedCardPosition.BOTTOM,
                    trailingContent = {
                        FilledTonalButton(onClick = onOpenSchoolAccount) {
                            Text("打开")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TimetableControlCard(
    uiState: TimetableUiState,
    onRefresh: () -> Unit,
    onSelectWeek: (TimetableTermInfo, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedTerm = uiState.selectedTerm
    val selectedWeek = uiState.selectedWeek
    var showTermMenu by remember { mutableStateOf(false) }
    var showWeekMenu by remember { mutableStateOf(false) }

    ExpressiveGroupedCard(
        position = GroupedCardPosition.SINGLE,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (uiState.terms.isNotEmpty() && selectedTerm != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        OutlinedButton(
                            onClick = { showTermMenu = true },
                            enabled = uiState.terms.size > 1 && !uiState.isRefreshing,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = selectedTerm.label,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            if (uiState.terms.size > 1) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Rounded.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = showTermMenu,
                            onDismissRequest = { showTermMenu = false },
                        ) {
                            uiState.terms.forEach { term ->
                                DropdownMenuItem(
                                    text = { Text(term.label) },
                                    onClick = {
                                        showTermMenu = false
                                        val targetWeek = (selectedWeek ?: 1).coerceIn(1, term.totalWeeks)
                                        onSelectWeek(term, targetWeek)
                                    },
                                )
                            }
                        }
                    }

                    if (uiState.currentSchoolWeek != null) {
                        ExpressiveStatusBadge(
                            text = "学校当前第 ${uiState.currentSchoolWeek} 周",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectedTerm != null && selectedWeek != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = { onSelectWeek(selectedTerm, selectedWeek - 1) },
                            enabled = selectedWeek > 1 && !uiState.isRefreshing,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                                contentDescription = "上一周",
                            )
                        }
                        Box {
                            FilledTonalButton(
                                onClick = { showWeekMenu = true },
                                enabled = !uiState.isRefreshing,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = if (selectedWeek == uiState.currentSchoolWeek) {
                                        "第 $selectedWeek 周 · 本周"
                                    } else {
                                        "第 $selectedWeek 周 / 共 ${selectedTerm.totalWeeks} 周"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            DropdownMenu(
                                expanded = showWeekMenu,
                                onDismissRequest = { showWeekMenu = false },
                            ) {
                                (1..selectedTerm.totalWeeks).forEach { weekNum ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (weekNum == uiState.currentSchoolWeek) {
                                                    "第 $weekNum 周（本周）"
                                                } else {
                                                    "第 $weekNum 周"
                                                },
                                            )
                                        },
                                        onClick = {
                                            showWeekMenu = false
                                            onSelectWeek(selectedTerm, weekNum)
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = { onSelectWeek(selectedTerm, selectedWeek + 1) },
                            enabled = selectedWeek < selectedTerm.totalWeeks && !uiState.isRefreshing,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = "下一周",
                            )
                        }
                    }
                } else {
                    Text(
                        text = "同步当前学校周课表",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Button(
                    onClick = onRefresh,
                    enabled = !uiState.isRefreshing,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(if (uiState.weekData == null) "同步课表" else "刷新")
                }
            }
        }
    }
}

@Composable
private fun InteractionChallengeCard(
    reason: String,
    onOpenWebView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(imageVector = Icons.Rounded.LockOpen, contentDescription = null)
                Text(
                    text = "需要完成学校安全验证",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                text = "$reason。客户端已自动停止密码提交并保留离线缓存，请使用唯一受控 HTTPS 验证页完成一次登录。",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onOpenWebView) {
                Text("打开受控安全验证页")
            }
        }
    }
}

@Composable
private fun InvalidCredentialBanner(
    message: String,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(imageVector = Icons.Rounded.WarningAmber, contentDescription = null)
                Text("学号或密码验证失败", style = MaterialTheme.typography.titleMedium)
            }
            Text(text = message, style = MaterialTheme.typography.bodySmall)
            FilledTonalButton(onClick = onOpenAccount) {
                Text("前往核对学校账号")
            }
        }
    }
}

@Composable
private fun ErrorStatusBanner(
    message: String,
    isOfflineFallback: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (isOfflineFallback) "同步失败（$message）" else message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text("重试")
            }
        }
    }
}

@Composable
private fun TimetableWeekGrid(
    weekData: TimetableWeek,
    onSelectMeeting: (CourseMeeting) -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxPeriod = maxOf(12, weekData.meetings.maxOfOrNull { it.endPeriod } ?: 12).coerceAtMost(14)
    val slotHeight = 52.dp
    val todayIso = remember {
        LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    val colorSlots = listOf(
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer,
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer,
        MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurface,
    )

    ExpressiveGroupedCard(
        position = GroupedCardPosition.SINGLE,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        ) {
            // Day Header: Sunday -> Saturday
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.width(34.dp))
                weekData.days.forEachIndexed { index, day ->
                    val isToday = day.dateIso == todayIso
                    val dayTitle = DAY_TITLES.getOrElse(index) { "" }
                    val shortDate = day.dateIso.drop(5).replace('-', '/')
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isToday) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Transparent
                                },
                            )
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = dayTitle,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                            color = if (isToday) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            text = shortDate,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isToday) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(modifier = Modifier.fillMaxWidth()) {
                // Left Period Axis (1..maxPeriod)
                Column(modifier = Modifier.width(34.dp)) {
                    for (period in 1..maxPeriod) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(slotHeight),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = period.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // 7 Day Columns
                weekData.days.forEach { day ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(slotHeight * maxPeriod),
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            for (period in 1..maxPeriod) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(slotHeight)
                                        .border(
                                            width = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                        ),
                                )
                            }
                        }

                        day.meetings.forEach { meeting ->
                            val span = (meeting.endPeriod - meeting.startPeriod + 1).coerceAtLeast(1)
                            val topOffset = slotHeight * (meeting.startPeriod - 1)
                            val blockHeight = slotHeight * span
                            val slotIndex = abs((meeting.courseId ?: meeting.courseName).hashCode()) % colorSlots.size
                            val (bg, fg) = colorSlots[slotIndex]

                            Surface(
                                color = bg,
                                contentColor = fg,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(1.5.dp)
                                    .padding(top = topOffset)
                                    .height((blockHeight - 3.dp).coerceAtLeast(24.dp))
                                    .clickable { onSelectMeeting(meeting) },
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 4.dp, vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = meeting.courseName,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = if (span >= 2) 3 else 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!meeting.location.isNullOrBlank()) {
                                        Text(
                                            text = "@${meeting.location}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = fg.copy(alpha = 0.85f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseDetailBottomSheet(
    meeting: CourseMeeting,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = meeting.courseName,
                style = MaterialTheme.typography.headlineSmall,
            )
            DetailLine("上课日期", meeting.dateIso)
            DetailLine("节次", "${meeting.periodRangeLabel}（${meeting.effectiveTimeText}）")
            if (meeting.sectionsSource == "lessArr_split_noncontiguous") {
                DetailLine(
                    "完整原始节次",
                    meeting.sourcePeriods.joinToString("、") { "第 $it 节" },
                )
            }
            DetailLine("上课地点", meeting.location ?: "未提供地点")
            DetailLine("任课教师", meeting.teacher ?: "未提供教师")
            DetailLine("周次", "${meeting.weekLabel}${meeting.weekType?.let { "（$it）" }.orEmpty()}")
            meeting.courseType?.let { DetailLine("课程性质", it) }
            meeting.credit?.let { DetailLine("学分", it) }
            meeting.examType?.let { DetailLine("考核方式", it) }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun ScheduleLine(label: String, time: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = time,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatTimestamp(epochMillis: Long?): String? {
    if (epochMillis == null || epochMillis <= 0L) return null
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.of("Asia/Shanghai"))
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}

private val DAY_TITLES = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
