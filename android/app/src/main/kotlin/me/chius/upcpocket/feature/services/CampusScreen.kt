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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.EventSeat
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import me.chius.upcpocket.core.designsystem.components.BentoMetricTile
import me.chius.upcpocket.core.designsystem.components.BentoWideCard
import me.chius.upcpocket.core.designsystem.components.GoogleTopBar
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

@Composable
fun CampusScreen(
    campus: CampusPreference,
    accountState: SchoolAccountState,
    gradesState: GradesUiState,
    shiguangState: ShiguangUiState,
    librarySeatState: LibrarySeatUiState,
    cardBalanceState: CardBalanceUiState,
    powerState: PowerUiState,
    bottomNavPadding: Dp,
    onSelectCampus: (CampusPreference) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGrades: () -> Unit,
    onOpenShiguang: () -> Unit,
    onOpenLibrarySeat: () -> Unit,
    onOpenFreeClassroom: () -> Unit,
    onOpenCardWallet: () -> Unit,
    onOpenPower: () -> Unit,
    onOpenAcademicCalendar: () -> Unit,
) {
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

    Column(modifier = Modifier.fillMaxSize()) {
        GoogleTopBar(
            title = "校园",
            isAccountConfigured = accountState.status == AccountStatus.SAVED_UNVERIFIED,
            onAccountClick = onOpenSettings,
            accountBadgeText = accountInitials,
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = "设置")
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 4.dp,
                bottom = bottomNavPadding + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CampusPreference.entries.forEach { option ->
                        FilterChip(
                            selected = campus == option,
                            onClick = { onSelectCampus(option) },
                            label = { Text(option.displayName) },
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val gpa = gradesSummary?.officialGpa
                    BentoMetricTile(
                        title = "成绩 · GPA",
                        icon = Icons.Rounded.School,
                        metricValue = gpa?.let { "%.2f".format(Locale.ROOT, it) } ?: "查询",
                        metricUnit = if (gpa != null) "GPA" else null,
                        subtitle = gradesSummary?.weightedAverage?.let {
                            "加权 %.2f · %.1f 学分".format(Locale.ROOT, it, gradesSummary.passedCredits)
                        } ?: "加权均分与分数推演",
                        onClick = onOpenGrades,
                        modifier = Modifier.weight(1f),
                        iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )

                    val hours = shiguangState.summary?.totalHours
                    val actCount = shiguangState.activitiesPage?.items?.size
                    BentoMetricTile(
                        title = "石光二课",
                        icon = Icons.Rounded.EmojiEvents,
                        metricValue = hours?.toPlainString() ?: actCount?.toString() ?: "二课",
                        metricUnit = if (hours != null) "学时" else if (actCount != null) "活动" else null,
                        subtitle = if (shiguangState.summary != null) {
                            "五大模块学分成绩单"
                        } else {
                            "第二课堂活动与学时"
                        },
                        onClick = onOpenShiguang,
                        modifier = Modifier.weight(1f),
                        iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val activeRes = librarySeatState.libseatCurrentReservations.firstOrNull()
                    val freeCount = librarySeatState.libseatRooms.sumOf { it.freeSeats ?: 0 }
                    BentoMetricTile(
                        title = "图书馆选座",
                        icon = Icons.Rounded.EventSeat,
                        metricValue = when {
                            activeRes != null -> activeRes.statusLabel
                            librarySeatState.libseatRooms.isNotEmpty() -> "$freeCount"
                            campus == CampusPreference.GUZHENKOU -> "古镇口"
                            else -> "唐岛湾"
                        },
                        metricUnit = if (activeRes == null && librarySeatState.libseatRooms.isNotEmpty()) "空座" else null,
                        subtitle = activeRes?.location ?: "支持选座预约与取消",
                        onClick = onOpenLibrarySeat,
                        modifier = Modifier.weight(1f),
                        iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )

                    BentoMetricTile(
                        title = "空闲教室",
                        icon = Icons.Rounded.MeetingRoom,
                        metricValue = "第 $currentPeriod 节",
                        subtitle = "${if (campus == CampusPreference.GUZHENKOU) "古镇口" else "唐岛湾"}实时空房查询",
                        onClick = onOpenFreeClassroom,
                        modifier = Modifier.weight(1f),
                        iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val cardSnap = cardBalanceState.snapshot
                    BentoMetricTile(
                        title = "校园卡包",
                        icon = Icons.Rounded.CreditCard,
                        metricValue = cardSnap?.totalBalanceText ?: "余额",
                        metricUnit = if (cardSnap != null) "元" else null,
                        subtitle = "消费流水统计与微信充值",
                        onClick = onOpenCardWallet,
                        modifier = Modifier.weight(1f),
                        iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )

                    val reading = powerState.lastReading
                    val kwhFloat = reading?.kwhText?.toFloatOrNull()
                    BentoMetricTile(
                        title = "宿舍电量",
                        icon = Icons.Rounded.Bolt,
                        metricValue = reading?.kwhText ?: if (campus == CampusPreference.GUZHENKOU) "古镇口" else "查询",
                        metricUnit = if (reading != null) "度" else null,
                        subtitle = if (reading != null) {
                            "${reading.building} · ${reading.room}"
                        } else if (campus == CampusPreference.GUZHENKOU) {
                            "唐岛湾直查 / 古镇口官方页"
                        } else {
                            "绑定宿舍后首页实时追踪"
                        },
                        progress = kwhFloat?.let { (it / 200f).coerceIn(0.05f, 1f) },
                        onClick = onOpenPower,
                        modifier = Modifier.weight(1f),
                        iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            item {
                BentoWideCard(
                    title = "官方校历",
                    subtitle = "同步教务处最新学年校历原图，支持双指缩放高清浏览与离线缓存",
                    icon = Icons.Rounded.CalendarMonth,
                    trailingBadge = "查看",
                    onClick = onOpenAcademicCalendar,
                    iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
