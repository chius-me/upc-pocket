package me.chius.upcpocket.feature.focus

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import me.chius.upcpocket.core.designsystem.components.ExpressiveGroupedCard
import me.chius.upcpocket.core.designsystem.components.ExpressiveListRow
import me.chius.upcpocket.core.designsystem.components.ExpressiveSectionHeader
import me.chius.upcpocket.core.designsystem.components.ExpressiveStatusBadge
import me.chius.upcpocket.core.designsystem.theme.GroupedCardPosition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(
    uiState: FocusTimerUiState,
    bottomNavPadding: Dp,
    onSelectMode: (FocusMode) -> Unit,
    onToggleStartPause: () -> Unit,
    onReset: () -> Unit,
    onFinishEarly: () -> Unit,
    onClearHistory: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val animatedProgress by animateFloatAsState(
        targetValue = uiState.progress,
        label = "focus_progress",
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.systemBars.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
        ),
        topBar = {
            LargeTopAppBar(
                title = { Text("专注") },
                actions = {
                    if (uiState.recentRecords.isNotEmpty()) {
                        IconButton(onClick = onClearHistory) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "清空专注历史",
                            )
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FocusMode.entries.forEach { mode ->
                        FilterChip(
                            selected = uiState.mode == mode,
                            onClick = { onSelectMode(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }
            }

            item {
                ExpressiveGroupedCard(
                    position = GroupedCardPosition.TOP,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp, horizontal = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(216.dp),
                        ) {
                            CircularProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier.fillMaxSize(),
                                strokeWidth = 10.dp,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                strokeCap = StrokeCap.Round,
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = uiState.formattedTime,
                                    style = MaterialTheme.typography.displaySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                val badgeText = when {
                                    uiState.isRunning -> "进行中"
                                    uiState.isPaused -> "已暂停"
                                    else -> "已就绪"
                                }
                                ExpressiveStatusBadge(
                                    text = badgeText,
                                    containerColor = if (uiState.isRunning) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                    contentColor = if (uiState.isRunning) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(22.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = onToggleStartPause,
                                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
                            ) {
                                Icon(
                                    imageVector = if (uiState.isRunning) {
                                        Icons.Rounded.Pause
                                    } else {
                                        Icons.Rounded.PlayArrow
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                                Text(
                                    when {
                                        uiState.isRunning -> "暂停"
                                        uiState.isPaused -> "继续"
                                        else -> "开始专注"
                                    },
                                )
                            }

                            FilledTonalButton(
                                onClick = onReset,
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                                Text("重置")
                            }
                        }

                        if (!uiState.isIdle && uiState.mode.isFocus) {
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = onFinishEarly) {
                                Icon(
                                    imageVector = Icons.Rounded.StopCircle,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                                Text("提前结束并记录已学时长")
                            }
                        }
                    }
                }
            }

            item {
                ExpressiveListRow(
                    title = "今日已专注 ${FocusStats.humanDuration(uiState.summary.today.seconds)}",
                    subtitle = "已完成 ${uiState.summary.today.sessions} 轮完整番茄 · 墙钟状态已持久保存（凌晨 04:00 分界）",
                    leadingIcon = Icons.Rounded.Timer,
                    leadingContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    leadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    position = GroupedCardPosition.BOTTOM,
                    modifier = Modifier.padding(bottom = 18.dp),
                )
            }

            item {
                ExpressiveSectionHeader(
                    title = "本机专注统计",
                    subtitle = "专注时长累计所有学习段；完成轮数仅计完整番茄（不含短休）",
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
                        StatRow("今日（04:00 起）", uiState.summary.today)
                        StatRow("本周（周一至今）", uiState.summary.week)
                        StatRow("本月", uiState.summary.month)
                        StatRow("累计", uiState.summary.allTime)
                    }
                }
            }

            if (uiState.recentRecords.isNotEmpty()) {
                item {
                    ExpressiveSectionHeader(
                        title = "最近记录（${uiState.recentRecords.size}）",
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }

                itemsIndexed(
                    items = uiState.recentRecords,
                    key = { _, rec -> rec.id },
                ) { index, rec ->
                    val statusTag = if (rec.completed) "完整完成" else "提前结束"
                    val timeLabel = Instant.ofEpochMilli(rec.startedAt)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                    ExpressiveListRow(
                        title = "${rec.mode.label} · ${FocusStats.humanDuration(rec.actualSeconds)}",
                        subtitle = "$timeLabel · $statusTag",
                        leadingIcon = Icons.Rounded.CheckCircleOutline,
                        leadingContainerColor = if (rec.completed) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        leadingContentColor = if (rec.completed) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        position = GroupedCardPosition.forIndex(index, uiState.recentRecords.size),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, totals: FocusTotals) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "${FocusStats.humanDuration(totals.seconds)} · ${totals.sessions} 轮",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
