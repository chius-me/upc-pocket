package me.chius.upcpocket.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.EventSeat
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    campus: CampusPreference,
    accountState: SchoolAccountState,
    bottomNavPadding: Dp,
    onOpenSettings: () -> Unit,
    onOpenSchoolAccount: () -> Unit,
    onOpenTimetableTab: () -> Unit,
    onOpenServicesTab: () -> Unit,
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
                        text = "M0 纯原生骨架",
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
                    subtitle = "核心只读服务将在 M1 阶段逐项接入",
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            item {
                ExpressiveListRow(
                    title = "今日课表",
                    subtitle = if (accountState.status == AccountStatus.SAVED_UNVERIFIED) {
                        "将在 M1 接入正常 CAS 登录与离线周缓存"
                    } else {
                        "配置学校账号后可同步周日–周六课表"
                    },
                    leadingIcon = Icons.Rounded.CalendarToday,
                    position = GroupedCardPosition.TOP,
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = onOpenTimetableTab,
                )
            }

            item {
                ExpressiveListRow(
                    title = "校园卡余额",
                    subtitle = when {
                        accountState.status == AccountStatus.SAVED_UNVERIFIED && accountState.isValidStudentId ->
                            "已识别有效本人学号，M1 只读查询仅发送学号"
                        accountState.status == AccountStatus.SAVED_UNVERIFIED ->
                            "当前登录名不符合学号规则，将跳过余额查询"
                        else -> "未配置账号；仅接受本人有效学号查询"
                    },
                    leadingIcon = Icons.Rounded.AccountBalanceWallet,
                    position = GroupedCardPosition.MIDDLE,
                    trailingContent = {
                        ExpressiveStatusBadge(
                            text = "M1",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = onOpenServicesTab,
                )
            }

            item {
                val powerHint = when (campus) {
                    CampusPreference.GUZHENKOU -> "古镇口电费通过官方 HTTPS 页面访问"
                    CampusPreference.TANGDAOWAN -> "唐岛湾电量支持楼号与寝室号原生查询（M1）"
                    CampusPreference.ALL -> "唐岛湾支持原生查询，古镇口支持官方页面"
                }
                ExpressiveListRow(
                    title = "宿舍电量",
                    subtitle = powerHint,
                    leadingIcon = Icons.Rounded.Bolt,
                    position = GroupedCardPosition.BOTTOM,
                    onClick = onOpenServicesTab,
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
                    title = "空闲教室与图书馆座位",
                    subtitle = "空闲教室（M1）· 图书馆三入口只读（M2）",
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
                    onClick = onOpenServicesTab,
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
