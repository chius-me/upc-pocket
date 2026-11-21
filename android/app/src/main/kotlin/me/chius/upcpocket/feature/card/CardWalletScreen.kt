package me.chius.upcpocket.feature.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import me.chius.upcpocket.core.auth.UpcCardAuthEndpoints
import me.chius.upcpocket.core.designsystem.components.BentoMetricTile
import me.chius.upcpocket.core.designsystem.components.HeroAtAGlanceCard
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.SchoolAccountState
import me.chius.upcpocket.core.webview.ControlledSchoolWebViewDialog
import me.chius.upcpocket.core.webview.ControlledWebViewContract

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CardWalletScreen(
    accountState: SchoolAccountState,
    balanceState: CardBalanceUiState,
    walletState: CardWalletUiState,
    onBack: () -> Unit,
    onOpenSchoolAccount: () -> Unit,
    onRefreshBalance: () -> Unit,
    onLoadHistory: (month: String?, nextPage: Boolean) -> Unit,
    onPrepareRecharge: () -> Unit,
    onSubmitRecharge: (yuan: String) -> Unit,
    onRefreshPendingOrder: () -> Unit,
    onAdoptWebViewUrl: (String, ControlledWebViewContract.SessionContext) -> Unit,
    onDismissInteraction: () -> Unit,
) {
    var showRechargeSheet by remember { mutableStateOf(false) }
    var showWebView by remember { mutableStateOf(false) }
    var selectedMonth by remember { mutableStateOf(walletState.selectedMonth) }

    val recentMonths = remember {
        val nowYm = YearMonth.now(ZoneId.of("Asia/Shanghai"))
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT)
        (0..5).map { nowYm.minusMonths(it.toLong()).format(fmt) }
    }

    LaunchedEffect(accountState.status, accountState.generation) {
        if (accountState.status == AccountStatus.SAVED_UNVERIFIED && accountState.isValidStudentId) {
            if (balanceState.snapshot == null && !balanceState.isRefreshing) {
                onRefreshBalance()
            }
            if (walletState.rows.isEmpty() && !walletState.isLoadingHistory) {
                onLoadHistory(selectedMonth, false)
            }
        }
    }

    if (showWebView && walletState.interactionUrl != null) {
        val ctx = remember(accountState.accountId, accountState.generation, walletState.interactionUrl) {
            ControlledWebViewContract.SessionContext(
                accountId = accountState.accountId,
                generation = accountState.generation,
                entryUrl = walletState.interactionUrl,
            )
        }
        ControlledSchoolWebViewDialog(
            sessionContext = ctx,
            accountState = accountState,
            scopeHosts = ControlledWebViewContract.cardScopeHosts,
            targetCookieHost = UpcCardAuthEndpoints.CARD_HOST,
            completionPathPrefixes = setOf("/plat/", "/berserker-", "/h5/"),
            allowCompletionWithoutCookies = true,
            onCompletionUrlCaptured = { url ->
                showWebView = false
                onAdoptWebViewUrl(url, ctx)
            },
            onCookiesCaptured = {},
            onDismiss = {
                showWebView = false
                onDismissInteraction()
            },
        )
    }

    if (showRechargeSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var amountInput by remember { mutableStateOf("50") }
        val rules = walletState.rechargeRules

        ModalBottomSheet(
            onDismissRequest = { showRechargeSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "校园卡微信充值",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (walletState.isPreparingRecharge) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("正在向学校平台读取可充金额规则…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val presetValues = rules?.presets?.map { it.stripTrailingZeros().toPlainString() }
                    ?: listOf("10", "20", "50", "100")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presetValues.forEach { preset ->
                        FilterChip(
                            selected = amountInput == preset,
                            onClick = { amountInput = preset },
                            label = { Text("¥$preset") },
                        )
                    }
                }
                if (rules == null || !rules.presetOnly) {
                    OutlinedTextField(
                        value = amountInput,
                        onValueChange = { amountInput = it },
                        label = { Text("充值金额（元）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                rules?.let {
                    Text(
                        text = "单笔限额 ¥${it.minimum.toPlainString()} – ¥${it.maximum.toPlainString()}" +
                            if (it.integerOnly) "（仅整数元）" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!walletState.rechargeMessage.isNullOrBlank()) {
                    Text(
                        text = walletState.rechargeMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Button(
                    onClick = { onSubmitRecharge(amountInput) },
                    enabled = !walletState.isSubmittingRecharge && !walletState.isPreparingRecharge && rules != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (walletState.isSubmittingRecharge) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在下单并拉起微信…")
                    } else {
                        Text("下单并打开微信支付")
                    }
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("校园卡钱包") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (accountState.status == AccountStatus.SAVED_UNVERIFIED && accountState.isValidStudentId) {
                        IconButton(
                            onClick = {
                                onRefreshBalance()
                                onLoadHistory(selectedMonth, false)
                            },
                            enabled = !balanceState.isRefreshing && !walletState.isLoadingHistory,
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "刷新余额与流水")
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
            if (balanceState.isRefreshing || walletState.isLoadingHistory) {
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
                    val snap = balanceState.snapshot
                    HeroAtAGlanceCard(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Rounded.CreditCard, contentDescription = null)
                                Text(
                                    text = "中国石油大学（华东）校园卡",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "¥",
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                            Text(
                                text = snap?.totalBalanceText ?: "--",
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        if (snap != null) {
                            Text(
                                text = "卡内 ¥${snap.balanceText} · 过渡余额 ¥${snap.transitionText}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    showRechargeSheet = true
                                    onPrepareRecharge()
                                },
                                enabled = accountState.status == AccountStatus.SAVED_UNVERIFIED && accountState.isValidStudentId,
                            ) {
                                Icon(Icons.Rounded.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("微信充值")
                            }
                            FilledTonalButton(
                                onClick = {
                                    onRefreshBalance()
                                    onLoadHistory(selectedMonth, false)
                                },
                                enabled = accountState.status == AccountStatus.SAVED_UNVERIFIED && accountState.isValidStudentId,
                            ) {
                                Text("同步最新")
                            }
                        }
                    }
                }

                if (accountState.status != AccountStatus.SAVED_UNVERIFIED || !accountState.isValidStudentId) {
                    item {
                        HeroAtAGlanceCard(onClick = onOpenSchoolAccount) {
                            Text("需配置本人有效学号账号", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "保存本人学号与密码后，可查询卡内余额、月度流水和发起充值。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = onOpenSchoolAccount) { Text("前往学校账号") }
                        }
                    }
                }

                walletState.pendingOrder?.let { pending ->
                    item {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "未确认充值 ¥${pending.yuan}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = walletState.rechargeMessage ?: "请核对微信账单后点击查询订单状态",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                FilledTonalButton(onClick = onRefreshPendingOrder) {
                                    Text("核对状态")
                                }
                            }
                        }
                    }
                }

                if (walletState.interactionUrl != null) {
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
                                    text = walletState.interactionReason ?: "需要通过学校网页验证后继续",
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

                val err = walletState.historyError ?: balanceState.errorMessage
                if (!err.isNullOrBlank()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = err, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(14.dp))
                        }
                    }
                }

                if (walletState.dailySpend.isNotEmpty()) {
                    item {
                        DailySpendChartCard(points = walletState.dailySpend)
                    }
                }

                walletState.periodStats?.let { stats ->
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            BentoMetricTile(
                                title = "本月支出",
                                icon = Icons.Rounded.CreditCard,
                                metricValue = formatCents(stats.expenseCents),
                                metricUnit = "元",
                                subtitle = selectedMonth ?: "当前统计",
                                onClick = {},
                                modifier = Modifier.weight(1f),
                                iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                                iconTint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            BentoMetricTile(
                                title = "本月入账",
                                icon = Icons.Rounded.AccountBalanceWallet,
                                metricValue = formatCents(stats.incomeCents),
                                metricUnit = "元",
                                subtitle = selectedMonth ?: "当前统计",
                                onClick = {},
                                modifier = Modifier.weight(1f),
                                iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }

                if (accountState.status == AccountStatus.SAVED_UNVERIFIED && accountState.isValidStudentId) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                FilterChip(
                                    selected = selectedMonth == null,
                                    onClick = {
                                        selectedMonth = null
                                        onLoadHistory(null, false)
                                    },
                                    label = { Text("全部近期") },
                                )
                            }
                            items(recentMonths) { ym ->
                                FilterChip(
                                    selected = selectedMonth == ym,
                                    onClick = {
                                        selectedMonth = ym
                                        onLoadHistory(ym, false)
                                    },
                                    label = { Text(ym) },
                                )
                            }
                        }
                    }

                    item {
                        Text(
                            text = "交易流水（共 ${walletState.totalRows} 笔）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }

                    if (walletState.rows.isEmpty() && !walletState.isLoadingHistory) {
                        item {
                            HeroAtAGlanceCard {
                                Text(
                                    text = "当前筛选范围内暂无消费或充值流水",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(items = walletState.rows, key = { it.id }) { row ->
                            TransactionRowCard(row = row)
                        }
                        if (walletState.canLoadMore) {
                            item {
                                OutlinedButton(
                                    onClick = { onLoadHistory(selectedMonth, true) },
                                    enabled = !walletState.isLoadingHistory,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("加载下一页流水")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DailySpendChartCard(points: List<DailySpendPoint>) {
    val maxVal = (points.maxOfOrNull { it.expenseCents } ?: 1L).coerceAtLeast(100L)
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "近期每日消费分布",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                points.forEach { pt ->
                    val ratio = (pt.expenseCents.toFloat() / maxVal.toFloat()).coerceIn(0.08f, 1f)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = if (pt.expenseCents >= 100L) "${pt.expenseCents / 100}" else ".",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .width(22.dp)
                                .height((68f * ratio).dp)
                                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = pt.dayLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionRowCard(row: CardHistoryRow) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = row.formattedSignedAmount,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (row.isIncome) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

private fun formatCents(cents: Long): String {
    val absVal = kotlin.math.abs(cents)
    return "%d.%02d".format(Locale.ROOT, absVal / 100L, absVal % 100L)
}
