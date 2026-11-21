package me.chius.upcpocket.feature.timetable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.chius.upcpocket.core.designsystem.components.ExpressiveGroupedCard
import me.chius.upcpocket.core.designsystem.components.ExpressiveHeroCard
import me.chius.upcpocket.core.designsystem.components.ExpressiveListRow
import me.chius.upcpocket.core.designsystem.components.ExpressiveSectionHeader
import me.chius.upcpocket.core.designsystem.components.ExpressiveStatusBadge
import me.chius.upcpocket.core.designsystem.theme.GroupedCardPosition
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.SchoolAccountState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    accountState: SchoolAccountState,
    bottomNavPadding: Dp,
    onOpenSchoolAccount: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.systemBars.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
        ),
        topBar = {
            LargeTopAppBar(
                title = { Text("课表") },
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
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            item {
                val (badge, subtitle) = when (accountState.status) {
                    AccountStatus.UNSET -> "未保存账号" to "请先配置学校账号。M1 阶段接入正常 CAS 登录与受控 WebView 验证码回退。"
                    AccountStatus.SAVED_UNVERIFIED -> "等待同步" to "已保存本机加密凭据（未验证），M1 阶段接入后可拉取当前教学周并离线缓存。"
                    AccountStatus.RECOVERY_REQUIRED -> "需要重新保存" to "凭据状态需要重新保存，暂不可用于同步学校课表。"
                }
                ExpressiveHeroCard(
                    title = "只读周课表骨架",
                    subtitle = subtitle,
                    icon = Icons.Rounded.CalendarMonth,
                    badgeText = badge,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            item {
                ExpressiveGroupedCard(
                    position = GroupedCardPosition.TOP,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "作息节次规范",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("上午 1–5 节", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "08:00 – 12:15",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("下午 6–9 节", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "14:00 – 17:45",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("晚间 10–12 节", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "19:00 – 21:35",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
