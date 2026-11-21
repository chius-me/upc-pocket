package me.chius.upcpocket.feature.shiguang

import android.webkit.CookieManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.chius.upcpocket.core.auth.UpcShiguangAuthEndpoints
import me.chius.upcpocket.core.designsystem.components.HeroAtAGlanceCard
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.SchoolAccountState
import me.chius.upcpocket.core.webview.ControlledSchoolWebViewDialog
import me.chius.upcpocket.core.webview.ControlledWebViewContract

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShiguangScreen(
    accountState: SchoolAccountState,
    uiState: ShiguangUiState,
    onBack: () -> Unit,
    onOpenSchoolAccount: () -> Unit,
    onSelectTab: (ShiguangTab) -> Unit,
    onRefresh: (keyword: String) -> Unit,
    onLoadPage: (page: Int, mine: Boolean) -> Unit,
    onOpenDetail: (ShiguangActivity) -> Unit,
    onDismissDetail: () -> Unit,
    onAdoptWebViewCookies: (Map<String, String>, ControlledWebViewContract.SessionContext) -> Unit,
    onDismissInteraction: () -> Unit,
) {
    var keywordInput by remember { mutableStateOf(uiState.keyword) }
    var showWebView by remember { mutableStateOf(false) }

    LaunchedEffect(accountState.status, accountState.generation) {
        if (accountState.status == AccountStatus.SAVED_UNVERIFIED &&
            uiState.activitiesPage == null &&
            !uiState.isLoading
        ) {
            onRefresh(keywordInput)
        }
    }

    uiState.interactionRequest?.let { req ->
        if (showWebView) {
            ControlledSchoolWebViewDialog(
                sessionContext = req.sessionContext,
                accountState = accountState,
                scopeHosts = req.scopeHosts,
                targetCookieHost = req.targetHost,
                completionPathPrefixes = req.completionPathPrefixes,
                onCookiesCaptured = { cookies ->
                    showWebView = false
                    onAdoptWebViewCookies(cookies, req.sessionContext)
                },
                onDismiss = {
                    showWebView = false
                    onDismissInteraction()
                },
            )
        }
    }

    uiState.selectedActivity?.let { activity ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismissDetail,
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = uiState.selectedDetail?.title ?: activity.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (uiState.isLoadingDetail) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("正在加载活动详情…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                uiState.selectedDetail?.let { detail ->
                    detail.fields.forEach { (label, value) ->
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
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                    }
                    if (!detail.description.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = detail.description,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("石光第二课堂") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (accountState.status == AccountStatus.SAVED_UNVERIFIED) {
                        IconButton(onClick = { onRefresh(keywordInput) }, enabled = !uiState.isLoading) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
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
            if (uiState.isLoading) {
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
                if (accountState.status != AccountStatus.SAVED_UNVERIFIED) {
                    item {
                        HeroAtAGlanceCard(onClick = onOpenSchoolAccount) {
                            Text("请先配置学校账号", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "保存凭据后可查询第二课堂活动、学时进度与个人成绩单。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = onOpenSchoolAccount) { Text("前往学校账号") }
                        }
                    }
                } else {
                    if (uiState.interactionRequest != null) {
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
                                        text = uiState.interactionRequest.reason,
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

                    item {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            ShiguangTab.entries.forEachIndexed { index, tab ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index, ShiguangTab.entries.size),
                                    selected = uiState.activeTab == tab,
                                    onClick = { onSelectTab(tab) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    }

                    if (uiState.activeTab == ShiguangTab.ACTIVITIES) {
                        item {
                            OutlinedTextField(
                                value = keywordInput,
                                onValueChange = { keywordInput = it },
                                label = { Text("搜索活动名称") },
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = { onRefresh(keywordInput) }) {
                                        Icon(Icons.Rounded.Search, contentDescription = "搜索")
                                    }
                                },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { onRefresh(keywordInput) }),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        val pageData = uiState.activitiesPage
                        if (pageData == null || pageData.items.isEmpty()) {
                            item {
                                HeroAtAGlanceCard {
                                    Text(
                                        text = if (uiState.isLoading) "正在拉取活动…" else "暂无匹配的第二课堂活动",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else {
                            items(items = pageData.items, key = { "${it.kind}_${it.id}" }) { act ->
                                ShiguangActivityCard(activity = act, onClick = { onOpenDetail(act) })
                            }
                            item {
                                PaginationBar(
                                    page = pageData.page,
                                    totalPages = pageData.totalPages,
                                    enabled = !uiState.isLoading,
                                    onPrev = { onLoadPage(pageData.page - 1, false) },
                                    onNext = { onLoadPage(pageData.page + 1, false) },
                                )
                            }
                        }
                    } else {
                        uiState.summary?.let { summary ->
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
                                        Column {
                                            Text("累计总学时", style = MaterialTheme.typography.labelLarge)
                                            Text(
                                                text = "${summary.totalHours?.toPlainString() ?: "--"} h",
                                                style = MaterialTheme.typography.displaySmall,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text("累计总学分", style = MaterialTheme.typography.labelLarge)
                                            Text(
                                                text = summary.totalCredits?.toPlainString() ?: "--",
                                                style = MaterialTheme.typography.headlineLarge,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        summary.modules.forEach { module ->
                                            val hrs = module.hours?.toFloat() ?: 0f
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                ) {
                                                    Text(
                                                        text = module.label,
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.SemiBold,
                                                    )
                                                    val rankText = module.rank?.let { " · 排名 ${it.position}/${it.total}" }.orEmpty()
                                                    Text(
                                                        text = "${module.hours?.toPlainString() ?: "0"}学时 / ${module.credits?.toPlainString() ?: "0"}学分$rankText",
                                                        style = MaterialTheme.typography.labelMedium,
                                                    )
                                                }
                                                LinearProgressIndicator(
                                                    progress = { (hrs / 40f).coerceIn(0.05f, 1f) },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(6.dp),
                                                    strokeCap = StrokeCap.Round,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val minePage = uiState.participationsPage
                        if (!uiState.identityVerified) {
                            item {
                                HeroAtAGlanceCard {
                                    Text(
                                        text = "个人记录暂未显示",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "学校仅返回空记录或尚未确认学号归属，请尝试刷新同步。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else if (minePage != null && minePage.items.isNotEmpty()) {
                            item {
                                Text(
                                    text = "已参与活动",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                            }
                            items(items = minePage.items, key = { "mine_${it.kind}_${it.id}" }) { act ->
                                ShiguangActivityCard(activity = act, onClick = { onOpenDetail(act) })
                            }
                            item {
                                PaginationBar(
                                    page = minePage.page,
                                    totalPages = minePage.totalPages,
                                    enabled = !uiState.isLoading,
                                    onPrev = { onLoadPage(minePage.page - 1, true) },
                                    onNext = { onLoadPage(minePage.page + 1, true) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShiguangActivityCard(
    activity: ShiguangActivity,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = activity.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(
                        text = activity.fields["学时"] ?: activity.fields["分类"] ?: if (activity.kind == ShiguangKind.SOCIAL_PRACTICE) "社会实践" else "校园活动",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            val meta = listOfNotNull(
                activity.fields["活动时间"]?.let { "时间 $it" },
                activity.fields["地点"]?.let { "地点 $it" },
                activity.fields["报名截止时间"]?.let { "截止 $it" },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PaginationBar(
    page: Int,
    totalPages: Int?,
    enabled: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onPrev, enabled = enabled && page > 1) {
            Text("上一页")
        }
        Text(
            text = totalPages?.let { "第 $page / $it 页" } ?: "第 $page 页",
            style = MaterialTheme.typography.labelLarge,
        )
        OutlinedButton(
            onClick = onNext,
            enabled = enabled && (totalPages == null || page < totalPages),
        ) {
            Text("下一页")
        }
    }
}
