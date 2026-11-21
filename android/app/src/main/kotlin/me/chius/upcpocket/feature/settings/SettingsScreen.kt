package me.chius.upcpocket.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Http
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import me.chius.upcpocket.core.data.AppPreferencesState
import me.chius.upcpocket.core.designsystem.components.ExpressiveGroupedCard
import me.chius.upcpocket.core.designsystem.components.ExpressiveListRow
import me.chius.upcpocket.core.designsystem.components.ExpressiveSectionHeader
import me.chius.upcpocket.core.designsystem.components.ExpressiveStatusBadge
import me.chius.upcpocket.core.designsystem.theme.GroupedCardPosition
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.CampusPreference
import me.chius.upcpocket.core.model.SchoolAccountState
import me.chius.upcpocket.core.model.ThemePreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferences: AppPreferencesState,
    accountState: SchoolAccountState,
    onBack: () -> Unit,
    onOpenSchoolAccount: () -> Unit,
    onOpenAbout: () -> Unit,
    onSelectCampus: (CampusPreference) -> Unit,
    onSelectTheme: (ThemePreference) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            MediumTopAppBar(
                title = { Text("设置") },
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
                ExpressiveSectionHeader(
                    title = "账号与校园",
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            item {
                val accountSubtitle = when (accountState.status) {
                    AccountStatus.UNSET -> "点击配置统一学校账号，加密保存在本机"
                    AccountStatus.SAVED_UNVERIFIED ->
                        "${accountState.maskedLoginName ?: "已保存"} · 本机密文代次 #${accountState.generation}"
                    AccountStatus.RECOVERY_REQUIRED -> "密钥或密文异常，请重新保存"
                }
                ExpressiveListRow(
                    title = "学校账号",
                    subtitle = accountSubtitle,
                    leadingIcon = Icons.Rounded.Person,
                    position = GroupedCardPosition.TOP,
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ExpressiveStatusBadge(
                                text = accountState.status.label,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = onOpenSchoolAccount,
                )
            }

            item {
                ExpressiveGroupedCard(
                    position = GroupedCardPosition.BOTTOM,
                    modifier = Modifier.padding(bottom = 18.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column {
                                Text(
                                    text = "常用校区",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = "服务页与校区相关查询优先展示所选校区",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CampusPreference.entries.forEach { option ->
                                FilterChip(
                                    selected = preferences.campus == option,
                                    onClick = { onSelectCampus(option) },
                                    label = { Text(option.displayName) },
                                )
                            }
                        }
                    }
                }
            }

            item {
                ExpressiveSectionHeader(
                    title = "外观与动态主题",
                    modifier = Modifier.padding(bottom = 6.dp),
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
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.DarkMode,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "主题模式",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemePreference.entries.forEach { theme ->
                                FilterChip(
                                    selected = preferences.theme == theme,
                                    onClick = { onSelectTheme(theme) },
                                    label = { Text(theme.displayName) },
                                )
                            }
                        }
                    }
                }
            }

            item {
                ExpressiveListRow(
                    title = "动态取色（Material You）",
                    subtitle = "Android 12+ 自动提取壁纸色彩，关闭则使用石大深海蓝",
                    leadingIcon = Icons.Rounded.ColorLens,
                    position = GroupedCardPosition.BOTTOM,
                    trailingContent = {
                        Switch(
                            checked = preferences.dynamicColorEnabled,
                            onCheckedChange = onToggleDynamicColor,
                        )
                    },
                    onClick = { onToggleDynamicColor(!preferences.dynamicColorEnabled) },
                    modifier = Modifier.padding(bottom = 18.dp),
                )
            }

            item {
                ExpressiveSectionHeader(
                    title = "安全与隐私基线",
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            item {
                ExpressiveListRow(
                    title = "系统云备份与跨机迁移已关闭",
                    subtitle = "allowBackup=false，偏好与账号密文仅保存在 noBackupFilesDir",
                    leadingIcon = Icons.Rounded.Backup,
                    leadingContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    leadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    position = GroupedCardPosition.TOP,
                )
            }

            item {
                ExpressiveListRow(
                    title = "最小化明文网络例外",
                    subtitle = "全局强制 HTTPS；仅放行 jhzf.upc.edu.cn 与 dfcz.upc.edu.cn",
                    leadingIcon = Icons.Rounded.Http,
                    leadingContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    leadingContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    position = GroupedCardPosition.MIDDLE,
                )
            }

            item {
                ExpressiveListRow(
                    title = "唯一受控 WebView 契约",
                    subtitle = "未来仅保留 1 个校方认证网页回退容器，限定精确 HTTPS 域名",
                    leadingIcon = Icons.Rounded.Security,
                    leadingContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    leadingContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    position = GroupedCardPosition.BOTTOM,
                    modifier = Modifier.padding(bottom = 18.dp),
                )
            }

            item {
                ExpressiveListRow(
                    title = "关于 UPC Pocket",
                    subtitle = "版本信息、非官方声明与 MIT 致谢",
                    leadingIcon = Icons.Rounded.Info,
                    position = GroupedCardPosition.SINGLE,
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = onOpenAbout,
                )
            }
        }
    }
}
