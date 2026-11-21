package me.chius.upcpocket.feature.card

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.io.IOException
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import me.chius.upcpocket.core.auth.CardAuthResult
import me.chius.upcpocket.core.auth.CasHttpRequest
import me.chius.upcpocket.core.auth.CasHttpTransport
import me.chius.upcpocket.core.auth.CasTransportException
import me.chius.upcpocket.core.auth.DefaultCasHttpTransport
import me.chius.upcpocket.core.auth.UpcCardAuthEndpoints
import me.chius.upcpocket.core.auth.UpcCardAuthenticator
import me.chius.upcpocket.core.data.SchoolAccountRepository
import me.chius.upcpocket.core.model.AccountInvalidationReason
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.SchoolAccountState
import me.chius.upcpocket.core.model.SchoolLoginCredential
import me.chius.upcpocket.core.model.SchoolStudentIdentity
import me.chius.upcpocket.core.webview.ControlledWebViewContract
import me.chius.upcpocket.core.webview.clearSchoolWebViewCookies

class CardWalletRepository internal constructor(
    private val accountStateProvider: () -> SchoolAccountState,
    private val identityProvider: () -> SchoolStudentIdentity?,
    private val matchesAccountGeneration: (String, Long) -> Boolean,
    private val credentialExecutor: suspend (suspend (SchoolLoginCredential) -> CardAuthResult) -> CardAuthResult?,
    private val authenticator: UpcCardAuthenticator,
    private val pendingStore: RechargePendingStore,
    private val weChatChecker: () -> Boolean,
    private val weChatLauncher: (String) -> Boolean,
    private val transport: CasHttpTransport = DefaultCasHttpTransport,
    private val clearWebViewCookies: () -> Unit = ::clearSchoolWebViewCookies,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        context: Context,
        schoolAccountRepository: SchoolAccountRepository,
        transport: CasHttpTransport = DefaultCasHttpTransport,
    ) : this(
        accountStateProvider = { schoolAccountRepository.state.value },
        identityProvider = { schoolAccountRepository.studentIdentity.value },
        matchesAccountGeneration = schoolAccountRepository::matchesAccountGeneration,
        credentialExecutor = { block -> schoolAccountRepository.withLoginCredential(block) },
        authenticator = UpcCardAuthenticator(transport),
        pendingStore = RechargePendingStore(context),
        weChatChecker = { resolveWeChatPayIntent(context, "weixin://wap/pay") != null },
        weChatLauncher = { rawUri ->
            val safe = CardWalletProtocol.validateWeChatUri(rawUri)
            val intent = resolveWeChatPayIntent(context, safe)
            if (intent == null) {
                false
            } else {
                try {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (_: Exception) {
                    false
                }
            }
        },
        transport = transport,
    )

    private val mutex = Mutex()
    private val businessEpoch = AtomicLong(0L)

    @Volatile
    private var inMemoryToken: String? = null

    @Volatile
    private var preparedRecharge: CardRechargePrepared? = null

    private val _uiState = MutableStateFlow(CardWalletUiState())
    val uiState: StateFlow<CardWalletUiState> = _uiState.asStateFlow()

    suspend fun loadHistory(
        month: String? = _uiState.value.selectedMonth,
        loadNextPage: Boolean = false,
    ) = withContext(ioDispatcher) {
        mutex.withLock {
            val state = accountStateProvider()
            val identity = identityProvider()
            val epoch = businessEpoch.get()
            if (state.status != AccountStatus.SAVED_UNVERIFIED || identity == null) {
                _uiState.update { it.copy(isLoadingHistory = false, historyError = "请先配置包含有效学号的学校账号") }
                return@withLock
            }
            val targetPage = if (loadNextPage) (_uiState.value.loadedPage + 1).coerceAtLeast(1) else 1
            _uiState.update {
                it.copy(
                    selectedMonth = month,
                    isLoadingHistory = true,
                    historyError = null,
                    interactionUrl = null,
                    interactionReason = null,
                )
            }

            val token = ensureTokenLocked(identity, epoch) ?: return@withLock
            try {
                val (page, stats) = fetchHistoryPageAndStats(token, month, targetPage)
                if (!stillValid(identity, epoch)) return@withLock
                _uiState.update { current ->
                    val mergedRows = if (loadNextPage && current.selectedMonth == month) {
                        (current.rows + page.rows).distinctBy { it.id }
                    } else {
                        page.rows
                    }
                    current.copy(
                        rows = mergedRows,
                        loadedPage = targetPage,
                        totalPages = page.pages,
                        totalRows = page.total,
                        periodStats = stats ?: current.periodStats,
                        dailySpend = CardWalletProtocol.buildDailySpendPoints(mergedRows),
                        isLoadingHistory = false,
                        historyError = null,
                    )
                }
            } catch (e: CardWalletException) {
                if (e.code == "P-LOGIN") {
                    inMemoryToken = null
                    val freshToken = ensureTokenLocked(identity, epoch) ?: return@withLock
                    try {
                        val (page, stats) = fetchHistoryPageAndStats(freshToken, month, targetPage)
                        if (!stillValid(identity, epoch)) return@withLock
                        _uiState.update { current ->
                            val mergedRows = if (loadNextPage && current.selectedMonth == month) {
                                (current.rows + page.rows).distinctBy { it.id }
                            } else {
                                page.rows
                            }
                            current.copy(
                                rows = mergedRows,
                                loadedPage = targetPage,
                                totalPages = page.pages,
                                totalRows = page.total,
                                periodStats = stats ?: current.periodStats,
                                dailySpend = CardWalletProtocol.buildDailySpendPoints(mergedRows),
                                isLoadingHistory = false,
                                historyError = null,
                            )
                        }
                        return@withLock
                    } catch (retryErr: Exception) {
                        _uiState.update { it.copy(isLoadingHistory = false, historyError = retryErr.message) }
                    }
                } else {
                    _uiState.update { it.copy(isLoadingHistory = false, historyError = e.message) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingHistory = false, historyError = e.message ?: "流水查询失败") }
            }
        }
    }

    suspend fun adoptWebViewUrl(
        callbackUrl: String,
        sessionContext: ControlledWebViewContract.SessionContext,
    ) = withContext(ioDispatcher) {
        clearWebViewCookies()
        val state = accountStateProvider()
        val identity = identityProvider()
        if (!sessionContext.isStillValidFor(state) || identity == null) {
            _uiState.update { it.copy(interactionUrl = null, interactionReason = null) }
            return@withContext
        }
        _uiState.update { it.copy(interactionUrl = null, interactionReason = null, isLoadingHistory = true) }
        when (val res = authenticator.completeWebLoginResult(callbackUrl, identity.studentId)) {
            is CardAuthResult.Success -> {
                inMemoryToken = res.token
                loadHistory(month = _uiState.value.selectedMonth, loadNextPage = false)
            }
            is CardAuthResult.UpstreamError -> {
                _uiState.update { it.copy(isLoadingHistory = false, historyError = res.message) }
            }
            else -> {
                _uiState.update { it.copy(isLoadingHistory = false, historyError = "校园卡登录未生效") }
            }
        }
    }

    fun dismissInteractionPrompt() {
        _uiState.update { it.copy(interactionUrl = null, interactionReason = null) }
    }

    suspend fun prepareRecharge() = withContext(ioDispatcher) {
        val wechatOk = weChatChecker()
        _uiState.update { it.copy(isWeChatAvailable = wechatOk, isPreparingRecharge = true, rechargeMessage = null) }
        mutex.withLock {
            val identity = identityProvider()
            val epoch = businessEpoch.get()
            if (identity == null) {
                _uiState.update { it.copy(isPreparingRecharge = false, rechargeMessage = "请先保存有效学号") }
                return@withLock
            }
            val token = ensureTokenLocked(identity, epoch)
            if (token == null) {
                _uiState.update { it.copy(isPreparingRecharge = false, rechargeMessage = "校园卡登录未完成") }
                return@withLock
            }
            try {
                val pending = pendingStore.read(identity)
                val prepared = fetchRechargePrepared(token, identity.studentId)
                if (!stillValid(identity, epoch)) return@withLock
                preparedRecharge = prepared
                _uiState.update {
                    it.copy(
                        isPreparingRecharge = false,
                        rechargeRules = prepared.rules,
                        pendingOrder = pending,
                        rechargeMessage = if (pending != null) "检测到一笔未确认订单，请先核对状态" else null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isPreparingRecharge = false,
                        rechargeMessage = e.message ?: "无法读取校园卡充值规则",
                    )
                }
            }
        }
    }

    /**
     * Single-shot order creation + WeChat pay handoff. Never auto-retries an unknown create/pay POST.
     */
    suspend fun submitRecharge(yuanInput: String): Boolean = withContext(NonCancellable + ioDispatcher) {
        if (!weChatChecker()) {
            _uiState.update { it.copy(isWeChatAvailable = false, rechargeMessage = "本机未检测到微信客户端，无法发起支付") }
            return@withContext false
        }
        val identity = identityProvider() ?: return@withContext false
        val epoch = businessEpoch.get()
        val prepared = preparedRecharge ?: run {
            _uiState.update { it.copy(rechargeMessage = "请先加载充值规则") }
            return@withContext false
        }
        val token = inMemoryToken ?: run {
            _uiState.update { it.copy(rechargeMessage = "校园卡登录会话尚未就绪") }
            return@withContext false
        }

        val amount = try {
            CardWalletProtocol.validateAmount(yuanInput, prepared.rules)
        } catch (e: CardWalletException) {
            _uiState.update { it.copy(rechargeMessage = e.message) }
            return@withContext false
        }

        _uiState.update { it.copy(isSubmittingRecharge = true, rechargeMessage = null) }
        val attemptId = UUID.randomUUID().toString()
        var pending = RechargePendingRecord(
            accountId = identity.accountId,
            studentId = identity.studentId,
            generation = identity.generation,
            attemptId = attemptId,
            yuan = amount,
        )
        return@withContext try {
            pendingStore.save(identity, pending)
            val orderId = createThirdOrder(token, prepared, amount)
            pending = pending.copy(orderId = orderId)
            pendingStore.save(identity, pending)

            val payUri = fetchPayDeepLink(token, prepared, pending) { payId ->
                pending = pending.copy(payId = payId)
                pendingStore.save(identity, pending)
            }
            if (!stillValid(identity, epoch)) {
                _uiState.update { it.copy(isSubmittingRecharge = false, rechargeMessage = "账号已切换，已取消拉起") }
                return@withContext false
            }
            val launched = weChatLauncher(payUri)
            _uiState.update {
                it.copy(
                    isSubmittingRecharge = false,
                    pendingOrder = pending,
                    rechargeMessage = if (launched) "已跳转微信支付，完成后请点击核对订单状态" else "未能拉起微信支付页",
                )
            }
            launched
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isSubmittingRecharge = false,
                    pendingOrder = runCatching { pendingStore.read(identity) }.getOrNull() ?: pending,
                    rechargeMessage = e.message ?: "下单状态未知，请先核对微信账单后再刷新状态",
                )
            }
            false
        }
    }

    suspend fun refreshPendingOrderStatus() = withContext(ioDispatcher) {
        val identity = identityProvider() ?: return@withContext
        val token = inMemoryToken ?: return@withContext
        val pending = _uiState.value.pendingOrder ?: runCatching { pendingStore.read(identity) }.getOrNull() ?: return@withContext
        val orderId = pending.orderId ?: return@withContext
        try {
            val url = "${CardWalletProtocol.BASE}/charge/order/personal_data?orderid=$orderId" +
                (pending.payId?.let { "&payid=$it" } ?: "") + "&synAccessSource=h5"
            val body = cardGet(url, token)
            val root = CardWalletProtocol.parseJsonObject(body)
            if ((root["code"] as? JsonPrimitive)?.intOrNull != 200) {
                throw CardWalletException("未能查询订单状态", "P-STATUS")
            }
            val list = root["orderList"] as? JsonArray ?: throw CardWalletException("订单列表异常", "P-STATUS")
            val info = list.singleOrNull() as? JsonObject ?: throw CardWalletException("未找到对应订单", "P-STATUS")
            if (CardWalletProtocol.field(info, "orderid") != orderId) {
                throw CardWalletException("订单编号不一致", "P-ORDER-OWNER")
            }
            val status = when (CardWalletProtocol.field(info, "status")) {
                "1" -> RechargeOrderStatus.SUCCEEDED
                "0" -> RechargeOrderStatus.FAILED
                "2" -> RechargeOrderStatus.EXPIRED
                else -> RechargeOrderStatus.UNKNOWN
            }
            if (status != RechargeOrderStatus.UNKNOWN) {
                pendingStore.removeIfMatches(identity, pending.attemptId)
            }
            _uiState.update {
                it.copy(
                    pendingOrder = if (status == RechargeOrderStatus.UNKNOWN) pending else null,
                    lastRechargeStatus = status,
                    rechargeMessage = when (status) {
                        RechargeOrderStatus.SUCCEEDED -> "充值订单已支付成功"
                        RechargeOrderStatus.FAILED -> "充值订单支付失败"
                        RechargeOrderStatus.EXPIRED -> "充值订单已过期"
                        RechargeOrderStatus.UNKNOWN -> "订单尚未确认，请稍后再次查询"
                    },
                )
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(rechargeMessage = e.message ?: "查单失败，请稍后重试") }
        }
    }

    suspend fun onAccountInvalidated(reason: AccountInvalidationReason) = withContext(ioDispatcher) {
        businessEpoch.incrementAndGet()
        inMemoryToken = null
        preparedRecharge = null
        clearWebViewCookies()
        if (reason == AccountInvalidationReason.ACCOUNT_CHANGED_OR_CLEARED) {
            pendingStore.clearAll()
        }
        _uiState.value = CardWalletUiState(isWeChatAvailable = weChatChecker())
    }

    private suspend fun ensureTokenLocked(identity: SchoolStudentIdentity, epoch: Long): String? {
        inMemoryToken?.let { return it }
        val result = credentialExecutor { cred -> authenticator.authenticate(cred) }
            ?: run {
                _uiState.update { it.copy(isLoadingHistory = false, historyError = "学校账号未保存或需要重新保存") }
                return null
            }
        if (!stillValid(identity, epoch)) {
            _uiState.update { it.copy(isLoadingHistory = false) }
            return null
        }
        return when (result) {
            is CardAuthResult.Success -> {
                inMemoryToken = result.token
                result.token
            }
            is CardAuthResult.InvalidCredentials -> {
                _uiState.update { it.copy(isLoadingHistory = false, historyError = "学号或统一认证密码错误") }
                null
            }
            is CardAuthResult.InteractionRequired -> {
                _uiState.update {
                    it.copy(
                        isLoadingHistory = false,
                        interactionUrl = result.interactionUrl,
                        interactionReason = result.reason,
                        historyError = result.reason,
                    )
                }
                null
            }
            is CardAuthResult.NetworkError -> {
                _uiState.update { it.copy(isLoadingHistory = false, historyError = result.message) }
                null
            }
            is CardAuthResult.UpstreamError -> {
                _uiState.update { it.copy(isLoadingHistory = false, historyError = result.message) }
                null
            }
        }
    }

    private suspend fun fetchHistoryPageAndStats(
        token: String,
        month: String?,
        pageNumber: Int,
    ): Pair<CardHistoryPage, CardPeriodStats?> {
        val monthQuery = month?.let { raw ->
            val ym = java.time.YearMonth.parse(raw)
            val start = ym.atDay(1).toString()
            val end = ym.atEndOfMonth().toString()
            "&timeFrom=$start&timeTo=$end"
        }.orEmpty()
        val turnoverUrl = "${CardWalletProtocol.BASE}/berserker-search/search/personal/turnover" +
            "?size=30&current=$pageNumber&synAccessSource=h5$monthQuery"
        val page = CardWalletProtocol.parseTurnoverPage(cardGet(turnoverUrl, token))

        val statsUrl = if (month != null) {
            val ym = java.time.YearMonth.parse(month)
            "${CardWalletProtocol.BASE}/berserker-search/statistics/turnover/count" +
                "?timeFrom=${ym.atDay(1)}&timeTo=${ym.atEndOfMonth()}&synAccessSource=h5"
        } else {
            val now = LocalDate.now(ZoneId.of("Asia/Shanghai"))
            "${CardWalletProtocol.BASE}/berserker-search/statistics/turnover/count" +
                "?timeFrom=${now.withDayOfMonth(1)}&timeTo=$now&synAccessSource=h5"
        }
        val stats = runCatching { CardWalletProtocol.parsePeriodStats(cardGet(statsUrl, token)) }.getOrNull()
        return page to stats
    }

    private suspend fun fetchRechargePrepared(token: String, studentId: String): CardRechargePrepared {
        val front = CardWalletProtocol.parseEnvelopeData(
            cardGet("${CardWalletProtocol.BASE}/berserker-app/frontInfo?synAccessSource=h5", token),
        )
        val settings = CardWalletProtocol.parseJsonObject(CardWalletProtocol.field(front, "getFrontConfig"))
        val ecard = CardWalletProtocol.parseJsonObject(CardWalletProtocol.field(front, "getEcardConfig"))
        if (CardWalletProtocol.field(ecard, "type") != "2") {
            throw CardWalletException("当前学校一卡通类型不支持在线充值", "P-CARD-TYPE")
        }
        val feeItem = CardWalletProtocol.field(settings, "recharge")
            .takeIf { it.matches(Regex("[0-9]{1,10}")) }
            ?: throw CardWalletException("学校未返回充值项目编号", "P-CONFIG")

        val cardData = CardWalletProtocol.parseEnvelopeData(
            cardGet("${CardWalletProtocol.BASE}/berserker-app/ykt/tsm/queryCard?scene=recharge", token),
        )
        val cards = cardData["card"] as? JsonArray ?: throw CardWalletException("校园卡信息异常", "P-CARDS")
        val own = cards.mapNotNull { it as? JsonObject }.filter { CardWalletProtocol.field(it, "sno") == studentId }
        if (own.size != 1) throw CardWalletException("未核对到唯一本人校园卡", "P-CARD-OWNER")
        val card = own.single()
        if (CardWalletProtocol.field(card, "lostflag") !in setOf("0", "false") ||
            CardWalletProtocol.field(card, "freezeflag") !in setOf("0", "false")
        ) {
            throw CardWalletException("校园卡处于挂失或冻结状态，不可充值", "P-CARD-BLOCKED")
        }
        val yktAccount = CardWalletProtocol.field(card, "account")
            .takeIf { it.matches(Regex("[0-9]{1,24}")) }
            ?: throw CardWalletException("校园卡账号缺失", "P-CARD-ACCOUNT")

        val feeJson = CardWalletProtocol.parseJsonObject(
            cardGet("${CardWalletProtocol.BASE}/charge/feeitem/singleFeeitem?feeitemid=$feeItem", token),
        )
        val feeObj = feeJson["feeitem"] as? JsonObject
            ?: throw CardWalletException("缺少充值费项规则", "P-FEE-CONFIG")

        val scriptBody = transport.execute(
            CasHttpRequest(url = CardWalletProtocol.SIGNER_SOURCE, method = "GET"),
        ).let { resp ->
            if (resp.statusCode != 200) throw CardWalletException("无法加载充值签名组件", "P-SIGN-FORMAT")
            resp.body
        }
        val signer = CardWalletProtocol.parseSigner(scriptBody)
        val rules = CardWalletProtocol.parseRechargeRules(feeObj, settings)
        return CardRechargePrepared(
            feeItem = feeItem,
            yktAccount = yktAccount,
            signer = signer,
            rules = rules,
        )
    }

    private suspend fun createThirdOrder(
        token: String,
        prepared: CardRechargePrepared,
        amount: String,
    ): String {
        val fields = signFields(
            signer = prepared.signer,
            fields = linkedMapOf(
                "feeitemid" to prepared.feeItem,
                "appid" to prepared.signer.appId,
                "tranamt" to amount,
                "source" to "app",
                "synjones-auth" to "bearer $token",
                "yktcard" to prepared.yktAccount,
                "synAccessSource" to "h5",
                "abstracts" to """{"type":"recharge","othersRecharge":false}""",
            ),
        )
        var url = "${CardWalletProtocol.BASE}/charge/order/thirdOrder"
        var resp = cardPost(url, fields, token)
        repeat(8) {
            val next = if (resp.statusCode in setOf(301, 302, 303)) {
                resp.location
            } else {
                CardWalletProtocol.literalRedirect(resp.body)
            } ?: throw CardWalletException("创建充值订单后未获跳转", "P-ORDER-REDIRECT")
            url = CardWalletProtocol.upgradeSchoolUrl(url, next)
            CardWalletProtocol.orderFromPaymentUrl(url)?.let { return it }
            resp = cardRequestRaw(url, method = "GET", token = token)
        }
        throw CardWalletException("创建订单跳转次数超限", "P-ORDER-REDIRECT")
    }

    private suspend fun fetchPayDeepLink(
        token: String,
        prepared: CardRechargePrepared,
        pending: RechargePendingRecord,
        onMethodSelected: (String) -> Unit,
    ): String {
        val orderId = pending.orderId ?: throw CardWalletException("缺少充值订单号", "P-ORDER-UNKNOWN")
        val infoBody = cardGet(
            "${CardWalletProtocol.BASE}/charge/pay/getpayinfo?orderid=$orderId&userAgent=h5&synAccessSource=h5",
            token,
        )
        val info = CardWalletProtocol.parseJsonObject(infoBody)
        val order = info["order"] as? JsonObject ?: throw CardWalletException("支付详情缺少订单对象", "P-ORDER-INFO")
        if (CardWalletProtocol.field(order, "orderid") != orderId) {
            throw CardWalletException("支付订单号不匹配", "P-ORDER-OWNER")
        }
        val list = info["payList"] as? JsonArray ?: throw CardWalletException("缺少可用支付方式", "P-PAY-METHOD")
        val wxMethods = list.mapNotNull { it as? JsonObject }.filter {
            CardWalletProtocol.field(it, "name").contains("微信")
        }
        if (wxMethods.size != 1) throw CardWalletException("未识别到唯一微信支付方式", "P-PAY-METHOD")
        val method = wxMethods.single()
        val payId = CardWalletProtocol.field(method, "payid")
            .takeIf { it.matches(Regex("[0-9]{1,8}")) }
            ?: throw CardWalletException("支付方式编号异常", "P-PAY-METHOD")
        val code = CardWalletProtocol.field(method, "code")
            .takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
            ?: throw CardWalletException("支付代码异常", "P-PAY-METHOD")
        onMethodSelected(payId)

        val signedFields = signFields(
            signer = prepared.signer,
            fields = mapOf(
                "paytypeid" to payId,
                "paytype" to code,
                "paystep" to "2",
                "orderid" to orderId,
                "redirect_url" to "${CardWalletProtocol.BASE}/payment/?name=result",
                "userAgent" to "h5",
            ),
        )
        val payResp = cardPost("${CardWalletProtocol.BASE}/blade-pay/pay", signedFields, token)
        if (payResp.statusCode != 200) throw CardWalletException("发起支付失败", "P-PAY-RESPONSE")
        val root = CardWalletProtocol.parseJsonObject(payResp.body)
        if ((root["success"] as? JsonPrimitive)?.booleanOrNull != true) {
            throw CardWalletException("学校未确认微信支付创建", "P-PAY-RESPONSE")
        }
        val data = CardWalletProtocol.parseEnvelopeData(payResp.body)
        if (CardWalletProtocol.field(data, "orderid") != orderId) {
            throw CardWalletException("支付响应订单号不匹配", "P-ORDER-OWNER")
        }
        val cashierUrl = CardWalletProtocol.parseCashierForm(CardWalletProtocol.field(data, "paysubmit"))
        var currentUrl = cashierUrl
        // Do not send any campus token to Tencent wx.tenpay.com!
        var wxResp = transport.execute(
            CasHttpRequest(
                url = currentUrl,
                method = "POST",
                headers = mapOf(
                    "Referer" to "${CardWalletProtocol.BASE}/",
                    "Accept" to "text/html,application/json",
                ),
                formBody = "",
            ),
        )
        repeat(4) {
            if (wxResp.statusCode == 200) {
                return CardWalletProtocol.extractWeChatDeepLink(wxResp.body)
            }
            val loc = wxResp.location ?: throw CardWalletException("微信收银台缺少跳转", "P-WX-REDIRECT")
            val next = URI(currentUrl).resolve(loc).toString()
            if (next.startsWith("weixin:")) return CardWalletProtocol.validateWeChatUri(next)
            val nextUri = URI(next)
            if (nextUri.scheme != "https" ||
                nextUri.host != "wx.tenpay.com" ||
                nextUri.path != "/cgi-bin/mmpayweb-bin/checkmweb" ||
                nextUri.rawUserInfo != null ||
                nextUri.port !in setOf(-1, 443)
            ) {
                throw CardWalletException("微信跳转目标未通过域名检查", "P-WX-REDIRECT")
            }
            currentUrl = next
            wxResp = transport.execute(
                CasHttpRequest(
                    url = currentUrl,
                    method = "GET",
                    headers = mapOf("Referer" to "${CardWalletProtocol.BASE}/"),
                ),
            )
        }
        throw CardWalletException("未取得微信支付链接", "P-WX-REDIRECT")
    }

    private fun signFields(signer: CardRechargeSigner, fields: Map<String, String>): Map<String, String> {
        val ts = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val nonce = UUID.randomUUID().toString().replace("-", "")
        return signer.sign(fields, ts, nonce)
    }

    private suspend fun cardGet(url: String, token: String): String {
        val resp = cardRequestRaw(url, method = "GET", token = token)
        if (resp.statusCode != 200) throw CardWalletException("校园卡接口返回 HTTP ${resp.statusCode}", "P-HTTP")
        return resp.body
    }

    private suspend fun cardPost(url: String, fields: Map<String, String>, token: String) =
        cardRequestRaw(
            url = url,
            method = "POST",
            token = token,
            formBody = DefaultCasHttpTransport.formEncode(fields.entries.map { it.key to it.value }),
        )

    private suspend fun cardRequestRaw(
        url: String,
        method: String,
        token: String,
        formBody: String? = null,
    ) = try {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host != "mb-xyk.upc.edu.cn") {
            throw CardWalletException("仅允许访问校园卡官方 HTTPS 域名", "P-ORIGIN")
        }
        transport.execute(
            CasHttpRequest(
                url = url,
                method = method,
                headers = mapOf(
                    "synjones-auth" to "bearer $token",
                    "synAccessSource" to "h5",
                    "Referer" to "${CardWalletProtocol.BASE}/campus-card/",
                    "Accept" to "application/json, text/html",
                ),
                formBody = formBody,
            ),
        ).also { resp ->
            if (resp.statusCode in setOf(401, 403)) {
                throw CardWalletException("校园卡登录已失效", "P-LOGIN")
            }
        }
    } catch (e: CasTransportException) {
        throw CardWalletException(e.message ?: "校园卡网络连接失败", "P-NETWORK")
    }

    private fun stillValid(identity: SchoolStudentIdentity, epoch: Long): Boolean =
        matchesAccountGeneration(identity.accountId, identity.generation) && businessEpoch.get() == epoch

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"

        fun resolveWeChatPayIntent(context: Context, uriString: String): Intent? {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).setPackage(WECHAT_PACKAGE)
            val info = runCatching {
                context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            }.getOrNull() ?: return null
            if (info.packageName != WECHAT_PACKAGE || !info.exported) return null
            return intent.setComponent(ComponentName(info.packageName, info.name))
        }
    }
}
