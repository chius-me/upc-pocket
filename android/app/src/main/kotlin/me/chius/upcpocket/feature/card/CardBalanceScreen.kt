package me.chius.upcpocket.feature.card

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Http
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.chius.upcpocket.core.designsystem.components.ExpressiveGroupedCard
import me.chius.upcpocket.core.designsystem.components.ExpressiveHeroCard
import me.chius.upcpocket.core.designsystem.components.ExpressiveListRow
import me.chius.upcpocket.core.designsystem.components.ExpressiveSectionHeader
import me.chius.upcpocket.core.designsystem.theme.GroupedCardPosition
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.SchoolAccountState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardBalanceScreen(
    accountState: SchoolAccountState,
    uiState: CardBalanceUiState,
    onBack: () -> Unit,
    onOpenSchoolAccount: () -> Unit,
    onRefresh: () -> Unit,
    onClearCache: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("仅清除余额缓存？") },
            text = { Text("将删除本机保存的校园卡余额缓存，不会删除学校账号或加密凭据。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClearCache()
                    },
                ) {
                    Text("确认清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    val canQuery = accountState.status == AccountStatus.SAVED_UNVERIFIED && accountState.isValidStudentId

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            MediumTopAppBar(
                title = { Text("校园卡余额") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                val snapshot = uiState.snapshot
                val heroTitle = if (snapshot != null) {
                    "¥${snapshot.totalBalanceText}"
                } else {
                    "暂无余额记录"
                }
                val heroSubtitle = when {
                    snapshot != null && uiState.isHistoricalGeneration ->
                        "当前展示同账号历史缓存（已修改过密码，建议重新刷新）"
                    snapshot != null ->
                        "总额 = 卡内余额 ¥${snapshot.balanceText} + 过渡余额 ¥${snapshot.transitionText}"
                    canQuery ->
                        "点击下方查询，仅向学校接口发送当前绑定的本人学号"
                    else ->
                        "请先配置整串符合学号格式的学校账号（仅查询本人校园卡）"
                }
                val badge = when {
                    uiState.isRefreshing -> "查询中"
                    snapshot != null && uiState.isHistoricalGeneration -> "历史缓存"
                    snapshot != null -> "本机缓存"
                    canQuery -> "可查询"
                    else -> accountState.status.label
                }

                ExpressiveHeroCard(
                    title = heroTitle,
                    subtitle = heroSubtitle,
                    icon = Icons.Rounded.AccountBalanceWallet,
                    badgeText = badge,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            if (!uiState.errorMessage.isNullOrBlank()) {
                item {
                    ExpressiveGroupedCard(
                        position = GroupedCardPosition.SINGLE,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.padding(bottom = 14.dp),
                    ) {
                        Text(
                            text = uiState.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (canQuery) {
                        Button(
                            onClick = onRefresh,
                            enabled = !uiState.isRefreshing,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (uiState.isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            }
                            Text(if (uiState.isRefreshing) "正在查询…" else "查询当前余额")
                        }
                    } else {
                        Button(
                            onClick = onOpenSchoolAccount,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            Text("前往配置学校账号")
                        }
                    }

                    if (uiState.snapshot != null) {
                        OutlinedButton(
                            onClick = { showClearDialog = true },
                            enabled = !uiState.isRefreshing,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                            Text("清缓存")
                        }
                    }
                }
            }

            uiState.snapshot?.let { snap ->
                item {
                    ExpressiveSectionHeader(
                        title = "余额明细",
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }

                item {
                    ExpressiveGroupedCard(
                        position = GroupedCardPosition.SINGLE,
                        modifier = Modifier.padding(bottom = 18.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            DetailRow("卡内余额", "¥${snap.balanceText}")
                            DetailRow("过渡余额", "¥${snap.transitionText}")
                            DetailRow("合计总额", "¥${snap.totalBalanceText}")
                            DetailRow("查询时间", formatTimestamp(snap.fetchedAtEpochMillis))
                        }
                    }
                }
            }

            item {
                ExpressiveSectionHeader(
                    title = "安全与传输说明",
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            item {
                ExpressiveListRow(
                    title = "仅发送本人学号（不含密码）",
                    subtitle = "当前绑定账号：${accountState.maskedLoginName ?: "未配置"}；只读余额不触发 CAS 认证。",
                    leadingIcon = Icons.Rounded.Person,
                    position = GroupedCardPosition.TOP,
                )
            }

            item {
                ExpressiveListRow(
                    title = "明文 HTTP 最小例外",
                    subtitle = "学校接口 jhzf.upc.edu.cn:20081 仅提供 HTTP；建议在可信校园网环境下查询。",
                    leadingIcon = Icons.Rounded.Http,
                    leadingContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    leadingContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    position = GroupedCardPosition.BOTTOM,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
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
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatTimestamp(epochMillis: Long): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    return formatter.format(Date(epochMillis))
}
