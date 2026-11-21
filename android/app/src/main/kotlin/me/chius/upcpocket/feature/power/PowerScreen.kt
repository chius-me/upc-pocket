package me.chius.upcpocket.feature.power

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.chius.upcpocket.core.designsystem.components.ExpressiveGroupedCard
import me.chius.upcpocket.core.designsystem.components.ExpressiveHeroCard
import me.chius.upcpocket.core.designsystem.components.ExpressiveListRow
import me.chius.upcpocket.core.designsystem.components.ExpressiveSectionHeader
import me.chius.upcpocket.core.designsystem.theme.GroupedCardPosition
import me.chius.upcpocket.core.model.CampusPreference

private enum class PowerCampusTab(val label: String) {
    TANGDAOWAN("唐岛湾"),
    GUZHENKOU("古镇口"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerScreen(
    preferredCampus: CampusPreference,
    uiState: PowerUiState,
    onBack: () -> Unit,
    onLoadBuildings: () -> Unit,
    onQuery: (building: String, room: String) -> Unit,
    onBindDormitory: (building: String, room: String) -> Unit,
    onClearBoundDormitory: () -> Unit,
    onClearSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var selectedCampus by remember(preferredCampus) {
        mutableStateOf(
            if (preferredCampus == CampusPreference.GUZHENKOU) {
                PowerCampusTab.GUZHENKOU
            } else {
                PowerCampusTab.TANGDAOWAN
            },
        )
    }

    var buildingInput by remember { mutableStateOf(uiState.building) }
    var roomInput by remember { mutableStateOf(uiState.room) }
    var buildingsExpanded by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.building, uiState.room) {
        if (buildingInput.isEmpty() && uiState.building.isNotEmpty()) {
            buildingInput = uiState.building
        }
        if (roomInput.isEmpty() && uiState.room.isNotEmpty()) {
            roomInput = uiState.room
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除电量记录？") },
            text = { Text("将清除本机保存的唐岛湾楼号、房间号与上次电量读数。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        buildingInput = ""
                        roomInput = ""
                        onClearSaved()
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

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            MediumTopAppBar(
                title = { Text("宿舍电量") },
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PowerCampusTab.entries.forEach { tab ->
                        FilterChip(
                            selected = selectedCampus == tab,
                            onClick = { selectedCampus = tab },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }

            if (selectedCampus == PowerCampusTab.GUZHENKOU) {
                item {
                    ExpressiveHeroCard(
                        title = "古镇口电费",
                        subtitle = "该入口由学校外部 HTTPS 页面提供；应用不会绕过或忽略证书校验。",
                        icon = Icons.Rounded.Public,
                        badgeText = "外部页面",
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
                item {
                    ExpressiveListRow(
                        title = "在系统浏览器打开古镇口电费页",
                        subtitle = "https://gzkdf.upc.edu.cn/acmswx/index.aspx",
                        leadingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                        position = GroupedCardPosition.SINGLE,
                        onClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://gzkdf.upc.edu.cn/acmswx/index.aspx"),
                            )
                            runCatching { context.startActivity(intent) }.onFailure {
                                Toast.makeText(context, "未找到可用的系统浏览器", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            } else {
                item {
                    val reading = uiState.lastReading
                    val title = if (reading != null) {
                        "剩余电量 ${reading.kwhText} 度"
                    } else {
                        "唐岛湾宿舍剩余电量"
                    }
                    val subtitle = if (reading != null) {
                        "${reading.building} · ${reading.room} · 更新于 ${formatTime(reading.fetchedAtEpochMillis)}"
                    } else if (uiState.hasBoundDormitory) {
                        "已绑定常用宿舍：${uiState.boundBuilding} · ${uiState.boundRoom}"
                    } else {
                        "选择楼号与房间号查询电量，可绑定到今日首页自动同步。"
                    }
                    val badge = when {
                        uiState.isQuerying -> "查询中"
                        uiState.hasBoundDormitory -> "已绑定"
                        reading != null -> "已保存"
                        else -> null
                    }
                    ExpressiveHeroCard(
                        title = title,
                        subtitle = subtitle,
                        icon = Icons.Rounded.Bolt,
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
                    ExpressiveGroupedCard(
                        position = GroupedCardPosition.SINGLE,
                        modifier = Modifier.padding(bottom = 18.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = buildingInput,
                                    onValueChange = { buildingInput = it },
                                    label = { Text("楼号") },
                                    placeholder = { Text("例如 1号楼 / 加载列表") },
                                    singleLine = true,
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Apartment, contentDescription = null)
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = {
                                        if (uiState.buildings.isEmpty()) {
                                            onLoadBuildings()
                                        } else {
                                            buildingsExpanded = true
                                        }
                                    },
                                    enabled = !uiState.isLoadingBuildings,
                                ) {
                                    if (uiState.isLoadingBuildings) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Text(if (uiState.buildings.isEmpty()) "加载楼号" else "选择(${uiState.buildings.size})")
                                    }
                                }
                                DropdownMenu(
                                    expanded = buildingsExpanded,
                                    onDismissRequest = { buildingsExpanded = false },
                                ) {
                                    uiState.buildings.forEach { building ->
                                        DropdownMenuItem(
                                            text = { Text(building) },
                                            onClick = {
                                                buildingInput = building
                                                buildingsExpanded = false
                                            },
                                        )
                                    }
                                }
                            }

                            if (uiState.buildings.isNotEmpty()) {
                                TextButton(
                                    onClick = onLoadBuildings,
                                    enabled = !uiState.isLoadingBuildings,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 4.dp),
                                    )
                                    Text("重新拉取楼号列表")
                                }
                            }

                            OutlinedTextField(
                                value = roomInput,
                                onValueChange = { roomInput = it },
                                label = { Text("房间号") },
                                placeholder = { Text("输入完整房间号") },
                                singleLine = true,
                                leadingIcon = {
                                    Icon(Icons.Rounded.MeetingRoom, contentDescription = null)
                                },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = { onQuery(buildingInput, roomInput) },
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Button(
                                    onClick = { onQuery(buildingInput, roomInput) },
                                    enabled = !uiState.isQuerying &&
                                        buildingInput.isNotBlank() &&
                                        roomInput.isNotBlank(),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(if (uiState.isQuerying) "查询中…" else "查询剩余电量")
                                }
                                if (uiState.lastReading != null || uiState.room.isNotBlank()) {
                                    OutlinedButton(
                                        onClick = { showClearDialog = true },
                                        enabled = !uiState.isQuerying,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 4.dp),
                                        )
                                        Text("清除")
                                    }
                                }
                            }

                            val isCurrentlyBound = uiState.hasBoundDormitory &&
                                uiState.boundBuilding == buildingInput.trim() &&
                                uiState.boundRoom == roomInput.trim()
                            if (buildingInput.isNotBlank() && roomInput.isNotBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        if (isCurrentlyBound) {
                                            onClearBoundDormitory()
                                        } else {
                                            onBindDormitory(buildingInput, roomInput)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(if (isCurrentlyBound) "已绑定到今日首页（点击解绑）" else "设为我的常用宿舍（首页自动同步）")
                                }
                            }
                        }
                    }
                }

                if (uiState.candidates.isNotEmpty()) {
                    item {
                        ExpressiveSectionHeader(
                            title = "命中多个候选房间，请选择确切房间",
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    itemsIndexed(uiState.candidates) { index, candidate ->
                        val isLast = index == uiState.candidates.lastIndex
                        ExpressiveListRow(
                            title = "${candidate.building} · ${candidate.room}",
                            subtitle = "系统编号：${candidate.systemNo}",
                            leadingIcon = Icons.Rounded.MeetingRoom,
                            position = GroupedCardPosition.forIndex(index, uiState.candidates.size),
                            onClick = {
                                val targetBuilding = candidate.building.ifBlank { buildingInput }
                                buildingInput = targetBuilding
                                roomInput = candidate.room
                                onQuery(targetBuilding, candidate.room)
                            },
                            modifier = if (isLast) Modifier.padding(bottom = 18.dp) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(epochMillis: Long): String {
    val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    return formatter.format(Date(epochMillis))
}
