package me.chius.upcpocket.feature.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.chius.upcpocket.BuildConfig
import me.chius.upcpocket.core.data.AppPreferencesState
import me.chius.upcpocket.core.designsystem.components.ExpressiveGroupedCard
import me.chius.upcpocket.core.designsystem.components.ExpressiveListRow
import me.chius.upcpocket.core.designsystem.components.ExpressiveSectionHeader
import me.chius.upcpocket.core.designsystem.components.ExpressiveStatusBadge
import me.chius.upcpocket.core.designsystem.theme.GroupedCardPosition
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.CampusPreference
import me.chius.upcpocket.core.model.ColorSeed
import me.chius.upcpocket.core.model.SchoolAccountState
import me.chius.upcpocket.core.model.ThemePreference
import me.chius.upcpocket.core.update.AppUpdateState
import me.chius.upcpocket.feature.power.PowerUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferences: AppPreferencesState,
    accountState: SchoolAccountState,
    powerState: PowerUiState,
    appUpdateState: AppUpdateState,
    onBack: () -> Unit,
    onOpenSchoolAccount: () -> Unit,
    onOpenPower: () -> Unit,
    onOpenAbout: () -> Unit,
    onSelectCampus: (CampusPreference) -> Unit,
    onSelectTheme: (ThemePreference) -> Unit,
    onSelectColorSeed: (ColorSeed) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    onCheckForUpdates: () -> Unit,
    onDismissUpdate: () -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    if (appUpdateState is AppUpdateState.UpdateAvailable) {
        AlertDialog(
            onDismissRequest = onDismissUpdate,
            title = { Text("发现新版本 v${appUpdateState.versionName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "当前版本：v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = appUpdateState.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = appUpdateState.apkDownloadUrl ?: appUpdateState.releasePageUrl
                        onDismissUpdate()
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                        }.onFailure {
                            Toast.makeText(context, "未找到可用浏览器或下载器", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) {
                    Text(if (appUpdateState.apkDownloadUrl != null) "下载安装包" else "前往 Release")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissUpdate) { Text("稍后再说") }
            },
        )
    }

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
                        "${accountState.maskedLoginName ?: "已保存"} · 密文代次 #${accountState.generation}"
                    AccountStatus.RECOVERY_REQUIRED -> "密钥或密文异常，请重新保存"
                }
                ExpressiveListRow(
                    title = "学校账号",
                    subtitle = accountSubtitle,
                    leadingIcon = Icons.Rounded.Person,
                    position = GroupedCardPosition.TOP,
                    trailingContent = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
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
                ExpressiveListRow(
                    title = "常用宿舍（电量主页追踪）",
                    subtitle = if (powerState.hasBoundDormitory) {
                        "已绑定 ${powerState.boundBuilding} · ${powerState.boundRoom}"
                    } else {
                        "尚未绑定，点击前往宿舍电量页绑定"
                    },
                    leadingIcon = Icons.Rounded.Bolt,
                    position = GroupedCardPosition.MIDDLE,
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = onOpenPower,
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
                                    text = "校园与选座/空教室查询优先定位所选校区",
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
                    title = "外观与色彩风格",
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            item {
                ExpressiveGroupedCard(position = GroupedCardPosition.TOP) {
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
                            Text(text = "主题模式", style = MaterialTheme.typography.titleMedium)
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
                ExpressiveGroupedCard(position = GroupedCardPosition.MIDDLE) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.Palette,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column {
                                Text(text = "品牌色彩方案", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = if (preferences.dynamicColorEnabled) {
                                        "当前已开启动态取色，关闭下方开关即可使用所选方案"
                                    } else {
                                        preferences.colorSeed.displayName
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ColorSeed.entries.forEach { seed ->
                                val selected = preferences.colorSeed == seed
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(Color(seed.swatchArgb))
                                        .then(
                                            if (selected) {
                                                Modifier.border(
                                                    width = 3.dp,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    shape = CircleShape,
                                                )
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .clickable { onSelectColorSeed(seed) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = seed.displayName,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                ExpressiveListRow(
                    title = "动态取色（Material You）",
                    subtitle = "Android 12+ 自动提取壁纸色彩",
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
                    title = "应用与关于",
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            item {
                val updateSubtitle = when (appUpdateState) {
                    is AppUpdateState.Idle -> "当前版本 v${BuildConfig.VERSION_NAME} · 点击检查 GitHub Releases"
                    is AppUpdateState.Checking -> "正在检查最新版本…"
                    is AppUpdateState.UpToDate -> "已是最新版本 v${appUpdateState.currentVersion}"
                    is AppUpdateState.UpdateAvailable -> "发现新版本 v${appUpdateState.versionName}"
                    is AppUpdateState.Error -> appUpdateState.message
                }
                ExpressiveListRow(
                    title = "检查应用更新",
                    subtitle = updateSubtitle,
                    leadingIcon = Icons.Rounded.SystemUpdate,
                    position = GroupedCardPosition.TOP,
                    trailingContent = {
                        if (appUpdateState is AppUpdateState.Checking) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    },
                    onClick = onCheckForUpdates,
                )
            }

            item {
                ExpressiveListRow(
                    title = "关于 UPC Pocket",
                    subtitle = "隐私安全说明与开源致谢",
                    leadingIcon = Icons.Rounded.Info,
                    position = GroupedCardPosition.BOTTOM,
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
