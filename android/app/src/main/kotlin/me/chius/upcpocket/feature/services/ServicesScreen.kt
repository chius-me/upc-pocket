package me.chius.upcpocket.feature.services

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.EventSeat
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.chius.upcpocket.core.designsystem.components.ExpressiveListRow
import me.chius.upcpocket.core.designsystem.components.ExpressiveSectionHeader
import me.chius.upcpocket.core.designsystem.components.ExpressiveStatusBadge
import me.chius.upcpocket.core.designsystem.theme.GroupedCardPosition
import me.chius.upcpocket.core.model.CampusPreference
import me.chius.upcpocket.core.model.CampusServiceCatalog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesScreen(
    campus: CampusPreference,
    bottomNavPadding: Dp,
    onSelectCampus: (CampusPreference) -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val openExternalUrl: (String) -> Unit = { url ->
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching {
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(context, "未找到可用的系统浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    val filteredQueries = CampusServiceCatalog.queryServices.filter { entry ->
        campus == CampusPreference.ALL ||
            entry.campus == CampusPreference.ALL ||
            entry.campus == campus
    }
    val officialWeb = CampusServiceCatalog.officialWebEntries
    val deferredEntries = CampusServiceCatalog.deferredReadOnlyEntries

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.systemBars.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
        ),
        topBar = {
            LargeTopAppBar(
                title = { Text("服务") },
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
                ExpressiveSectionHeader(
                    title = "校园基础查询",
                    subtitle = "按常用校区优先显示，核心只读协议自 M1 起陆续上线",
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            itemsIndexed(
                items = filteredQueries,
                key = { _, item -> item.id },
            ) { index, item ->
                val isLast = index == filteredQueries.lastIndex
                ExpressiveListRow(
                    title = item.title,
                    subtitle = item.subtitle,
                    leadingIcon = serviceIconForId(item.id),
                    position = GroupedCardPosition.forIndex(index, filteredQueries.size),
                    trailingContent = {
                        if (item.externalUrl != null) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = "外部打开",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            ExpressiveStatusBadge(
                                text = item.milestone,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = item.externalUrl?.let { url -> { openExternalUrl(url) } },
                    modifier = if (isLast) Modifier.padding(bottom = 18.dp) else Modifier,
                )
            }

            item {
                ExpressiveSectionHeader(
                    title = "校园官方网页",
                    subtitle = "通过系统浏览器打开学校 HTTPS 官方入口",
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            itemsIndexed(
                items = officialWeb,
                key = { _, item -> item.id },
            ) { index, item ->
                val isLast = index == officialWeb.lastIndex
                ExpressiveListRow(
                    title = item.title,
                    subtitle = item.subtitle,
                    leadingIcon = Icons.Rounded.Public,
                    leadingContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    leadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    position = GroupedCardPosition.forIndex(index, officialWeb.size),
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = "在浏览器打开",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = { openExternalUrl(item.url) },
                    modifier = if (isLast) Modifier.padding(bottom = 18.dp) else Modifier,
                )
            }

            item {
                ExpressiveSectionHeader(
                    title = "后续只读规划",
                    subtitle = "高风险写操作（如校园卡充值）当前明确后置",
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            itemsIndexed(
                items = deferredEntries,
                key = { _, item -> item.id },
            ) { index, item ->
                ExpressiveListRow(
                    title = item.title,
                    subtitle = item.subtitle,
                    leadingIcon = if (item.id == "shiguang") Icons.Rounded.School else Icons.Rounded.AccountBalance,
                    leadingContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    leadingContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    position = GroupedCardPosition.forIndex(index, deferredEntries.size),
                    trailingContent = {
                        ExpressiveStatusBadge(
                            text = item.milestone,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
    }
}

private fun serviceIconForId(id: String): ImageVector = when (id) {
    "free_classroom" -> Icons.Rounded.MeetingRoom
    "electricity_tangdaowan", "electricity_guzhenkou" -> Icons.Rounded.Bolt
    "campus_card_balance" -> Icons.Rounded.AccountBalanceWallet
    "library_seat" -> Icons.Rounded.EventSeat
    else -> Icons.Rounded.Public
}
