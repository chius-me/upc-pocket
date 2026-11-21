package me.chius.upcpocket.feature.classroom

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
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.chius.upcpocket.core.designsystem.components.ExpressiveGroupedCard
import me.chius.upcpocket.core.designsystem.components.ExpressiveSectionHeader
import me.chius.upcpocket.core.designsystem.components.ExpressiveStatusBadge
import me.chius.upcpocket.core.designsystem.theme.GroupedCardPosition
import me.chius.upcpocket.core.model.CampusPreference

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FreeClassroomScreen(
    preferredCampus: CampusPreference,
    repository: FreeClassroomRepository,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    var selectedCampus by remember(preferredCampus) {
        mutableStateOf(FreeClassroomCampus.fromPreferred(preferredCampus))
    }
    var dateOffsetDays by remember { mutableStateOf(0L) }
    var selectedBuilding by remember { mutableStateOf("全部") }
    var selectedPeriods by remember { mutableStateOf(ClassroomPeriodGroup.MORNING.periods) }
    var queryMode by remember { mutableStateOf(ClassroomQueryMode.PRESET) }
    var showCustomPeriods by remember { mutableStateOf(false) }
    var zhuoyueFilter by remember { mutableStateOf(ZhuoyueWingFilter.ALL) }
    var onlyAvailable by remember { mutableStateOf(true) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<FreeClassroomResult?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    val currentQuery = remember(selectedCampus, dateOffsetDays, selectedBuilding, selectedPeriods) {
        FreeClassroomQuery(
            campus = selectedCampus,
            date = LocalDate.now().plusDays(dateOffsetDays),
            building = selectedBuilding,
            periods = selectedPeriods,
        )
    }

    fun executeQuery(targetQuery: FreeClassroomQuery) {
        if (targetQuery.periods.isEmpty()) {
            errorMessage = "请至少选择 1 个节次"
            return
        }
        activeJob?.cancel()
        activeJob = scope.launch {
            isLoading = true
            errorMessage = null
            val outcome = repository.query(targetQuery)
            isLoading = false
            outcome.onSuccess { fetched ->
                result = fetched
            }.onFailure { err ->
                errorMessage = err.message ?: "查询空闲教室失败，请稍后重试"
            }
        }
    }

    LaunchedEffect(selectedCampus, dateOffsetDays, selectedBuilding) {
        if (queryMode == ClassroomQueryMode.PRESET && selectedPeriods.isNotEmpty()) {
            executeQuery(currentQuery)
        }
    }

    val buildingChoices = result?.takeIf { it.query.campus == selectedCampus }?.buildings
        ?: listOf("全部")

    val displayedResult = result?.takeIf { it.query == currentQuery }
        ?.filterZhuoyueWing(zhuoyueFilter)
    val groups = displayedResult?.displayGroups(onlyAvailable).orEmpty()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            MediumTopAppBar(
                title = { Text("空闲教室") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { executeQuery(currentQuery) },
                        enabled = !isLoading && selectedPeriods.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "刷新",
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
                ExpressiveGroupedCard(
                    position = GroupedCardPosition.SINGLE,
                    modifier = Modifier.padding(bottom = 14.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FreeClassroomCampus.entries.forEach { campus ->
                                FilterChip(
                                    selected = selectedCampus == campus,
                                    onClick = {
                                        if (selectedCampus != campus) {
                                            selectedCampus = campus
                                            selectedBuilding = "全部"
                                            zhuoyueFilter = ZhuoyueWingFilter.ALL
                                        }
                                    },
                                    label = { Text(campus.shortLabel) },
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    val next = !showCustomPeriods
                                    showCustomPeriods = next
                                    queryMode = if (next) {
                                        ClassroomQueryMode.CUSTOM
                                    } else {
                                        ClassroomQueryMode.PRESET
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                                Text(if (showCustomPeriods) "收起节次" else "自选节次")
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0L to "今天", 1L to "明天", 2L to "后天").forEach { (offset, label) ->
                                FilterChip(
                                    selected = dateOffsetDays == offset,
                                    onClick = { dateOffsetDays = offset },
                                    label = { Text(label) },
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ClassroomPeriodGroup.entries.forEach { group ->
                                val groupState = groupSelectionState(selectedPeriods, group)
                                FilterChip(
                                    selected = groupState != GroupSelectionState.NONE,
                                    onClick = {
                                        if (queryMode == ClassroomQueryMode.PRESET) {
                                            selectedPeriods = group.periods
                                            executeQuery(
                                                currentQuery.copy(periods = group.periods),
                                            )
                                        } else {
                                            selectedPeriods = toggleGroup(selectedPeriods, group)
                                        }
                                    },
                                    label = { Text("${group.label} ${group.rangeLabel}") },
                                )
                            }
                        }

                        if (showCustomPeriods) {
                            Text(
                                text = "已选节次：${if (selectedPeriods.isEmpty()) "未选" else "第 ${formatPeriods(selectedPeriods)} 节"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                (1..13).forEach { period ->
                                    val selected = period in selectedPeriods
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            selectedPeriods = if (selected) {
                                                selectedPeriods - period
                                            } else {
                                                selectedPeriods + period
                                            }
                                        },
                                        label = { Text("$period") },
                                    )
                                }
                            }
                        }

                        if (buildingChoices.size > 1) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(buildingChoices) { b ->
                                    FilterChip(
                                        selected = selectedBuilding == b,
                                        onClick = { selectedBuilding = b },
                                        label = { Text(b) },
                                    )
                                }
                            }
                        }

                        if (selectedCampus == FreeClassroomCampus.GUZHENKOU) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ZhuoyueWingFilter.entries.forEach { wing ->
                                    FilterChip(
                                        selected = zhuoyueFilter == wing,
                                        onClick = { zhuoyueFilter = wing },
                                        label = { Text(wing.label) },
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Switch(
                                    checked = onlyAvailable,
                                    onCheckedChange = { onlyAvailable = it },
                                )
                                Text(
                                    text = "仅看空闲教室",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }

                            Button(
                                onClick = { executeQuery(currentQuery) },
                                enabled = !isLoading && selectedPeriods.isNotEmpty(),
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .padding(end = 6.dp)
                                            .size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                                Text(if (isLoading) "查询中…" else "查询")
                            }
                        }
                    }
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                item {
                    ExpressiveGroupedCard(
                        position = GroupedCardPosition.SINGLE,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.padding(bottom = 14.dp),
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            if (displayedResult != null) {
                item {
                    val freeTotal = displayedResult.availableRooms.size
                    val allTotal = displayedResult.rooms.size
                    ExpressiveSectionHeader(
                        title = "查询结果 · ${displayedResult.query.date} · 第 ${formatPeriods(displayedResult.query.periods)} 节",
                        subtitle = "全选时段均空闲 $freeTotal / $allTotal 间（仅以校方 d.js 命中为准）",
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }

                if (groups.isEmpty()) {
                    item {
                        ExpressiveGroupedCard(position = GroupedCardPosition.SINGLE) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MeetingRoom,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = if (onlyAvailable) "当前条件暂无空闲教室" else "当前条件暂无教室记录",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                } else {
                    itemsIndexed(
                        items = groups,
                        key = { _, group -> group.building },
                    ) { index, group ->
                        ExpressiveGroupedCard(
                            position = GroupedCardPosition.forIndex(index, groups.size),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = group.building,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    ExpressiveStatusBadge(
                                        text = "空闲 ${group.freeCount} / ${group.totalCount}",
                                        containerColor = if (group.freeCount > 0) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        },
                                        contentColor = if (group.freeCount > 0) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    group.rooms.forEach { room ->
                                        val containerColor = if (room.available) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        }
                                        val contentColor = if (room.available) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        Surface(
                                            shape = MaterialTheme.shapes.small,
                                            color = containerColor,
                                            contentColor = contentColor,
                                        ) {
                                            val cap = room.capacity?.let { " · ${it}座" } ?: ""
                                            Text(
                                                text = "${room.name}$cap",
                                                style = MaterialTheme.typography.labelLarge,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (!isLoading && errorMessage == null) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    ExpressiveGroupedCard(position = GroupedCardPosition.SINGLE) {
                        Text(
                            text = "点击“查询”向 app.upc.edu.cn 发起公开空闲教室查询。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}
