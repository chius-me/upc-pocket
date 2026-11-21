package me.chius.upcpocket.feature.services

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.EventSeat
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import me.chius.upcpocket.core.designsystem.components.ExpressiveListRow
import me.chius.upcpocket.core.designsystem.components.ExpressiveSectionHeader
import me.chius.upcpocket.core.designsystem.components.ExpressiveStatusBadge
import me.chius.upcpocket.core.designsystem.components.GoogleSearchPillBar
import me.chius.upcpocket.core.designsystem.components.QuickSearchSheet
import me.chius.upcpocket.core.designsystem.theme.GroupedCardPosition
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.CampusPreference
import me.chius.upcpocket.core.model.SchoolAccountState
import me.chius.upcpocket.feature.card.CardBalanceUiState
import me.chius.upcpocket.feature.grades.GpaCalculator
import me.chius.upcpocket.feature.grades.GradeFilterPreset
import me.chius.upcpocket.feature.grades.GradesUiState
import me.chius.upcpocket.feature.library.LibrarySeatUiState
import me.chius.upcpocket.feature.power.PowerUiState
import me.chius.upcpocket.feature.shiguang.ShiguangUiState
import me.chius.upcpocket.feature.timetable.TimetableSchedule
import me.chius.upcpocket.feature.timetable.TimetableUiState

@Composable
fun CampusScreen(
    campus: CampusPreference,
    accountState: SchoolAccountState,
    timetableState: TimetableUiState,
    gradesState: GradesUiState,
    shiguangState: ShiguangUiState,
    librarySeatState: LibrarySeatUiState,
    cardBalanceState: CardBalanceUiState,
    powerState: PowerUiState,
    bottomNavPadding: Dp,
    onSelectCampus: (CampusPreference) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSchoolAccount: () -> Unit,
    onOpenTimetableTab: () -> Unit,
    onOpenGrades: () -> Unit,
    onOpenShiguang: () -> Unit,
    onOpenLibrarySeat: () -> Unit,
    onOpenFreeClassroom: () -> Unit,
    onOpenCardWallet: () -> Unit,
    onOpenPower: () -> Unit,
    onOpenAcademicCalendar: () -> Unit,
) {
    var showSearchSheet by remember { mutableStateOf(false) }

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

    val accountInitials = accountState.maskedLoginName?.take(2)?.uppercase(Locale.ROOT)
    val gradesSummary = remember(gradesState.snapshot) {
        gradesState.snapshot?.let { snap ->
            GpaCalculator.calculate(
                GpaCalculator.filterByPreset(snap.records, GradeFilterPreset.ALL, null),
            )
        }
    }
    val currentPeriod = remember {
        val now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Shanghai"))
        TimetableSchedule.currentOrNextPeriod(now.hour * 60 + now.minute)
    }
    val campusLabel = if (campus == CampusPreference.GUZHENKOU) "古镇口" else "唐岛湾"

    Column(modifier = Modifier.fillMaxSize()) {
        GoogleSearchPillBar(
            placeholder = "搜索校园服务与功能",
            isAccountConfigured = accountState.status == AccountStatus.SAVED_UNVERIFIED,
            onBarClick = { showSearchSheet = true },
            onAccountClick = onOpenSettings,
            accountBadgeText = accountInitials,
        )

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
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp),
                ) {
                    val options = CampusPreference.entries
                    options.forEachIndexed { index, option ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                            selected = campus == option,
                            onClick = { onSelectCampus(option) },
                            label = {
                                Text(
                                    text = option.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }

            item {
                ExpressiveSectionHeader(
                    title = "学业与教务",
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            item {
                val gpa = gradesSummary?.officialGpa
                val gradeBadge = gpa?.let { "GPA %.2f".format(Locale.ROOT, it) } ?: "查询"
                val gradeSubtitle = gradesSummary?.weightedAverage?.let {
                    "加权 %.2f · %.1f 学分".format(Locale.ROOT, it, gradesSummary.passedCredits)
                } ?: "教务成绩单、加权均分与分数推演"
                ExpressiveListRow(
                    title = "成绩与 GPA",
                    subtitle = gradeSubtitle,
                    leadingIcon = Icons.Rounded.School,
                    leadingContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    leadingContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    position = GroupedCardPosition.TOP,
                    trailingContent = { TrailingValueChevron(gradeBadge) },
                    onClick = onOpenGrades,
                )
            }

            item {
                val hours = shiguangState.summary?.totalHours
                val actCount = shiguangState.activitiesPage?.items?.size
                val shiguangBadge = when {
                    hours != null -> "${hours.stripTrailingZeros().toPlainString()} 学时"
                    actCount != null -> "$actCount 项"
                    else -> "进入"
                }
                ExpressiveListRow(
                    title = "石光第二课堂",
                    subtitle = if (shiguangState.summary != null) {
                        "五大模块学分学时成绩单"
                    } else {
                        "第二课堂活动列表与学时统计"
                    },
                    leadingIcon = Icons.Rounded.EmojiEvents,
                    leadingContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    leadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    position = GroupedCardPosition.MIDDLE,
                    trailingContent = { TrailingValueChevron(shiguangBadge) },
                    onClick = onOpenShiguang,
                )
            }

            item {
                ExpressiveListRow(
                    title = "官方校历",
                    subtitle = "教务处最新学年校历高清浏览与离线缓存",
                    leadingIcon = Icons.Rounded.CalendarMonth,
                    leadingContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    leadingContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    position = GroupedCardPosition.BOTTOM,
                    modifier = Modifier.padding(bottom = 14.dp),
                    trailingContent = { TrailingValueChevron("查看") },
                    onClick = onOpenAcademicCalendar,
                )
            }

            item {
                ExpressiveSectionHeader(
                    title = "学习空间",
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            item {
                val activeRes = librarySeatState.libseatCurrentReservations.firstOrNull()
                val freeCount = librarySeatState.libseatRooms.sumOf { it.freeSeats ?: 0 }
                val seatBadge = when {
                    activeRes != null -> activeRes.statusLabel
                    librarySeatState.libseatRooms.isNotEmpty() -> "余 $freeCount 座"
                    else -> "选座"
                }
                ExpressiveListRow(
                    title = "图书馆选座",
                    subtitle = activeRes?.location ?: "${if (campus == CampusPreference.GUZHENKOU) "古镇口 / 求是" else "唐岛湾"}预约选座与取消",
                    leadingIcon = Icons.Rounded.EventSeat,
                    leadingContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    leadingContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    position = GroupedCardPosition.TOP,
                    trailingContent = { TrailingValueChevron(seatBadge) },
                    onClick = onOpenLibrarySeat,
                )
            }

            item {
                ExpressiveListRow(
                    title = "空闲教室",
                    subtitle = "${campusLabel}校区实时空房查询",
                    leadingIcon = Icons.Rounded.MeetingRoom,
                    leadingContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    leadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    position = GroupedCardPosition.BOTTOM,
                    modifier = Modifier.padding(bottom = 14.dp),
                    trailingContent = { TrailingValueChevron("第 $currentPeriod 节") },
                    onClick = onOpenFreeClassroom,
                )
            }

            item {
                ExpressiveSectionHeader(
                    title = "校园生活",
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            item {
                val cardSnap = cardBalanceState.snapshot
                val cardBadge = cardSnap?.totalBalanceText?.let { "¥$it" } ?: "钱包"
                ExpressiveListRow(
                    title = "校园卡与充值",
                    subtitle = "消费流水柱图统计与微信充值",
                    leadingIcon = Icons.Rounded.CreditCard,
                    leadingContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    leadingContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    position = GroupedCardPosition.TOP,
                    trailingContent = { TrailingValueChevron(cardBadge) },
                    onClick = onOpenCardWallet,
                )
            }

            item {
                val reading = powerState.lastReading
                val powerBadge = reading?.kwhText?.let { "$it 度" } ?: "查询"
                val powerSubtitle = when {
                    reading != null -> "${reading.building} · ${reading.room}"
                    powerState.hasBoundDormitory -> "已绑定 ${powerState.boundBuilding} · ${powerState.boundRoom}"
                    campus == CampusPreference.GUZHENKOU -> "唐岛湾直查 / 古镇口官方页"
                    else -> "绑定常用宿舍后首页自动追踪"
                }
                ExpressiveListRow(
                    title = "宿舍电量",
                    subtitle = powerSubtitle,
                    leadingIcon = Icons.Rounded.Bolt,
                    leadingContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    leadingContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    position = GroupedCardPosition.BOTTOM,
                    trailingContent = { TrailingValueChevron(powerBadge) },
                    onClick = onOpenPower,
                )
            }
        }
    }
}

@Composable
private fun TrailingValueChevron(badgeText: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExpressiveStatusBadge(
            text = badgeText,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
