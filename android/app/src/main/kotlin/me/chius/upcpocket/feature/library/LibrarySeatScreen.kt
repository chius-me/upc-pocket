package me.chius.upcpocket.feature.library

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.EventSeat
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.chius.upcpocket.core.designsystem.components.ExpressiveGroupedCard
import me.chius.upcpocket.core.designsystem.components.ExpressiveListRow
import me.chius.upcpocket.core.designsystem.components.ExpressiveSectionHeader
import me.chius.upcpocket.core.designsystem.components.ExpressiveStatusBadge
import me.chius.upcpocket.core.designsystem.theme.GroupedCardPosition
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.CampusPreference
import me.chius.upcpocket.core.model.SchoolAccountState
import me.chius.upcpocket.core.webview.ControlledSchoolWebViewDialog

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibrarySeatScreen(
    preferredCampus: CampusPreference,
    accountState: SchoolAccountState,
    uiState: LibrarySeatUiState,
    onBack: () -> Unit,
    onOpenSchoolAccount: () -> Unit,
    onInitialize: (CampusPreference) -> Unit,
    onSelectSystem: (LibrarySystemTab) -> Unit,
    onRefresh: () -> Unit,
    onSelectLibseatDate: (String) -> Unit,
    onSelectLibseatBuilding: (LibraryBuilding) -> Unit,
    onSelectLibseatFloor: (LibraryFloor?) -> Unit,
    onOpenLibseatRoom: (LibraryRoom) -> Unit,
    onDismissLibseatRoom: () -> Unit,
    onSelectChaoxingDate: (String) -> Unit,
    onOpenChaoxingRoom: (ChaoxingSeatRoom) -> Unit,
    onUpdateChaoxingWindow: (Int, Int) -> Unit,
    onSelectChaoxingSeat: (String) -> Unit,
    onDismissChaoxingRoom: () -> Unit,
    onAdoptLibseatUrl: (String, me.chius.upcpocket.core.webview.ControlledWebViewContract.SessionContext) -> Unit,
    onAdoptChaoxingCookies: (Map<String, String>, me.chius.upcpocket.core.webview.ControlledWebViewContract.SessionContext) -> Unit,
    onDismissInteraction: () -> Unit,
    onClearSessions: () -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(preferredCampus) {
        onInitialize(preferredCampus)
    }

    val openExternalUrl: (String) -> Unit = { url ->
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching {
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(context, "未找到可用的系统浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            MediumTopAppBar(
                title = { Text("图书馆座位") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (accountState.status == AccountStatus.SAVED_UNVERIFIED) {
                        IconButton(
                            onClick = onRefresh,
                            enabled = !uiState.isLoading,
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "刷新座位数据")
                        }
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val tabs = LibrarySystemTab.entries
                    tabs.forEachIndexed { index, tab ->
                        SegmentedButton(
                            selected = uiState.activeTab == tab,
                            onClick = { onSelectSystem(tab) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                            label = {
                                Text(
                                    text = when (tab) {
                                        LibrarySystemTab.LIBSEAT -> "唐岛湾"
                                        LibrarySystemTab.CHAOXING_SEAT -> "古镇口"
                                        LibrarySystemTab.CHAOXING_SEATENGINE -> "求是"
                                    },
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }

            item {
                ExpressiveGroupedCard(
                    position = GroupedCardPosition.SINGLE,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VerifiedUser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "严格只读查询 · 会话仅存内存",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                text = "支持查看余座、座位分布与预约记录；如需预约/签到请打开官方页面。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        TextButton(onClick = { openExternalUrl(uiState.activeTab.officialUrl) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                            Text("官方页")
                        }
                    }
                }
            }

            if (accountState.status != AccountStatus.SAVED_UNVERIFIED) {
                item {
                    ExpressiveGroupedCard(
                        position = GroupedCardPosition.SINGLE,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Rounded.Lock, contentDescription = null)
                                Text(
                                    text = if (accountState.status == AccountStatus.RECOVERY_REQUIRED) {
                                        "学校账号需要重新保存"
                                    } else {
                                        "尚未配置学校账号"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Text(
                                text = "图书馆与超星座位系统需要通过统一认证查询实时余座与本人预约状态。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = onOpenSchoolAccount) {
                                Text("前往配置学校账号")
                            }
                        }
                    }
                }
            } else {
                if (uiState.isLoading) {
                    item {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                uiState.errorMessage?.let { msg ->
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(text = msg, style = MaterialTheme.typography.bodyMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = onRefresh) {
                                        Text("重试")
                                    }
                                    if (uiState.connectionStatus == LibraryConnectionStatus.INVALID_CREDENTIALS) {
                                        OutlinedButton(onClick = onOpenSchoolAccount) {
                                            Text("核对账号")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                uiState.statusMessage?.let { info ->
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = info,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (uiState.connectionStatus == LibraryConnectionStatus.READY) {
                                TextButton(onClick = onClearSessions) {
                                    Text("清空内存会话")
                                }
                            }
                        }
                    }
                }

                when (uiState.activeTab) {
                    LibrarySystemTab.LIBSEAT -> {
                        if (uiState.libseatDates.isEmpty() && !uiState.isLoading && uiState.errorMessage == null) {
                            item {
                                ExpressiveGroupedCard(position = GroupedCardPosition.SINGLE) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Text("点击同步唐岛湾图书馆座位", style = MaterialTheme.typography.titleMedium)
                                        Button(onClick = onRefresh) {
                                            Text("同步唐岛湾 libseat")
                                        }
                                    }
                                }
                            }
                        } else if (uiState.libseatDates.isNotEmpty()) {
                            item {
                                ExpressiveSectionHeader(title = "日期与馆区筛选")
                            }
                            item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(uiState.libseatDates) { date ->
                                        FilterChip(
                                            selected = uiState.libseatSelectedDate == date,
                                            onClick = { onSelectLibseatDate(date) },
                                            label = { Text(date) },
                                        )
                                    }
                                }
                            }
                            item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(uiState.libseatBuildings, key = { it.id }) { building ->
                                        FilterChip(
                                            selected = uiState.libseatSelectedBuilding?.id == building.id,
                                            onClick = { onSelectLibseatBuilding(building) },
                                            label = { Text(building.name) },
                                        )
                                    }
                                }
                            }
                            if (uiState.libseatFloors.isNotEmpty()) {
                                item {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        item {
                                            FilterChip(
                                                selected = uiState.libseatSelectedFloor == null,
                                                onClick = { onSelectLibseatFloor(null) },
                                                label = { Text("全部楼层") },
                                            )
                                        }
                                        items(uiState.libseatFloors, key = { it.floorNum }) { floor ->
                                            FilterChip(
                                                selected = uiState.libseatSelectedFloor?.floorNum == floor.floorNum,
                                                onClick = { onSelectLibseatFloor(floor) },
                                                label = { Text(floor.floorName) },
                                            )
                                        }
                                    }
                                }
                            }

                            val visibleRooms = uiState.libseatSelectedFloor?.let { fl ->
                                uiState.libseatRooms.filter { it.floor == fl.floorNum }
                            } ?: uiState.libseatRooms

                            item {
                                ExpressiveSectionHeader(
                                    title = "阅览室余座（共 ${visibleRooms.size} 间）",
                                    subtitle = "点击房间查看本馆座位实时状态分布",
                                )
                            }

                            if (visibleRooms.isEmpty()) {
                                item {
                                    ExpressiveGroupedCard(position = GroupedCardPosition.SINGLE) {
                                        Text(
                                            text = "当前筛选条件下暂无房间数据",
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(16.dp),
                                        )
                                    }
                                }
                            } else {
                                itemsIndexed(
                                    items = visibleRooms,
                                    key = { _, room -> room.roomId },
                                ) { index, room ->
                                    ExpressiveListRow(
                                        title = room.name,
                                        subtitle = buildString {
                                            append(room.floorLabel)
                                            append(" · ")
                                            append(room.remainingLabel)
                                            room.maxDurationLabel?.let { append(" · $it") }
                                        },
                                        leadingIcon = Icons.Rounded.EventSeat,
                                        position = GroupedCardPosition.forIndex(index, visibleRooms.size),
                                        trailingContent = {
                                            ExpressiveStatusBadge(
                                                text = room.freeSeats?.let { "余 $it" } ?: "查看",
                                                containerColor = if ((room.freeSeats ?: 0) > 0) {
                                                    MaterialTheme.colorScheme.primaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                                },
                                                contentColor = if ((room.freeSeats ?: 0) > 0) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                            )
                                        },
                                        onClick = { onOpenLibseatRoom(room) },
                                    )
                                }
                            }

                            item {
                                ExpressiveSectionHeader(
                                    title = "当前预约记录（只读）",
                                    subtitle = "data: null 时如实显示无当前预约",
                                )
                            }

                            if (uiState.libseatCurrentReservations.isEmpty()) {
                                item {
                                    ExpressiveGroupedCard(position = GroupedCardPosition.SINGLE) {
                                        Text(
                                            text = "当前没有有效预约记录",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(16.dp),
                                        )
                                    }
                                }
                            } else {
                                itemsIndexed(
                                    items = uiState.libseatCurrentReservations,
                                    key = { _, item -> item.id },
                                ) { index, reservation ->
                                    ExpressiveListRow(
                                        title = reservation.location,
                                        subtitle = "${reservation.date} · ${reservation.begin}–${reservation.end}",
                                        leadingIcon = Icons.Rounded.BookmarkAdded,
                                        position = GroupedCardPosition.forIndex(
                                            index,
                                            uiState.libseatCurrentReservations.size,
                                        ),
                                        trailingContent = {
                                            ExpressiveStatusBadge(
                                                text = reservation.statusLabel,
                                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                            )
                                        },
                                    )
                                }
                            }

                            if (uiState.libseatHistoryReservations.isNotEmpty()) {
                                item {
                                    ExpressiveSectionHeader(title = "历史预约（最近 ${uiState.libseatHistoryReservations.size} 条）")
                                }
                                itemsIndexed(
                                    items = uiState.libseatHistoryReservations,
                                    key = { _, item -> "hist_${item.id}" },
                                ) { index, reservation ->
                                    ExpressiveListRow(
                                        title = reservation.location,
                                        subtitle = "${reservation.date} · ${reservation.begin}–${reservation.end}",
                                        leadingIcon = Icons.Rounded.History,
                                        leadingContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        leadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        position = GroupedCardPosition.forIndex(
                                            index,
                                            uiState.libseatHistoryReservations.size,
                                        ),
                                        trailingContent = {
                                            ExpressiveStatusBadge(
                                                text = reservation.statusLabel,
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }

                    LibrarySystemTab.CHAOXING_SEAT, LibrarySystemTab.CHAOXING_SEATENGINE -> {
                        if (uiState.chaoxingAvailableDates.isNotEmpty()) {
                            item {
                                ExpressiveSectionHeader(title = "日期选择（北京时间）")
                            }
                            item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(uiState.chaoxingAvailableDates) { date ->
                                        FilterChip(
                                            selected = uiState.chaoxingSelectedDate == date,
                                            onClick = { onSelectChaoxingDate(date) },
                                            label = { Text(date) },
                                        )
                                    }
                                }
                            }
                        }

                        if (uiState.chaoxingRooms.isEmpty() && !uiState.isLoading && uiState.errorMessage == null) {
                            item {
                                ExpressiveGroupedCard(position = GroupedCardPosition.SINGLE) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Text(
                                            text = "点击同步 ${uiState.activeTab.label} 区域",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Button(onClick = onRefresh) {
                                            Text("同步房间列表")
                                        }
                                    }
                                }
                            }
                        } else if (uiState.chaoxingRooms.isNotEmpty()) {
                            item {
                                ExpressiveSectionHeader(
                                    title = "${uiState.activeTab.label} 开放区域（${uiState.chaoxingRooms.size} 个）",
                                    subtitle = "点击区域查看座位分布、时段占用与可用座号",
                                )
                            }
                            itemsIndexed(
                                items = uiState.chaoxingRooms,
                                key = { _, room -> room.id },
                            ) { index, room ->
                                ExpressiveListRow(
                                    title = room.name,
                                    subtitle = buildString {
                                        append("开放 ${room.openHoursLabel}")
                                        append(" · 容量 ${room.capacity} 座")
                                        room.usedCount?.let { append(" · 已用 $it") }
                                    },
                                    leadingIcon = Icons.Rounded.EventSeat,
                                    position = GroupedCardPosition.forIndex(index, uiState.chaoxingRooms.size),
                                    trailingContent = {
                                        ExpressiveStatusBadge(
                                            text = room.remainingEstimate?.let { "余约 $it" } ?: "查看",
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    },
                                    onClick = { onOpenChaoxingRoom(room) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    uiState.libseatSelectedRoom?.let { room ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var onlySelectable by remember { mutableStateOf(false) }

        ModalBottomSheet(
            onDismissRequest = onDismissLibseatRoom,
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "${room.name}（${room.floorLabel}）",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = room.remainingLabel + (room.maxDurationLabel?.let { " · $it" }.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (uiState.isLoadingRoomDetail) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("正在读取座位分布…", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                uiState.libseatSelectedRoomLayout?.let { layout ->
                    val seatItems = layout.seats.filter { it.isSeat }
                    val freeCount = seatItems.count { it.selectable }
                    val displayedSeats = if (onlySelectable) seatItems.filter { it.selectable } else seatItems

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "共 ${seatItems.size} 个座位 · 当前可选 $freeCount 个",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        FilterChip(
                            selected = onlySelectable,
                            onClick = { onlySelectable = !onlySelectable },
                            label = { Text("仅看可选") },
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        displayedSeats.take(60).forEach { seat ->
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = if (seat.selectable) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                                contentColor = if (seat.selectable) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            ) {
                                Text(
                                    text = "${seat.name.ifBlank { seat.id }}·${LibrarySeatStatus.label(seat.status)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    if (displayedSeats.size > 60) {
                        Text(
                            text = "已展示前 60 个座位（共 ${displayedSeats.size} 个）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    uiState.chaoxingSelectedRoom?.let { room ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var onlyAvailable by remember { mutableStateOf(false) }

        ModalBottomSheet(
            onDismissRequest = onDismissChaoxingRoom,
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = room.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "开放 ${room.openHoursLabel} · 查询日期 ${uiState.chaoxingSelectedDate}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (uiState.isLoadingRoomDetail) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("正在读取座位坐标与占用状态…", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                uiState.chaoxingSelectedRoomDetail?.let { detail ->
                    val windows = remember(detail.roomId, detail.startMinute, detail.endMinute) {
                        buildList {
                            var s = detail.startMinute
                            while (s < detail.endMinute) {
                                val e = minOf(s + 240, detail.endMinute)
                                add(s to e)
                                s += 240
                            }
                        }
                    }
                    if (windows.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(windows) { (s, e) ->
                                FilterChip(
                                    selected = uiState.chaoxingSelectedStartMinute == s && uiState.chaoxingSelectedEndMinute == e,
                                    onClick = { onUpdateChaoxingWindow(s, e) },
                                    label = { Text("${formatClockMinute(s)}–${formatClockMinute(e)}") },
                                )
                            }
                        }
                    }

                    val totalSeats = detail.places.size
                    val disabledSet = detail.disabledSeatNums
                    val usedSet = uiState.chaoxingUsedSeatNums
                    val availableSeats = detail.places.filter {
                        it.seatNum !in disabledSet && it.seatNum !in usedSet
                    }
                    val displayedSeats = if (onlyAvailable) availableSeats else detail.places

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "底图共 $totalSeats 座 · 时段空闲 ${availableSeats.size} 座",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        FilterChip(
                            selected = onlyAvailable,
                            onClick = { onlyAvailable = !onlyAvailable },
                            label = { Text("仅看空闲") },
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        displayedSeats.take(60).forEach { place ->
                            val isDisabled = place.seatNum in disabledSet
                            val isUsed = place.seatNum in usedSet
                            val selected = uiState.chaoxingSelectedSeatNum == place.seatNum
                            FilterChip(
                                selected = selected,
                                onClick = { onSelectChaoxingSeat(place.seatNum) },
                                label = {
                                    Text(
                                        when {
                                            isDisabled -> "${place.seatNum}·禁用"
                                            isUsed -> "${place.seatNum}·已占"
                                            else -> "${place.seatNum}·空闲"
                                        },
                                    )
                                },
                            )
                        }
                    }

                    uiState.chaoxingSelectedSeatNum?.let { seatNum ->
                        val times = uiState.chaoxingSelectedSeatUsedTimes
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "座位 $seatNum 当日已占时段",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = if (times.isEmpty()) "全天暂未查询到已占时段" else times.joinToString("、") { it.label },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    uiState.interactionRequest?.let { req ->
        ControlledSchoolWebViewDialog(
            sessionContext = req.sessionContext,
            accountState = accountState,
            scopeHosts = req.scopeHosts,
            targetCookieHost = req.targetHost,
            completionPathPrefixes = req.completionPathPrefixes,
            allowCompletionWithoutCookies = req.allowCompletionWithoutCookies,
            onCompletionUrlCaptured = if (req.allowCompletionWithoutCookies) {
                { rawUrl -> onAdoptLibseatUrl(rawUrl, req.sessionContext) }
            } else {
                null
            },
            onCookiesCaptured = { cookies ->
                if (!req.allowCompletionWithoutCookies) {
                    onAdoptChaoxingCookies(cookies, req.sessionContext)
                }
            },
            onDismiss = onDismissInteraction,
        )
    }
}
