package me.chius.upcpocket.feature.timetable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.ViewWeek
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlinx.coroutines.launch
import me.chius.upcpocket.core.auth.UpcTimetableAuthEndpoints
import me.chius.upcpocket.core.designsystem.components.HeroAtAGlanceCard
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.SchoolAccountState
import me.chius.upcpocket.core.webview.ControlledSchoolWebViewDialog
import me.chius.upcpocket.core.webview.ControlledWebViewContract

private enum class TimetableViewMode {
    GRID,
    AGENDA,
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class,
)
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
) {
    var showWebViewDialog by remember { mutableStateOf(false) }
    var showWeekPickerSheet by remember { mutableStateOf(false) }
    var selectedMeeting by remember { mutableStateOf<CourseMeeting?>(null) }
    var viewMode by remember { mutableStateOf(TimetableViewMode.GRID) }

    val cachedWeeks = remember { mutableStateMapOf<String, TimetableWeek>() }
    LaunchedEffect(uiState.weekData) {
        val w = uiState.weekData ?: return@LaunchedEffect
        cachedWeeks["${w.term.year}_${w.term.term}_${w.week}"] = w
    }

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

    val selectedTerm = uiState.selectedTerm ?: uiState.weekData?.term
    val selectedWeekNum = uiState.selectedWeek ?: uiState.weekData?.week ?: 1
    val totalWeeks = selectedTerm?.totalWeeks ?: 20

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = (selectedWeekNum - 1).coerceIn(0, (totalWeeks - 1).coerceAtLeast(0)),
        pageCount = { totalWeeks.coerceAtLeast(1) },
    )

    LaunchedEffect(selectedWeekNum, selectedTerm) {
        val targetPage = (selectedWeekNum - 1).coerceIn(0, (totalWeeks - 1).coerceAtLeast(0))
        if (pagerState.currentPage != targetPage && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState.settledPage, selectedTerm) {
        val term = selectedTerm ?: return@LaunchedEffect
        val targetWeek = pagerState.settledPage + 1
        if (targetWeek != selectedWeekNum && targetWeek in 1..term.totalWeeks) {
            onSelectWeek(term, targetWeek)
        }
    }

    if (showWeekPickerSheet && selectedTerm != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showWeekPickerSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "选择教学周与学期",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (uiState.terms.size > 1) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        uiState.terms.forEach { term ->
                            FilterChip(
                                selected = term.year == selectedTerm.year && term.term == selectedTerm.term,
                                onClick = {
                                    showWeekPickerSheet = false
                                    onSelectWeek(term, selectedWeekNum.coerceIn(1, term.totalWeeks))
                                },
                                label = { Text(term.label) },
                            )
                        }
                    }
                } else {
                    Text(
                        text = selectedTerm.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (1..selectedTerm.totalWeeks).forEach { week ->
                        val isCurrentSchool = week == uiState.currentSchoolWeek
                        val isSelected = week == selectedWeekNum
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                showWeekPickerSheet = false
                                scope.launch { pagerState.animateScrollToPage(week - 1) }
                                onSelectWeek(selectedTerm, week)
                            },
                            label = {
                                Text(
                                    text = if (isCurrentSchool) "第${week}周 · 本周" else "第${week}周",
                                    fontWeight = if (isCurrentSchool) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        onClick = { if (selectedTerm != null) showWeekPickerSheet = true },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "第 $selectedWeekNum 周",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            if (selectedTerm != null) {
                                Icon(
                                    imageVector = Icons.Rounded.ExpandMore,
                                    contentDescription = "选择教学周",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }

                    val schoolWeek = uiState.currentSchoolWeek
                    if (selectedTerm != null && schoolWeek != null && selectedWeekNum != schoolWeek) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            onClick = {
                                scope.launch { pagerState.animateScrollToPage(schoolWeek - 1) }
                                onSelectWeek(selectedTerm, schoolWeek)
                            },
                        ) {
                            Text(
                                text = "回本周",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            viewMode = if (viewMode == TimetableViewMode.GRID) {
                                TimetableViewMode.AGENDA
                            } else {
                                TimetableViewMode.GRID
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (viewMode == TimetableViewMode.GRID) {
                                Icons.Rounded.ViewAgenda
                            } else {
                                Icons.Rounded.ViewWeek
                            },
                            contentDescription = "切换周网格与日程列表",
                        )
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
                                Icon(Icons.Rounded.Refresh, contentDescription = "同步课表")
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            if (uiState.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (uiState.isOfflineFallback) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.CloudOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            text = "当前显示离线缓存课表",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }

            if (uiState.connectionStatus == TimetableConnectionStatus.INTERACTION_REQUIRED &&
                activeInteractionSession != null
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = uiState.interactionReason ?: "需要完成学校安全验证",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(onClick = { showWebViewDialog = true }) {
                            Icon(Icons.Rounded.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("继续验证")
                        }
                    }
                }
            }

            if (!uiState.errorMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = uiState.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            when {
                accountState.status != AccountStatus.SAVED_UNVERIFIED -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        HeroAtAGlanceCard(onClick = onOpenSchoolAccount) {
                            Text(
                                text = "请先配置学校账号",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "登录统一身份认证后即可查看本学期完整课表。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = onOpenSchoolAccount) {
                                Text("前往学校账号")
                            }
                        }
                    }
                }
                uiState.weekData == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        HeroAtAGlanceCard {
                            Text(
                                text = "暂未同步课表数据",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "点击下方按钮从教务课表系统获取本周课程。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = onRefresh, enabled = !uiState.isRefreshing) {
                                Text(if (uiState.isRefreshing) "同步中…" else "立即同步课表")
                            }
                        }
                    }
                }
                else -> {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        val weekNumber = page + 1
                        val termKey = selectedTerm?.let { "${it.year}_${it.term}_$weekNumber" }
                        val pageWeekData = termKey?.let { cachedWeeks[it] }
                            ?: uiState.weekData.takeIf { it.week == weekNumber }

                        if (pageWeekData == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = bottomNavPadding),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        } else if (viewMode == TimetableViewMode.GRID) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = 10.dp,
                                    end = 10.dp,
                                    top = 4.dp,
                                    bottom = bottomNavPadding + 18.dp,
                                ),
                            ) {
                                item {
                                    SmartWeekGridCard(
                                        weekData = pageWeekData,
                                        onSelectMeeting = { selectedMeeting = it },
                                    )
                                }
                            }
                        } else {
                            AgendaWeekList(
                                weekData = pageWeekData,
                                bottomNavPadding = bottomNavPadding,
                                onSelectMeeting = { selectedMeeting = it },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartWeekGridCard(
    weekData: TimetableWeek,
    onSelectMeeting: (CourseMeeting) -> Unit,
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
        MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface,
    )

    val columnWeights = weekData.days.map { day ->
        if (day.weekday in setOf(6, 7) && day.meetings.isEmpty()) 0.58f else 1.2f
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.width(28.dp))
                weekData.days.forEachIndexed { idx, day ->
                    val isToday = day.dateIso == todayIso
                    val dayTitle = weekdayShortTitle(day.weekday)
                    val shortDate = day.dateIso.drop(5).replace('-', '/')
                    Column(
                        modifier = Modifier
                            .weight(columnWeights[idx])
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isToday) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
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
                            maxLines = 1,
                        )
                        Text(
                            text = shortDate,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isToday) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.width(28.dp)) {
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

                weekData.days.forEachIndexed { idx, day ->
                    Box(
                        modifier = Modifier
                            .weight(columnWeights[idx])
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
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
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

@Composable
private fun AgendaWeekList(
    weekData: TimetableWeek,
    bottomNavPadding: Dp,
    onSelectMeeting: (CourseMeeting) -> Unit,
) {
    val activeDays = weekData.days.filter { it.meetings.isNotEmpty() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = bottomNavPadding + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (activeDays.isEmpty()) {
            item {
                HeroAtAGlanceCard {
                    Text("本周暂无排课", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            activeDays.forEach { day ->
                item(key = "header_${day.weekday}_${day.dateIso}") {
                    Text(
                        text = "${weekdayFullTitle(day.weekday)} · ${day.dateIso}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                    )
                }
                day.meetings.forEach { meeting ->
                    item(key = "agenda_${day.weekday}_${meeting.startPeriod}_${meeting.courseName}") {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            onClick = { onSelectMeeting(meeting) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ) {
                                    Text(
                                        text = meeting.periodRangeLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = meeting.courseName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = listOfNotNull(
                                            meeting.effectiveTimeText,
                                            meeting.location,
                                            meeting.teacher,
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = meeting.courseName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            DetailLine("上课日期", meeting.dateIso)
            DetailLine("节次", "${meeting.periodRangeLabel}（${meeting.effectiveTimeText}）")
            if (meeting.sectionsSource == "lessArr_split_noncontiguous") {
                DetailLine("完整原始节次", meeting.sourcePeriods.joinToString("、") { "第 $it 节" })
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

private fun weekdayShortTitle(weekday: Int): String = when (weekday) {
    1 -> "一"
    2 -> "二"
    3 -> "三"
    4 -> "四"
    5 -> "五"
    6 -> "六"
    7 -> "日"
    else -> weekday.toString()
}

private fun weekdayFullTitle(weekday: Int): String = when (weekday) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    7 -> "周日"
    else -> "第 $weekday 天"
}
