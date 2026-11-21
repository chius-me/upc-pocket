package me.chius.upcpocket.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.chius.upcpocket.core.designsystem.components.ExpressiveHeroCard
import me.chius.upcpocket.core.designsystem.components.ExpressiveListRow
import me.chius.upcpocket.core.designsystem.components.ExpressiveSectionHeader
import me.chius.upcpocket.core.designsystem.components.ExpressiveStatusBadge
import me.chius.upcpocket.core.designsystem.theme.GroupedCardPosition
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.CampusPreference
import me.chius.upcpocket.core.model.SchoolAccountState
import me.chius.upcpocket.feature.card.CardBalanceUiState
import me.chius.upcpocket.feature.power.PowerUiState
import me.chius.upcpocket.feature.timetable.TimetableConnectionStatus
import me.chius.upcpocket.feature.timetable.TimetableUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    campus: CampusPreference,
    accountState: SchoolAccountState,
    cardState: CardBalanceUiState,
    powerState: PowerUiState,
    timetableState: TimetableUiState,
    bottomNavPadding: Dp,
    onOpenSettings: () -> Unit,
    onOpenSchoolAccount: () -> Unit,
    onOpenTimetableTab: () -> Unit,
    onOpenCardBalance: () -> Unit,
    onOpenPower: () -> Unit,
    onOpenFreeClassroom: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.systemBars.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
        ),
        topBar = {
            LargeTopAppBar(
                title = { Text("UPC Pocket") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "设置",
                        )
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = onOpenSettings,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LocationOn,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            Text(
                                text = "当前校区：${campus.displayName}",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    ExpressiveStatusBadge(
                        text = "M1b 课表与认证",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                val heroSubtitle = when (accountState.status) {
                    AccountStatus.UNSET -> "默认不保存凭据；点击配置统一学校账号，加密保存在本机。"
                    AccountStatus.SAVED_UNVERIFIED -> {
                        val accountLabel = accountState.maskedLoginName ?: "已配置"
                        "账号 $accountLabel 已加密保存在本机（尚未向学校服务端发起验证）。"
                    }
                    AccountStatus.RECOVERY_REQUIRED -> "本机加密状态异常或密钥失效，请点击重新保存。"
                }
                val badgeColor = when (accountState.status) {
                    AccountStatus.UNSET -> MaterialTheme.colorScheme.surfaceContainerHighest
                    AccountStatus.SAVED_UNVERIFIED -> MaterialTheme.colorScheme.secondaryContainer
                    AccountStatus.RECOVERY_REQUIRED -> MaterialTheme.colorScheme.errorContainer
                }
                val badgeContentColor = when (accountState.status) {
                    AccountStatus.UNSET -> MaterialTheme.colorScheme.onSurfaceVariant
                    AccountStatus.SAVED_UNVERIFIED -> MaterialTheme.colorScheme.onSecondaryContainer
                    AccountStatus.RECOVERY_REQUIRED -> MaterialTheme.colorScheme.onErrorContainer
                }

                ExpressiveHeroCard(
                    title = "学校账号",
                    subtitle = heroSubtitle,
                    icon = Icons.Rounded.Person,
                    badgeText = accountState.status.label,
                    badgeContainerColor = badgeColor,
                    badgeContentColor = badgeContentColor,
                    onClick = onOpenSchoolAccount,
                    modifier = Modifier.padding(bottom = 18.dp),
                )
            }

            item {
                ExpressiveSectionHeader(
                    title = "今日概览",
                    subtitle = "点击卡片直达对应原生查询页",
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            item {
                val todaySummary = timetableState.todaySummary
                val timetableSubtitle = when {
                    accountState.status != AccountStatus.SAVED_UNVERIFIED ->
                        "配置学校账号后可同步周日–周六课表"
                    todaySummary != null && todaySummary.courses.isEmpty() ->
                        "第 ${todaySummary.weekNumber ?: "-"} 周 · 今日暂无排课${if (todaySummary.isOfflineCache) "（缓存）" else ""}"
                    todaySummary != null -> {
                        val first = todaySummary.courses.first()
                        val loc = first.location?.let { " @ $it" }.orEmpty()
                        "今日 ${todaySummary.courses.size} 节课 · 首节 ${first.courseName}$loc"
                    }
                    timetableState.connectionStatus == TimetableConnectionStatus.INTERACTION_REQUIRED ->
                        "需要前往课表页通过安全验证后同步"
                    timetableState.connectionStatus == TimetableConnectionStatus.INVALID_CREDENTIALS ->
                        "学校账号或密码错误，请核对后重试"
                    else -> "点击前往课表同步当前教学周"
                }
                ExpressiveListRow(
                    title = "今日课表",
                    subtitle = timetableSubtitle,
                    leadingIcon = Icons.Rounded.CalendarToday,
                    position = GroupedCardPosition.TOP,
                    trailingContent = {
                        if (todaySummary != null) {
                            ExpressiveStatusBadge(
                                text = "${todaySummary.courses.size} 节",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = onOpenTimetableTab,
                )
            }

            item {
                val snap = cardState.snapshot
                val cardSubtitle = when {
                    snap != null -> "本机缓存总额 ¥${snap.totalBalanceText}（卡内 ¥${snap.balanceText} + 过渡 ¥${snap.transitionText}）"
                    accountState.status == AccountStatus.SAVED_UNVERIFIED && accountState.isValidStudentId ->
                        "已识别本人学号，点击查询校园卡余额"
                    accountState.status == AccountStatus.SAVED_UNVERIFIED ->
                        "当前登录名不符合学号规则，仅接受本人有效学号"
                    else -> "未配置账号；仅接受本人有效学号查询"
                }
                ExpressiveListRow(
                    title = "校园卡余额",
                    subtitle = cardSubtitle,
                    leadingIcon = Icons.Rounded.AccountBalanceWallet,
                    position = GroupedCardPosition.MIDDLE,
                    trailingContent = {
                        if (snap != null) {
                            ExpressiveStatusBadge(
                                text = "¥${snap.totalBalanceText}",
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = onOpenCardBalance,
                )
            }

            item {
                val powerReading = powerState.lastReading
                val powerHint = when {
                    powerReading != null -> "${powerReading.building} ${powerReading.room} · 剩余电量 ${powerReading.kwhText} 度"
                    campus == CampusPreference.GUZHENKOU -> "古镇口电费通过官方 HTTPS 页面访问"
                    else -> "点击查询唐岛湾宿舍剩余电量或打开古镇口页面"
                }
                ExpressiveListRow(
                    title = "宿舍电量",
                    subtitle = powerHint,
                    leadingIcon = Icons.Rounded.Bolt,
                    position = GroupedCardPosition.BOTTOM,
                    trailingContent = {
                        if (powerReading != null) {
                            ExpressiveStatusBadge(
                                text = "${powerReading.kwhText} 度",
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = onOpenPower,
                    modifier = Modifier.padding(bottom = 18.dp),
                )
            }

            item {
                ExpressiveSectionHeader(
                    title = "快捷服务与安全基线",
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            item {
                ExpressiveListRow(
                    title = "空闲教室",
                    subtitle = "两校区教学楼与 1–13 节交集空闲实时查询",
                    leadingIcon = Icons.Rounded.MeetingRoom,
                    leadingContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    leadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    position = GroupedCardPosition.TOP,
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = onOpenFreeClassroom,
                )
            }

            item {
                ExpressiveListRow(
                    title = "本地安全与隐私边界",
                    subtitle = "系统云备份已关闭 · 明文仅放行两处精确学校主机",
                    leadingIcon = Icons.Rounded.Shield,
                    leadingContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    leadingContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    position = GroupedCardPosition.BOTTOM,
                    onClick = onOpenSettings,
                )
            }
        }
    }
}
