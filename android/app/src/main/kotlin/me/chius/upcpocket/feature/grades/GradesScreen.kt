package me.chius.upcpocket.feature.grades

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import me.chius.upcpocket.core.auth.UpcJwxtAuthEndpoints
import me.chius.upcpocket.core.designsystem.components.HeroAtAGlanceCard
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.SchoolAccountState
import me.chius.upcpocket.core.webview.ControlledSchoolWebViewDialog
import me.chius.upcpocket.core.webview.ControlledWebViewContract

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradesScreen(
    accountState: SchoolAccountState,
    uiState: GradesUiState,
    onBack: () -> Unit,
    onOpenSchoolAccount: () -> Unit,
    onRefresh: () -> Unit,
    onClearCache: () -> Unit,
    onAdoptWebViewCookies: (Map<String, String>, ControlledWebViewContract.SessionContext) -> Unit,
    onDismissInteraction: () -> Unit,
) {
    var preset by remember { mutableStateOf(GradeFilterPreset.ALL) }
    var selectedTerm by remember { mutableStateOf<String?>(null) }
    val excludedIds = remember { mutableStateMapOf<String, Boolean>() }
    val simulatedScores = remember { mutableStateMapOf<String, Double>() }
    var editingRecord by remember { mutableStateOf<CourseGradeRecord?>(null) }
    var showWebView by remember { mutableStateOf(false) }

    LaunchedEffect(accountState.status, accountState.generation) {
        if (accountState.status == AccountStatus.SAVED_UNVERIFIED &&
            uiState.snapshot == null &&
            !uiState.isRefreshing
        ) {
            onRefresh()
        }
    }

    val snapshot = uiState.snapshot
    val filteredRecords = remember(snapshot, preset, selectedTerm) {
        GpaCalculator.filterByPreset(
            records = snapshot?.records.orEmpty(),
            preset = preset,
            selectedTerm = selectedTerm,
        )
    }
    val excludedSet = remember(excludedIds.toMap()) {
        excludedIds.filterValues { it }.keys
    }
    val simMap = remember(simulatedScores.toMap()) {
        simulatedScores.toMap()
    }
    val summary = remember(filteredRecords, excludedSet, simMap) {
        GpaCalculator.calculate(
            filteredRecords = filteredRecords,
            excludedIds = excludedSet,
            simulatedScores = simMap,
        )
    }
    val hasSimulation = excludedSet.isNotEmpty() || simMap.isNotEmpty()

    if (showWebView && uiState.interactionUrl != null) {
        val ctx = remember(accountState.accountId, accountState.generation, uiState.interactionUrl) {
            ControlledWebViewContract.SessionContext(
                accountId = accountState.accountId,
                generation = accountState.generation,
                entryUrl = uiState.interactionUrl,
            )
        }
        ControlledSchoolWebViewDialog(
            sessionContext = ctx,
            accountState = accountState,
            scopeHosts = ControlledWebViewContract.jwxtScopeHosts,
            targetCookieHost = UpcJwxtAuthEndpoints.JWXT_HOST,
            completionPathPrefixes = setOf("/jsxsd/"),
            onCookiesCaptured = { cookies ->
                showWebView = false
                onAdoptWebViewCookies(cookies, ctx)
            },
            onDismiss = {
                showWebView = false
                onDismissInteraction()
            },
        )
    }

    editingRecord?.let { target ->
        SimulateScoreDialog(
            record = target,
            currentSimulated = simulatedScores[target.id],
            onApply = { newScore ->
                if (newScore == null) {
                    simulatedScores.remove(target.id)
                } else {
                    simulatedScores[target.id] = newScore
                    excludedIds.remove(target.id)
                }
                editingRecord = null
            },
            onDismiss = { editingRecord = null },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("成绩与 GPA 推演") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (hasSimulation) {
                        IconButton(
                            onClick = {
                                excludedIds.clear()
                                simulatedScores.clear()
                            },
                        ) {
                            Icon(Icons.Rounded.RestartAlt, contentDescription = "重置推演")
                        }
                    }
                    if (snapshot != null) {
                        IconButton(onClick = onClearCache, enabled = !uiState.isRefreshing) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "清除成绩缓存")
                        }
                    }
                    if (accountState.status == AccountStatus.SAVED_UNVERIFIED) {
                        IconButton(onClick = onRefresh, enabled = !uiState.isRefreshing) {
                            if (uiState.isRefreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.Refresh, contentDescription = "刷新成绩")
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            if (uiState.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    HeroAtAGlanceCard(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (hasSimulation) "实时推演结果（不改动教务原表）" else "教务学分加权统计",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                            )
                            if (uiState.connectionStatus == GradesConnectionStatus.OFFLINE_CACHE) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                ) {
                                    Text(
                                        text = "离线缓存",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "加权平均分",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                                )
                                Text(
                                    text = summary.weightedAverage?.let { "%.2f".format(Locale.ROOT, it) } ?: "--",
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                summary.weightedDelta?.let { d ->
                                    val sign = if (d >= 0) "+" else ""
                                    Text(
                                        text = "$sign%.3f".format(Locale.ROOT, d),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "教务 GPA (5.0)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                                )
                                Text(
                                    text = summary.officialGpa?.let { "%.2f".format(Locale.ROOT, it) } ?: "--",
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                summary.gpaDelta?.let { d ->
                                    val sign = if (d >= 0) "+" else ""
                                    Text(
                                        text = "$sign%.3f".format(Locale.ROOT, d),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "计入 ${summary.courseCount} 门 · 共 %.1f 学分（通过 %.1f 学分）".format(
                                Locale.ROOT,
                                summary.totalCredits,
                                summary.passedCredits,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (uiState.interactionUrl != null) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = uiState.interactionReason ?: "需要完成教务系统安全验证",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                FilledTonalButton(onClick = { showWebView = true }) {
                                    Icon(Icons.Rounded.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("网页验证")
                                }
                            }
                        }
                    }
                }

                if (!uiState.errorMessage.isNullOrBlank()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = uiState.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                }

                if (accountState.status != AccountStatus.SAVED_UNVERIFIED) {
                    item {
                        HeroAtAGlanceCard(onClick = onOpenSchoolAccount) {
                            Text(
                                text = "请先配置学校账号",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "配置后即可拉取全部教务成绩并进行保研/重修模拟推演。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = onOpenSchoolAccount) {
                                Text("配置学校账号")
                            }
                        }
                    }
                } else if (snapshot != null) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(GradeFilterPreset.entries) { option ->
                                    FilterChip(
                                        selected = preset == option,
                                        onClick = { preset = option },
                                        label = { Text(option.label) },
                                    )
                                }
                            }
                            if (snapshot.terms.isNotEmpty()) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item {
                                        FilterChip(
                                            selected = selectedTerm == null,
                                            onClick = { selectedTerm = null },
                                            label = { Text("全部学期") },
                                        )
                                    }
                                    items(snapshot.terms) { term ->
                                        FilterChip(
                                            selected = selectedTerm == term,
                                            onClick = { selectedTerm = term },
                                            label = { Text(term) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text(
                            text = "勾选框可临时剔除某门课；点击课程卡片可输入模拟成绩（${filteredRecords.size} 门）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }

                    items(items = filteredRecords, key = { it.id }) { record ->
                        val isExcluded = excludedIds[record.id] == true
                        val simScore = simulatedScores[record.id]
                        GradeRecordCard(
                            record = record,
                            isExcluded = isExcluded,
                            simulatedScore = simScore,
                            onToggleExcluded = {
                                if (isExcluded) excludedIds.remove(record.id) else excludedIds[record.id] = true
                            },
                            onClickSimulate = { editingRecord = record },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GradeRecordCard(
    record: CourseGradeRecord,
    isExcluded: Boolean,
    simulatedScore: Double?,
    onToggleExcluded: () -> Unit,
    onClickSimulate: () -> Unit,
) {
    val displayScore = simulatedScore?.let {
        if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(Locale.ROOT, it)
    } ?: record.scoreText
    val displayGpa = simulatedScore?.let { GpaCalculator.scoreToUpcGpa(it) } ?: record.gpaPoint

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = if (isExcluded) {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClickSimulate),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = !isExcluded,
                onCheckedChange = { onToggleExcluded() },
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = record.courseName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = if (isExcluded) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (isExcluded) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (simulatedScore != null) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ) {
                            Text(
                                text = "推演",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = listOfNotNull(
                        record.term.takeIf { it.isNotBlank() },
                        "%.1f 学分".format(Locale.ROOT, record.credit),
                        record.courseAttribute.takeIf { it.isNotBlank() },
                        record.examNature.takeIf { it.isNotBlank() && it != "正常考试" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Text(
                    text = displayScore,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        isExcluded -> MaterialTheme.colorScheme.onSurfaceVariant
                        simulatedScore != null -> MaterialTheme.colorScheme.tertiary
                        (record.numericScore ?: 100.0) < 60.0 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "GPA %.2f".format(Locale.ROOT, displayGpa),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SimulateScoreDialog(
    record: CourseGradeRecord,
    currentSimulated: Double?,
    onApply: (Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    var inputText by remember(record.id, currentSimulated) {
        mutableStateOf(
            (currentSimulated ?: record.numericScore)?.let {
                if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(Locale.ROOT, it)
            }.orEmpty(),
        )
    }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模拟成绩 · ${record.courseName}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "原始成绩：${record.scoreText}（%.1f 学分 · 仅本地推演，不修改学校数据）".format(
                        Locale.ROOT,
                        record.credit,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = inputText,
                    onValueChange = {
                        inputText = it
                        errorText = null
                    },
                    label = { Text("目标分数（0–100）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = errorText != null,
                    supportingText = errorText?.let { msg -> { Text(msg) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf(95, 90, 85, 80, 75, 60)) { preset ->
                        FilterChip(
                            selected = inputText.toDoubleOrNull() == preset.toDouble(),
                            onClick = {
                                inputText = preset.toString()
                                errorText = null
                            },
                            label = { Text("$preset") },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = inputText.trim().toDoubleOrNull()
                    if (value == null || value !in 0.0..100.0) {
                        errorText = "请输入 0 到 100 之间的分数"
                    } else {
                        onApply(value)
                    }
                },
            ) {
                Text("应用模拟")
            }
        },
        dismissButton = {
            Row {
                if (currentSimulated != null) {
                    TextButton(onClick = { onApply(null) }) {
                        Text("恢复原分")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        },
    )
}
